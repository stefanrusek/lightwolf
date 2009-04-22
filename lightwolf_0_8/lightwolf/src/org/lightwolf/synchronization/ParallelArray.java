/*
 * Copyright (c) 2007, Fernando Colombo. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1) Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * 
 * 2) Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED ''AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.lightwolf.synchronization;

import java.util.NoSuchElementException;

import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;

public final class ParallelArray<T> {

    private final T[] data;
    private int threadCount;

    public ParallelArray(T[] data) {
        this.data = data;
        threadCount = 4;
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

    private static class ParallelArrayIterator<T> implements ParallelIterator<T> {

        private final int threadCount;
        private final T[] data;
        private final ThreadLocal<ThreadState> state;
        private int current;

        public ParallelArrayIterator(int threadCount, T[] data) {
            this.threadCount = threadCount;
            this.data = data;
            this.state = new ThreadLocal<ThreadState>();
        }

        @FlowMethod
        public boolean hasNext() {
            ThreadState ts = state.get();
            if (ts == null) {
                Flow.fork(threadCount - 1);
                ts = new ThreadState();
                state.set(ts);
            }
            return ts.hasNext();
        }

        public T next() {
            ThreadState ts = state.get();
            if (ts == null) {
                throw mustCallHasNext();
            }
            return ts.next();
        }

        private static IllegalStateException mustCallHasNext() {
            return new IllegalStateException("This iterator requires a call to hasNext() before next().");
        }

        private class ThreadState {

            private int next;

            ThreadState() {
                next = -1;
            }

            @FlowMethod
            boolean hasNext() {
                switch (next) {
                    case -1:
                        synchronized(ParallelArrayIterator.this) {
                            if (current < data.length) {
                                next = current;
                                current++;
                                return true;
                            }
                        }
                        next = -2;
                        // Yes, fall through.
                    case -2:
                        try {
                            Flow.merge();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        return false;
                    default:
                        return true;
                }
            }

            T next() {
                switch (next) {
                    case -1:
                        throw mustCallHasNext();
                    case -2:
                        throw new NoSuchElementException();
                    default:
                        T ret = data[next];
                        next = -1;
                        return ret;
                }
            }
        }

    }

}
