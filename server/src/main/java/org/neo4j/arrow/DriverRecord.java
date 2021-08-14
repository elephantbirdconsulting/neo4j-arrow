package org.neo4j.arrow;

import org.neo4j.driver.Record;
import org.neo4j.driver.internal.types.InternalTypeSystem;

import java.util.List;

public class DriverRecord implements RowBasedRecord {
    private final Record record;

    private DriverRecord(Record record) {
        this.record = record;
    }

    public static RowBasedRecord wrap(Record record) {
        return new DriverRecord(record);
    }

    private static RowBasedRecord.Value wrapValue(org.neo4j.driver.Value value) {
        return new Value() {
            @Override
            public int size() {
                if (value.hasType(InternalTypeSystem.TYPE_SYSTEM.LIST()))
                    return value.asList().size();
                return 1;
            }

            @Override
            public int asInt() {
                return value.asInt();
            }

            @Override
            public long asLong() {
                return value.asLong();
            }

            @Override
            public float asFloat() {
                return value.asFloat();
            }

            @Override
            public double asDouble() {
                return value.asDouble();
            }

            @Override
            public String asString() {
                return value.asString();
            }

            @Override
            public List<Object> asList() {
                return value.asList(val -> val.asObject());
            }

            @Override
            public List<Integer> asIntList() {
                return value.asList(val -> val.asInt());
            }

            @Override
            public List<Long> asLongList() {
                return value.asList(val -> val.asLong());
            }

            @Override
            public List<Float> asFloatList() {
                return value.asList(val -> val.asFloat());
            }

            @Override
            public List<Double> asDoubleList() {
                return value.asList(val -> val.asDouble());
            }

            @Override
            public double[] asDoubleArray() {
                return null;
            }

            public Type type() {
                switch (value.type().name()) {
                    case "INTEGER":
                        return Type.INT;
                    case "LONG":
                        return Type.LONG;
                    case "FLOAT":
                    case "DOUBLE":
                        return Type.DOUBLE;
                    case "STRING":
                        return Type.STRING;
                    case "LIST OF ANY?":
                        return Type.LIST;
                }
                // Catch-all
                return Type.OBJECT;
            }
        };
    }

    @Override
    public Value get(int index) {
        return wrapValue(record.get(index));
    }

    @Override
    public Value get(String field) {
        return wrapValue(record.get(field));
    }

    @Override
    public List<String> keys() {
        return record.keys();
    }
}
