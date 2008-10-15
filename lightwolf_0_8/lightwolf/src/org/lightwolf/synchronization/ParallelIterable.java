package org.lightwolf.synchronization;

public interface ParallelIterable<T> {

    public ParallelIterator<T> iterator();

}
