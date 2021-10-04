package org.neo4j.arrow.job;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.types.pojo.Schema;
import org.neo4j.arrow.Config;
import org.neo4j.arrow.action.GdsMessage;
import org.neo4j.arrow.action.GdsWriteNodeMessage;
import org.neo4j.arrow.action.Message;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.gds.NodeLabel;
import org.neo4j.gds.Orientation;
import org.neo4j.gds.api.*;
import org.neo4j.gds.config.GraphCreateConfig;
import org.neo4j.gds.core.huge.HugeGraph;
import org.neo4j.gds.core.loading.CSRGraphStoreUtil;
import org.neo4j.gds.core.loading.GraphStoreCatalog;
import org.neo4j.gds.core.loading.construction.GraphFactory;
import org.neo4j.gds.core.loading.construction.NodesBuilder;
import org.neo4j.gds.core.loading.construction.NodesBuilderBuilder;
import org.neo4j.gds.core.utils.mem.AllocationTracker;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


public class GdsWriteJob extends WriteJob {
    private final CompletableFuture<Boolean> future;

    private final DatabaseManagementService dbms;

    /**
     * Create a new GdsWriteJob for processing the given {@link GdsMessage}, creating or writing to
     * an in-memory GDS graph.
     * <p>
     * The supplied username is assumed to allow GDS to enforce authorization and is assumed to be
     * previously authenticated.
     *
     * @param msg      the {@link GdsMessage} to process in the job
     * @param username an already authenticated username
     * @param dbms reference to a {@link DatabaseManagementService}
     */
    public GdsWriteJob(GdsWriteNodeMessage msg, // XXX need to abstract here?
                       String username, DatabaseManagementService dbms) throws RuntimeException {
        super();
        this.dbms = dbms;

        final CompletableFuture<Boolean> job;
        logger.info("GdsWriteJob called with msg: {}", msg);

        job = handleNodeJob(msg, username);

        /* XXX later
        switch (msg.getRequestType()) {
            case node:
                job = handleNodeJob(msg, username);
                break;
            case relationship:
                job = handleRelationshipsJob(msg);
                break;
            default:
                throw CallStatus.UNIMPLEMENTED.withDescription("unhandled request type").toRuntimeException();
         }
         */

        future = job.exceptionally(throwable -> {
            logger.error(throwable.getMessage(), throwable);
            return false;
        }).handleAsync((aBoolean, throwable) -> {
            logger.info("GdsWriteJob completed! result: {}", (aBoolean == null ? "failed" : "ok!"));
            if (throwable != null)
                logger.error(throwable.getMessage(), throwable);
            return false;
        });
    }

    protected CompletableFuture<Boolean> handleNodeJob(GdsWriteNodeMessage msg, String username) {
        final GraphDatabaseAPI api = (GraphDatabaseAPI) dbms.database(msg.getDbName());
        final NamedDatabaseId dbId = api.databaseId();

        logger.info("configuring job for {}", msg);

        return CompletableFuture.supplyAsync(() -> {
            // XXX we assume we're creating a graph (for now), not updating
            final VectorSchemaRoot root;
            try {
                root = getStreamCompletion().get(1, TimeUnit.HOURS);    // XXX
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                e.printStackTrace();
                return false;
            }

            // XXX push schema validation to Producer side prior to full stream being formed
            final Schema schema = root.getSchema();
            final long rowCount = root.getRowCount();

            // XXX this assumes we only load up to ((1 << 31) - 1) (~2.1B) node ids
            // these will throw IllegalArg exceptions
            schema.findField(msg.getIdField());
            BigIntVector nodeIdVector = (BigIntVector) root.getVector(msg.getIdField());

            schema.findField(msg.getLabelsField());
            ListVector labelsVector = (ListVector) root.getVector(msg.getLabelsField());

            final NodesBuilder builder = (new NodesBuilderBuilder())
                    .concurrency(Config.arrowMaxPartitions)
                    .hasLabelInformation(true)
                    .hasProperties(true)
                    .allocationTracker(AllocationTracker.empty())
                    .maxOriginalId(Integer.MAX_VALUE)
                    .nodeCount(rowCount)
                    .build();

            assert(root.getRowCount() == nodeIdVector.getValueCount());
            IntStream.range(0, nodeIdVector.getValueCount())
                    .parallel()
                    .forEach(idx -> {
                        final String[] labels = labelsVector.getObject(idx).stream()
                                .map(Object::toString).collect(Collectors.toList()).toArray(String[]::new);
                        final NodeLabel[] nodeLabels = NodeLabel.listOf(labels).toArray(NodeLabel[]::new);
                        builder.addNode(nodeIdVector.get(idx), nodeLabels);
                    });

            final HugeGraph hugeGraph = GraphFactory.create(builder.build().nodeMapping(),
                    Relationships.of(0, Orientation.NATURAL, false, new AdjacencyList() {
                        // See GraphStoreFilterTest.java for inspiration of how to stub out
                        @Override
                        public int degree(long node) {
                            return 0;
                        }

                        @Override
                        public AdjacencyCursor adjacencyCursor(long node, double fallbackValue) {
                            return AdjacencyCursor.EmptyAdjacencyCursor.INSTANCE;
                        }

                        @Override
                        public AdjacencyCursor rawAdjacencyCursor() {
                            return AdjacencyCursor.EmptyAdjacencyCursor.INSTANCE;
                        }

                        @Override
                        public void close() {
                            // XXX hack
                            root.close();
                        }
                    }), AllocationTracker.empty());

            final GraphStore store = CSRGraphStoreUtil.createFromGraph(
                    dbId, hugeGraph, "REL", Optional.empty(),
                    Config.arrowMaxPartitions, AllocationTracker.create());

            // Try wiring in our arbitrary node properties.
            root.getFieldVectors().stream()
                    .filter(vec -> !vec.getName().equals(msg.getLabelsField())
                            && !vec.getName().equals(msg.getIdField()))
                    .map(ArrowNodeProperties::new)
                    .forEach(nodeProps -> store.addNodeProperty(NodeLabel.ALL_NODES, // XXX hack for now
                            nodeProps.getName(), nodeProps));

            final GraphCreateConfig config = new GraphCreateConfig() {
                @Override
                public String graphName() {
                    return msg.getGraphName();
                }

                @Override
                public GraphStoreFactory.Supplier graphStoreFactory() {
                    throw new RuntimeException("oops: graphStoreFactory() called");
                }

                @Override
                public <R> R accept(Cases<R> visitor) {
                    // TODO: what the heck is this Cases<R> stuff?!
                    return null;
                }

                @Override
                public int readConcurrency() {
                    return GraphCreateConfig.super.readConcurrency();
                }

                @Override
                public long nodeCount() {
                    return rowCount;
                }

                @Override
                public long relationshipCount() {
                    return 0;
                }

                @Override
                public boolean validateRelationships() {
                    return false;
                }

                @Override
                public String username() {
                    return username;
                }
            };

            GraphStoreCatalog.set(config, store);

            return true;
        });
    }

    protected CompletableFuture<Boolean> handleRelationshipsJob(Message unused) {
        return null;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return future.cancel(mayInterruptIfRunning);
    }

    @Override
    public void close() {
        future.cancel(true);
    }

    @Override
    public void onError(Exception e) {
        logger.info("failure", e);
        future.completeExceptionally(e);
    }

}
