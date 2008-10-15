package org.lightwolf.synchronization;

import org.lightwolf.FlowMethod;

public interface ParallelIterator<T> {

    @FlowMethod
    boolean hasNext();

    T next();

}
