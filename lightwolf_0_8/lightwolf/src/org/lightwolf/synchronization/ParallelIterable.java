package org.lightwolf.synchronization;

public interface ParallelIterable<T> extends Iterable<T> {

    public ParallelIterator<T> iterator();

}
