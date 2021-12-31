package org.neo4j.arrow.batchimport;

import org.neo4j.internal.batchimport.InputIterable;
import org.neo4j.internal.batchimport.InputIterator;

public class RelationshipInputIterable implements InputIterable {
    @Override
    public InputIterator iterator() {
        return new RelationshipInputIterator();
    }
}