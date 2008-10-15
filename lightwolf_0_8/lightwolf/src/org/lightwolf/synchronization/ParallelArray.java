package org.lightwolf.synchronization;

import java.util.NoSuchElementException;

import org.lightwolf.FlowLocal;
import org.lightwolf.FlowMethod;

public final class ParallelArray<T> implements ParallelIterable<T> {

    private final T[] data;
    private int threadCount;

    public ParallelArray(T[] data) {
        this(data, 4);
    }

    public ParallelArray(T[] data, int threadCount) {
        this.data = data;
        setThreadCount(threadCount);
    }

    public void setThreadCount(int threadCount) {
        if (threadCount < 1) {
            throw new IllegalArgumentException("Thread count must be a positive number.");
        }
        this.threadCount = threadCount;
    }

    public ParallelIterator<T> iterator() {
        return new ParallelArrayIterator<T>(threadCount, data);
    }

}

class ParallelArrayIterator<T> implements ParallelIterator<T> {

    private static final String GET_NEXT = "getnext";

    private final int threadCount;
    private final T[] data;
    private final FlowLocal<Object> next;

    public ParallelArrayIterator(int threadCount, T[] data) {
        this.threadCount = threadCount;
        this.data = data;
        this.next = new FlowLocal<Object>();
    }

    @FlowMethod
    public boolean hasNext() {
        return prepareNext();
    }

    @FlowMethod
    public T next() {
        if (!prepareNext()) {
            throw new NoSuchElementException();
        }
        return (T) next.set(GET_NEXT);
    }

    public void remove() {
        throw new UnsupportedOperationException();
    }

    @FlowMethod
    private boolean prepareNext() {
        throw new AssertionError();
    }

}
