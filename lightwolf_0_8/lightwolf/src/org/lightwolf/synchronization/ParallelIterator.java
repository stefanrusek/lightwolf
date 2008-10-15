package org.lightwolf.synchronization;

import java.util.Iterator;

import org.lightwolf.FlowMethod;

public interface ParallelIterator<T> extends Iterator<T> {

    @FlowMethod
    boolean hasNext();

    @FlowMethod
    T next();

}
