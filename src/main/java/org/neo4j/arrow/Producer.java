package org.neo4j.arrow;

import org.apache.arrow.compression.Lz4CompressionCodec;
import org.apache.arrow.flight.*;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.util.AutoCloseables;
import org.apache.arrow.vector.*;
import org.apache.arrow.vector.complex.BaseListVector;
import org.apache.arrow.vector.complex.FixedSizeListVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.impl.UnionFixedSizeListWriter;
import org.apache.arrow.vector.complex.impl.UnionListWriter;
import org.apache.arrow.vector.complex.writer.BaseWriter;
import org.apache.arrow.vector.compression.CompressionUtil;
import org.apache.arrow.vector.ipc.message.ArrowFieldNode;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.TransferPair;
import org.neo4j.arrow.action.ActionHandler;
import org.neo4j.arrow.action.Outcome;
import org.neo4j.arrow.action.StatusHandler;
import org.neo4j.arrow.job.Job;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Producer encapsulates the core Arrow Flight orchestration logic including both the RPC
 * framework and stream wrangling.
 *
 * <p>
 *     RPC {@link Action}s are made available via registration into the {@link #handlerMap} via
 *     {@link #registerHandler(ActionHandler)}.
 * </p>
 * <p>
 *     Streams, aka "Flights", are indexed by {@link Ticket}s and kept in {@link #flightMap}. As a
 *     consequence, there's currently no multi-process support. The {@link Job} backing the stream
 *     is kept in {@link #jobMap}.
 * </p>
 */
public class Producer implements FlightProducer, AutoCloseable {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Producer.class);

    final Location location;
    final BufferAllocator allocator;

    /* Holds all known current streams based on their tickets */
    final protected Map<Ticket, FlightInfo> flightMap = new ConcurrentHashMap<>();
    /* Holds all existing jobs based on their tickets */
    final protected Map<Ticket, Job> jobMap = new ConcurrentHashMap<>();
    /* All registered Action handlers */
    final protected Map<String, ActionHandler> handlerMap = new ConcurrentHashMap<>();

    public Producer(BufferAllocator parentAllocator, Location location) {
        this.location = location;
        this.allocator = parentAllocator.newChildAllocator("neo4j-flight-producer", 0, Long.MAX_VALUE);

        // Default event handlers
        handlerMap.put(StatusHandler.STATUS_ACTION, new StatusHandler());
    }

    private static class FlushWork {
        public final List<ValueVector> vectors;
        public final int vectorDimension;
        private FlushWork(List<ValueVector> vectors, int vectorDimension) {
            this.vectors = vectors;
            this.vectorDimension = vectorDimension;
        }
        public static FlushWork from(List<ValueVector> vectors, int vectorDimension) {
            return new FlushWork(vectors, vectorDimension);
        }
    }

    /**
     * Attempt to get an Arrow stream for the given {@link Ticket}.
     *
     * @param context the {@link org.apache.arrow.flight.FlightProducer.CallContext} that contains
     *                details on the client (the peer) attempting to access the stream.
     * @param ticket the {@link Ticket} for the Flight
     * @param listener the {@link org.apache.arrow.flight.FlightProducer.ServerStreamListener} for
     *                 returning results in the stream back to the caller.
     */
    @Override
    public void getStream(CallContext context, Ticket ticket, ServerStreamListener listener) {
        logger.debug("getStream called: context={}, ticket={}", context, ticket.getBytes());

        final Job job = jobMap.get(ticket);
        if (job == null) {
            listener.error(CallStatus.NOT_FOUND.withDescription("No job for ticket").toRuntimeException());
            return;
        }
        final FlightInfo info = flightMap.get(ticket);
        if (info == null) {
            listener.error(CallStatus.NOT_FOUND.withDescription("No flight for ticket").toRuntimeException());
            return;
        }

        try (BufferAllocator baseAllocator = allocator.newChildAllocator(
                String.format("convert-%s", UUID.nameUUIDFromBytes(ticket.getBytes())),
                0, Config.maxStreamMemory);
             BufferAllocator transmitAllocator = allocator.newChildAllocator(
                     String.format("transmit-%s", UUID.nameUUIDFromBytes(ticket.getBytes())),
                     0, Config.maxStreamMemory);
                VectorSchemaRoot root = VectorSchemaRoot.create(info.getSchema(), baseAllocator)) {

            final VectorLoader loader = new VectorLoader(root);
            logger.debug("using schema: {}", info.getSchema().toJson());

            // TODO: do we need to allocate explicitly? Or can we just not?
            final List<Field> fieldList = info.getSchema().getFields();

            // listener.setUseZeroCopy(true);
            // Add a job cancellation hook
            listener.setOnCancelHandler(() -> {
                logger.info("client disconnected or cancelled stream");
                job.cancel(true);
            });

            // Signal the client that we're about to start the stream
            listener.setUseZeroCopy(false);
            listener.start(root);

            // A bit hacky, but provide the core processing logic as a Consumer
            // TODO: handle batches of records to decrease frequency of calls
            final AtomicBoolean errored = new AtomicBoolean(false);

            // Tunable partition size...
            // TODO: figure out ideal way to set a good default based on host
            final int maxPartitions = Config.arrowMaxPartitions;

            // Map<String, BaseWriter.ListWriter> writerMap
            final Map<String, BaseWriter.ListWriter>[] partitionedWriters = new Map[maxPartitions];
            final List<FieldVector>[] partitionedVectorList = new List[maxPartitions];
            final BufferAllocator[] bufferAllocators = new BufferAllocator[maxPartitions];
            final AtomicInteger[] partitionedCounts = new AtomicInteger[maxPartitions];
            final Semaphore[] partitionedSemaphores = new Semaphore[maxPartitions];
            final Semaphore transferMutex = new Semaphore(1);

            // Our work queue for multiple producers, but a single consumer
            final BlockingDeque<FlushWork> workQueue = new LinkedBlockingDeque<>();
            final AtomicBoolean isFeeding = new AtomicBoolean(true);
            final CompletableFuture<Void> flushJob = CompletableFuture.runAsync(() -> {
               while (isFeeding.get() || !workQueue.isEmpty()) {
                   try {
                       final FlushWork work = workQueue.pollFirst(1, TimeUnit.SECONDS);
                       if (work != null) {
                           transferMutex.acquire();
                           flush(listener, loader, work.vectors, work.vectorDimension);
                           transferMutex.release();
                       }
                   } catch (InterruptedException e) {
                       logger.error("flush job interrupted!");
                       return;
                   }
               }
            });

            // Wasteful, but pre-init for now
            for (int i=0; i<maxPartitions; i++) {
                bufferAllocators[i] = baseAllocator.newChildAllocator(String.format("partition-%d", i), 0, Long.MAX_VALUE);
                partitionedSemaphores[i] = new Semaphore(1);
                partitionedWriters[i] = new HashMap<>();
                partitionedCounts[i] = new AtomicInteger(0);
                partitionedVectorList[i] = new ArrayList<>(0);
            }

            // Core job logic
            job.consume((record, partitionKey) -> {
                // Trivial partitioning scheme...
                final int partition = partitionKey % maxPartitions;
                try {
                    partitionedSemaphores[partition].acquire();
                    final BufferAllocator streamAllocator = bufferAllocators[partition];
                    final AtomicInteger cnt = partitionedCounts[partition];
                    final int idx = cnt.getAndIncrement();
                    final List<FieldVector> vectorList = partitionedVectorList[partition];
                    final Map<String, BaseWriter.ListWriter> writerMap = partitionedWriters[partition];

                    if (idx == 0) {
                        // (re)init field vectors
                        if (vectorList.size() == 0) {
                            for (Field field : fieldList) {
                                FieldVector fieldVector = field.createVector(streamAllocator);
                                vectorList.add(fieldVector);
                            }
                        }
                        for (FieldVector fieldVector : vectorList) {
                            int retries = 1000;
                            fieldVector.setInitialCapacity(Config.arrowBatchSize);
                            while (!fieldVector.allocateNewSafe() && --retries > 0) {
                                logger.error("failed to allocate memory for field {}", fieldVector.getName());
                                try { Thread.sleep(100); } catch (Exception ignored) {};
                            }
                        }
                    }

                    // TODO: refactor to using fixed arrays for speed
                    // Our translation guts...CPU intensive
                    for (int n=0; n<fieldList.size(); n++) {
                        final Field field = fieldList.get(n);
                        final RowBasedRecord.Value value = record.get(n);
                        final FieldVector vector = vectorList.get(n);

                        if (vector instanceof IntVector) {
                            ((IntVector) vector).set(idx, value.asInt());
                        } else if (vector instanceof BigIntVector) {
                            ((BigIntVector) vector).set(idx, value.asLong());
                        } else if (vector instanceof Float4Vector) {
                            ((Float4Vector) vector).set(idx, value.asFloat());
                        } else if (vector instanceof Float8Vector) {
                            ((Float8Vector) vector).set(idx, value.asDouble());
                        } else if (vector instanceof VarCharVector) {
                            ((VarCharVector) vector).setSafe(idx, value.asString().getBytes(StandardCharsets.UTF_8));
                        } else if (vector instanceof FixedSizeListVector) {
                            // Used for GdsJobs
                            final UnionFixedSizeListWriter writer =
                                    (UnionFixedSizeListWriter) writerMap.computeIfAbsent(field.getName(),
                                    s -> ((FixedSizeListVector) vector).getWriter());
                            writer.startList();
                            // XXX: Assumes all values share the same type and first value is non-null
                            switch (value.type()) {
                                case INT_ARRAY:
                                    for (int i : value.asIntArray())
                                        writer.writeInt(i);
                                    break;
                                case LONG_ARRAY:
                                    for (long l : value.asLongArray())
                                        writer.writeBigInt(l);
                                    break;
                                case FLOAT_ARRAY:
                                    for (float f : value.asFloatArray())
                                        writer.writeFloat4(f);
                                    break;
                                case DOUBLE_ARRAY:
                                    for (double d : value.asDoubleArray())
                                        writer.writeFloat8(d);
                                    break;
                                default:
                                    if (errored.compareAndSet(false, true)) {
                                        Exception e = CallStatus.INVALID_ARGUMENT.withDescription("invalid array type")
                                                .toRuntimeException();
                                        listener.error(e);
                                        logger.error("invalid array type: " + value.type(), e);
                                        job.cancel(true);
                                    }
                                    return;
                            }
                            writer.setValueCount(value.size());
                            writer.endList();
                        } else if (vector instanceof ListVector) {
                            // Used for Cypher
                            final UnionListWriter writer =
                                    (UnionListWriter) writerMap.computeIfAbsent(field.getName(),
                                            s -> ((ListVector) vector).getWriter());
                            writer.startList();
                            // XXX: Assumes all values are doubles for now :-(
                            for (Double d : value.asDoubleList())
                                writer.writeFloat8(d);
                            writer.setValueCount(value.asList().size());
                            writer.endList();
                        }
                    }

                    // Flush at our batch size limit and reset our batch states.
                    if ((idx + 1) == Config.arrowBatchSize) {
                        // Yolo?
                        final ArrayList<ValueVector> copy = new ArrayList<>();
                        final int vectorSize = idx + 1;
                        try {
                            transferMutex.acquire();
                            for (FieldVector vector : vectorList) {
                                final TransferPair tp = vector.getTransferPair(transmitAllocator);
                                tp.transfer();
                                copy.add(tp.getTo());
                            }
                        } finally {
                            transferMutex.release();
                        }

                        // Queue the flush work
                        workQueue.add(FlushWork.from(copy, vectorSize));

                        // Reset our partition state
                        cnt.set(0);
                        vectorList.forEach(FieldVector::clear);
                        writerMap.values().forEach(writer -> {
                            try {
                                writer.close();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
                        writerMap.clear();
                    }
                } catch (Exception e) {
                    if (errored.compareAndSet(false, true)) {
                        logger.error(e.getMessage(), e);
                        job.cancel(true);
                        listener.error(CallStatus.UNKNOWN.withDescription(e.getMessage()).toRuntimeException());
                    }
                } finally {
                    partitionedSemaphores[partition].release();
                }
            });

            // This should block until all data from the Job is prepared for the stream
            job.get();

            // Final flush of stragglers...
            for (int i=0; i<maxPartitions; i++) {
                final int partition = i;

                final List<FieldVector> vectorList = partitionedVectorList[partition];
                final Map<String, BaseWriter.ListWriter> writerMap = partitionedWriters[partition];
                final int vectorSize = partitionedCounts[partition].get();

                if (vectorSize > 0) {
                    final ArrayList<ValueVector> copy = new ArrayList<>();
                    try {
                        transferMutex.acquire();
                        for (FieldVector vector : partitionedVectorList[partition]) {
                            final TransferPair tp = vector.getTransferPair(transmitAllocator);
                            tp.transfer();
                            copy.add(tp.getTo());
                        }
                    } finally {
                        transferMutex.release();
                    }
                    workQueue.add(FlushWork.from(copy, vectorSize));
                }
                vectorList.forEach(FieldVector::close);
                writerMap.values().forEach(writer -> {
                    try {
                        writer.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            if (!isFeeding.compareAndSet(true, false)) {
                logger.error("invalid state: expected isFeeding == true");
                listener.error(CallStatus.INTERNAL.withDescription("unexpected state").toRuntimeException());
                return;
            }
            logger.info("waiting up to 5 mins for flushing to finish...");
            flushJob.get(5, TimeUnit.MINUTES);
            logger.info("flushing complete");

            // Close the allocators for each partition
            for (BufferAllocator allocator : bufferAllocators)
                allocator.close();

        } catch (Exception e) {
            logger.error("ruh row", e);
            listener.error(CallStatus.INTERNAL.withCause(e).withDescription(e.getMessage()).toRuntimeException());
        } finally {
            logger.info("finishing getStream for ticket {}", ticket);
            flightMap.remove(ticket);
            listener.completed();
        }
    }

    /**
     * Flush out our vectors into the stream. At this point, all data is Arrow-based.
     * <p>
     *     This part is tricky and requires turning Arrow vectors into Arrow Flight messages based
     *     on the concept of {@link ArrowFieldNode}s and {@link ArrowBuf}s. Not as simple as "here's
     *     my vectors!"
     * </p>
     * @param listener reference to the {@link ServerStreamListener}
     * @param loader reference to the {@link VectorLoader}
     * @param vectors
     * @param dimension dimension of the vectors
     */
    private void flush(ServerStreamListener listener, VectorLoader loader, List<ValueVector> vectors, int dimension) {
        final List<ArrowFieldNode> nodes = new ArrayList<>();
        final List<ArrowBuf> buffers = new ArrayList<>();

        try {
            for (ValueVector vector : vectors) {
                logger.debug("flushing vector {}", vector.getName());
                vector.setValueCount(dimension);
                nodes.add(new ArrowFieldNode(dimension, 0));

                if (vector instanceof BaseListVector) {
                    // Variable-width ListVectors have some special crap we need to deal with
                    if (vector instanceof ListVector) {
                        ((ListVector) vector).setLastSet(dimension);
                        buffers.add(vector.getValidityBuffer());
                        buffers.add(vector.getOffsetBuffer());
                    } else {
                        buffers.add(vector.getValidityBuffer());
                    }

                    for (FieldVector child : ((BaseListVector)vector).getChildrenFromFields()) {
                        logger.debug("batching child vector {} ({}, {})", child.getName(), child.getValueCount(), child.getNullCount());
                        nodes.add(new ArrowFieldNode(child.getValueCount(), child.getNullCount()));
                        buffers.addAll(List.of(child.getBuffers(false)));
                    }

                } else {
                    for (ArrowBuf buf : vector.getBuffers(false)) {
                        logger.debug("adding buf {} for vector {}", buf, vector.getName());
                        buffers.add(buf);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("error preparing batch", e);
        }

        // The actual data transmission...
        try (ArrowRecordBatch batch = new ArrowRecordBatch(dimension, nodes, buffers,
                CompressionUtil.createBodyCompression(new Lz4CompressionCodec()))) {
            loader.load(batch);
            listener.putNext();
        } catch (Exception e) {
            // logger.error(e.getMessage(), e);
            listener.error(CallStatus.UNKNOWN.withDescription("Unknown error during batching").toRuntimeException());
        }

        // We need to close our reference to the ValueVector to decrement the ref count in the underlying buffers.
        vectors.forEach(ValueVector::close);
    }

    /**
     * Ticket a Job and add it to the jobMap.
     * @param job instance of a Job
     * @return new Ticket
     */
    public Ticket ticketJob(Job job) {
        final Ticket ticket = new Ticket(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        jobMap.put(ticket, job);
        return ticket;
    }

    public Job getJob(Ticket ticket) {
        return jobMap.get(ticket);
    }

    public void setFlightInfo(Ticket ticket, Schema schema) {
        final Job job = getJob(ticket);
        if (job == null)
            throw CallStatus.INTERNAL.withDescription("no job for flight???").toRuntimeException();
        assert(job.getStatus() == Job.Status.PENDING || job.getStatus() == Job.Status.INITIALIZING);

        final FlightInfo info = new FlightInfo(schema, FlightDescriptor.command(ticket.getBytes()),
                List.of(new FlightEndpoint(ticket, location)), -1, -1);
        flightMap.put(ticket, info);

        // We need to flip the job status only after the flight map is updated otherwise we could race
        job.setStatus(Job.Status.PRODUCING);
        logger.info("set flight info {}", info);
    }

    public void deleteFlight(Ticket ticket) {
        // XXX just nuke map values for now
        logger.info("deleting flight for ticket {}", ticket);
        flightMap.remove(ticket);
        jobMap.remove(ticket);
    }

    public void registerHandler(ActionHandler handler) {
        handler.actionTypes().forEach(action -> handlerMap.put(action, handler));
    }

    @Override
    public void listFlights(CallContext context, Criteria criteria, StreamListener<FlightInfo> listener) {
        logger.debug("listFlights called: context={}, criteria={}", context, criteria);
        flightMap.values().forEach(listener::onNext);
        listener.onCompleted();
    }

    @Override
    public FlightInfo getFlightInfo(CallContext context, FlightDescriptor descriptor) {
        logger.debug("getFlightInfo called: context={}, descriptor={}", context, descriptor);

        // We assume for now that our "commands" are just a serialized ticket
        try {
            Ticket ticket = Ticket.deserialize(ByteBuffer.wrap(descriptor.getCommand()));
            FlightInfo info = flightMap.get(ticket);

            if (info == null) {
                logger.info("no flight found for ticket {}", ticket);
                throw CallStatus.NOT_FOUND.withDescription("no flight found").toRuntimeException();
            }
            return info;
        } catch (IOException e) {
            logger.error("failed to get flight info", e);
            throw CallStatus.INVALID_ARGUMENT.withDescription("failed to interpret ticket").toRuntimeException();
        }
    }

    @Override
    public Runnable acceptPut(CallContext context, FlightStream flightStream, StreamListener<PutResult> ackStream) {
        logger.debug("acceptPut called");
        return ackStream::onCompleted;
    }

    @Override
    public void doAction(CallContext context, Action action, StreamListener<Result> listener) {
        logger.info("doAction called: action.type={}, peer={}", action.getType(), context.peerIdentity());

        ActionHandler handler = handlerMap.get(action.getType());
        if (handler == null) {
            final Exception e = CallStatus.NOT_FOUND.withDescription("unsupported action").toRuntimeException();
            logger.error(String.format("no handler for action type %s", action.getType()), e);
            listener.onError(e);
            return;
        }

        try {
            final Outcome outcome = handler.handle(context, action, this);
            if (outcome.isSuccessful()) {
                listener.onNext(outcome.result.get());
                listener.onCompleted();
            } else {
                final CallStatus callStatus = outcome.callStatus.get();
                logger.error(callStatus.description(), callStatus.toRuntimeException());
                listener.onError(callStatus.toRuntimeException());
            }
        } catch (Exception e) {
            logger.error(String.format("unexpected exception: %s", e.getMessage()), e);
            listener.onError(CallStatus.INTERNAL.withDescription("internal error").toRuntimeException());
        }
    }

    @Override
    public void listActions(CallContext context, StreamListener<ActionType> listener) {
        logger.debug("listActions called: context={}", context);
        handlerMap.values().stream()
                .distinct()
                .flatMap(handler -> handler.actionDescriptions().stream())
                .forEach(listener::onNext);
        listener.onCompleted();
    }

    @Override
    public void close() throws Exception {
        logger.debug("closing");
        for (Job job : jobMap.values()) {
            job.close();
        }
        AutoCloseables.close(allocator);
    }
}
