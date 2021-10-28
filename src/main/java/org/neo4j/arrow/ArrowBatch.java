package org.neo4j.arrow;

import org.apache.arrow.memory.AllocationListener;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VectorLoader;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.FixedSizeListVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.UnionVector;
import org.apache.arrow.vector.complex.impl.UnionListReader;
import org.apache.arrow.vector.complex.impl.UnionListWriter;
import org.apache.arrow.vector.complex.writer.BaseWriter;
import org.apache.arrow.vector.ipc.message.ArrowFieldNode;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.*;
import org.checkerframework.checker.units.qual.A;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Container of Arrow vectors and metadata, schema, etc.
 * <p>
 *     This is a mess right now :-(
 * </p>
 */
public class ArrowBatch implements AutoCloseable {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ArrowBatch.class);

    final Schema schema;
    final BufferAllocator allocator;

    final List<List<ValueVector>> vectorSpace = new ArrayList<>();
    final String[] fieldNames;

    long rowCount = 0;
    int maxBatchSize = -1;

    public ArrowBatch(Schema schema, BufferAllocator parentAllocator, String name) {
        this.schema = schema;
        this.allocator = parentAllocator.newChildAllocator("arrow-batch-" + name, 0, Config.maxStreamMemory);
        final List<Field> fields = schema.getFields();
        fieldNames = new String[fields.size()];

        IntStream.range(0, fields.size())
                .forEach(idx -> {
                    final String fieldName = fields.get(idx).getName();
                    fieldNames[idx] = fieldName;
                    vectorSpace.add(new ArrayList<>());
                    logger.info("added {} to vectorspace", fieldName);
                });
    }

    public void appendRoot(VectorSchemaRoot root) {
        // TODO: validate schema option?
        final int rows = root.getRowCount();

        if (maxBatchSize > 0 && rows > maxBatchSize) {
            logger.error("maxBatchSize: {}, root row count: {}", maxBatchSize, rows);
            throw new RuntimeException("BOOP BOOP BOOP!");
        }
        maxBatchSize = Math.max(maxBatchSize, rows);

        final int cols = root.getSchema().getFields().size();
        logger.debug("appending root with {} rows, {} vectors", rows, cols);

        IntStream.range(0, cols)
                .parallel()
                .forEach(idx -> {
                    try {
                        final FieldVector fv = root.getVector(idx);
                        final TransferPair pair = fv.getTransferPair(allocator);
                        pair.transfer();
                        final ValueVector to = pair.getTo();
                        vectorSpace.get(idx).add(to);
                    } catch (Exception e) {
                        logger.error("Exception caught while transferring field vector", e);
                        throw new RuntimeException(e);
                    }
                });

        rowCount += rows;
        logger.debug("new rowCount {}", rowCount);
    }

    public long estimateSize() {
        // XXX not thread safe
        return vectorSpace.stream()
                .flatMap(Collection::stream)
                .mapToLong(ValueVector::getBufferSize)
                .sum();
    }

    public long actualSize() {
        // XXX not thread safe
        return vectorSpace.stream()
                .flatMap(Collection::stream)
                .map(vec -> vec.getBuffers(false))
                .flatMap(Arrays::stream)
                .mapToLong(ArrowBuf::capacity)
                .sum();
    }

    public static class BatchedVector implements Closeable {
        private final List<ValueVector> vectors;
        private final int batchSize;
        private final String name;
        private final long rowCount;
        private int watermark = 0;

        private BatchedVector(String name, List<ValueVector> vectors, int batchSize, long rowCount) {
            this.name = name;
            this.vectors = vectors;
            this.batchSize = batchSize;
            this.rowCount = rowCount;

            // XXX this is ugly...but we need to know where our search gets tough
            for (int i=0; i<vectors.size(); i++) {
                if (vectors.get(i).getValueCount() < batchSize) {
                    watermark = i;
                    break;
                }
            }
        }

        public List<ValueVector> getVectors() {
            return this.vectors;
        }

        public String getName() {
            return name;
        }

        public Class<?> getBaseType() {
            return vectors.get(0).getClass();
        }

        /**
         * Find an item from the vector space, accounting for the fact the tail end might have batch sizes less than
         * the original batch size.
         * @param index index of item to retrieve from the space
         * @return Object value if found, otherwise null
         */
        private Object translateIndex(long index) {
            assert (index < rowCount);

            // assumption is our batches only become "short" at the end
            int column = (int) Math.floorDiv(index, batchSize);
            int offset = (int) (index % batchSize);
            //logger.info("looking up index {} (col = {}, offset = {})", index, column, offset);

            try {
                if (column < watermark) {
                    // trivial case
                    ValueVector vector = vectors.get(column);
                    return vector.getObject(offset);
                }

                // harder, we need to search varying size columns. start at our watermark.
                int pos = watermark * batchSize;
                column = watermark;
                ValueVector vector = vectors.get(column);
                logger.trace("starting search from pos {} to find index {} (watermark: {})", pos, index, watermark);
                while ((index - pos) >= vector.getValueCount()) {
                    column++;
                    pos += vector.getValueCount();
                    vector = vectors.get(column); // XXX eventually will barf...need better handling here
                }
                return vector.getObject((int) (index - pos));
            } catch (Exception e) {
                logger.error(String.format("failed to get index %d for %s (offset %d, column %d, batchSize %d)",
                        index, vectors.get(0).getName(), offset, column, batchSize), e);
                logger.trace(String.format("debug: %s",
                        vectors.stream()
                                .map(ValueVector::getValueCount)
                                .map(String::valueOf)
                                .collect(Collectors.joining(", "))));
                return null;
            }
        }

        public long getNodeId(long index) {
            final Long nodeId = (Long) translateIndex(index);
            if (nodeId == null) {
                throw new RuntimeException(String.format("cant get nodeId for index %d", index));
            }
            if (nodeId < 0) {
                throw new RuntimeException("nodeId < 0?!?!");
            }
            return nodeId;
        }

        public List<String> getLabels(long index) {
            final List<?> list = (List<?>) translateIndex(index);
            if (list == null) {
                logger.warn("failed to find list at index {}, index", index);
                return List.of();
            }
            return list.stream().map(Object::toString).collect(Collectors.toList());
        }

        public String getType(long index) {
            // XXX Assumption for now is we're dealing with a VarCharVector
            final Text type = (Text) translateIndex(index);
            if (type == null) {
                logger.warn("failed to find type string at index {}, index", index);
                return "";
            }
            return type.toString();
        }

        public Object getObject(long index) {
            final Object o = translateIndex(index);
            if (o == null) {
                logger.warn("failed to find list at index {}, index", index);
                return null;
            }
            return o;
        }

        public List<?> getList(long index) {
            final List<?> list = (List<?>) translateIndex(index);
            if (list == null) {
                logger.warn("failed to find list at index {}, index", index);
                return List.of();
            }
            return list;
        }

        public Optional<Class<?>> getDataClass() {
            final ValueVector v = vectors.get(0);
            if (v instanceof ListVector) {
                return Optional.of(((ListVector) v).getDataVector().getClass());
            } else if (v instanceof FixedSizeListVector) {
                final FixedSizeListVector fv = (FixedSizeListVector) v;
                return Optional.of(fv.getDataVector().getClass());
            }
            return Optional.empty();
        }

        @Override
        public void close() {
            vectors.forEach(ValueVector::close);
        }
    }

    public BatchedVector getVector(int index) {
        if (index < 0 || index >= fieldNames.length)
            throw new RuntimeException("index out of range");

        return new BatchedVector(fieldNames[index], vectorSpace.get(index), maxBatchSize, rowCount);
    }

    public BatchedVector getVector(String name) {
        logger.info("...finding vector for name {}", name);
        int index = 0;
        for ( ; index<fieldNames.length; index++) {
            if (fieldNames[index].equals(name))
                break;
        }
        if (index == fieldNames.length)
            throw new RuntimeException(String.format("name %s not found in arrow batch", name));

        return new BatchedVector(name, vectorSpace.get(index), maxBatchSize, rowCount);
    }

    public List<BatchedVector> getFieldVectors() {
        return IntStream.range(0, fieldNames.length)
                .mapToObj(this::getVector)
                .collect(Collectors.toList());
    }

    public int getRowCount() {
        return (int) rowCount; // XXX  TODO
    }

    public Schema getSchema() {
        return schema;
    }

    @Override
    public void close() {
        vectorSpace.forEach(list -> list.forEach(ValueVector::close));
        allocator.close();
    }
}