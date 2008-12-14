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
package org.lightwolf.tools;

import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.lightwolf.Flow;
import org.lightwolf.FlowManager;
import org.lightwolf.FlowSignal;

public class SimpleFlowManager extends FlowManager implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final WeakHashMap<Key, SimpleFlowManager> activeManagers = new WeakHashMap<Key, SimpleFlowManager>();

    private static SimpleFlowManager restore(Key key) {
        return activeManagers.get(key);
    }

    private final Key serialKey;
    private final ThreadPoolExecutor executor;

    public SimpleFlowManager(String name) {
        serialKey = new Key(name);
        synchronized(activeManagers) {
            activeManagers.put(serialKey, this);
        }
        executor = new ThreadPoolExecutor(8, Integer.MAX_VALUE, 0, TimeUnit.NANOSECONDS, new SynchronousQueue<Runnable>(), new SimpleThreadFactory(name));
        Runtime.getRuntime().addShutdownHook(new ShutdownManager());
    }

    public int getActiveCount() {
        return executor.getActiveCount();
    }

    @Override
    protected ScheduledFuture<?> schedule(Callable<?> callable, long delay, TimeUnit unit) {
        ScheduledFutureRunner<?> ret = new ScheduledFutureRunner<Object>((Callable<Object>) callable, delay, unit);
        executor.execute(ret);
        return ret;
    }

    @Override
    protected void fork(Flow requester, int n) {
        if (n < 0) {
            throw new IllegalArgumentException("Number of forks must be non-negative.");
        }
        prepareFork(requester, n);
        if (n == 0) {
            return;
        }
        for (int i = 1; i <= n; ++i) {
            Flow branch = createBranch(requester, i);
            submit(branch, null);
        }
    }

    @Override
    protected void doStreamedFork(Flow requester, int n) {
        throw new AssertionError("Pending...");
    }

    @Override
    protected Future<?> submit(final Flow flow, final Object message) {
        Runnable command = new Runnable() {

            public void run() {
                try {
                    clearThread();
                    Flow.log("Resuming " + flow + ", message=" + message);
                    try {
                        flow.resume(message);
                    } catch (FlowSignal s) {
                        if (s.getFlow() != flow) {
                            throw s;
                        }
                        s.defaultAction();
                        return;
                    }
                    assert flow.isEnded();
                } catch (Throwable e) {
                    notifyException(e);
                }
            }
        };
        Flow.log("Scheduling " + flow + ", message=" + message);
        return executor.submit(command);
    }

    private void notifyException(Throwable e) {
        Flow.log("Threw exception: " + e.getMessage());
        LightWolfLog.printTrace(e);
    }

    private Object writeReplace() {
        return serialKey;
    }

    private static final class Key implements Serializable {

        private static final long serialVersionUID = 1L;
        private final String id;

        Key(String id) {
            this.id = id.intern();
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Key)) {
                return false;
            }
            return ((Key) obj).id.equals(id);
        }

        private Object readResolve() throws ObjectStreamException {
            SimpleFlowManager ret = restore(this);
            if (ret == null) {
                throw new InvalidObjectException("Coult not find " + SimpleFlowManager.class.getName() + " instance named '" + id + "'.");
            }
            return ret;
        }

    }

    private static class SimpleThreadFactory implements ThreadFactory {

        private final ThreadGroup group;
        private final String namePrefix;
        private int nextNumber;

        SimpleThreadFactory(String ownerName) {
            SecurityManager s = System.getSecurityManager();
            group = s != null ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
            namePrefix = ownerName + "-";
        }

        public Thread newThread(Runnable r) {
            Flow.log("newThread-init");
            int index;
            synchronized(this) {
                index = nextNumber++;
            }
            Thread ret = new Thread(group, r, namePrefix + index);
            if (!ret.isDaemon()) {
                ret.setDaemon(true);
            }
            if (ret.getPriority() != Thread.NORM_PRIORITY) {
                ret.setPriority(Thread.NORM_PRIORITY);
            }
            Flow.log("newThread-initdone");
            return ret;
        }

    }

    private class ShutdownManager extends Thread {

        @Override
        public void run() {
            executor.shutdown();
            try {
                executor.awaitTermination(4 * 60 * 60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }

    private static class ScheduledFutureRunner<V> implements Runnable, ScheduledFuture<V> {

        private static final byte WAITING = 1;
        private static final byte RUNNING = 2;
        private static final byte DONE = 3;
        private static final byte CANCELLED = 4;
        private static final AtomicInteger idGenerator = new AtomicInteger();
        private final long time;
        private final int id;
        private byte state;
        private final Callable<V> callable;
        private Thread thread;
        private V result;
        private Exception exception;

        ScheduledFutureRunner(Callable<V> callable, long delay, TimeUnit unit) {
            time = System.currentTimeMillis() + unit.convert(delay, TimeUnit.MILLISECONDS);
            id = idGenerator.getAndIncrement();
            state = WAITING;
            this.callable = callable;
        }

        public long getDelay(TimeUnit unit) {
            return unit.convert(time - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        public int compareTo(Delayed o) {
            if (o == this) {
                return 0;
            }
            ScheduledFutureRunner<V> of = (ScheduledFutureRunner<V>) o;
            if (time > of.time) {
                return 1;
            } else if (time < of.time) {
                return -1;
            }
            if (id > of.id) {
                return 1;
            } else if (id < of.id) {
                return -1;
            }
            throw new AssertionError("Id generator overflow.");
        }

        public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            if (state != WAITING) {
                if (state == DONE || state == CANCELLED) {
                    return false;
                }
                assert state == RUNNING;
                if (mayInterruptIfRunning && thread.isAlive()) {
                    thread.interrupt();
                }
            }
            state = CANCELLED;
            notifyAll();
            return true;
        }

        public synchronized V get() throws InterruptedException, ExecutionException {
            while (state == WAITING || state == RUNNING) {
                wait();
            }
            if (state == CANCELLED) {
                throw new CancellationException();
            }
            assert state == DONE;
            if (exception != null) {
                throw new ExecutionException(exception);
            }
            return result;
        }

        public synchronized V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            long limit = System.currentTimeMillis() + unit.convert(timeout, TimeUnit.MILLISECONDS);
            while (state == WAITING || state == RUNNING) {
                long delay = limit - System.currentTimeMillis();
                if (delay <= 0) {
                    throw new TimeoutException();
                }
                wait(delay);
            }
            if (state == CANCELLED) {
                throw new CancellationException();
            }
            assert state == DONE;
            if (exception != null) {
                throw new ExecutionException(exception);
            }
            return result;
        }

        public boolean isCancelled() {
            return state == CANCELLED;
        }

        public boolean isDone() {
            return state == DONE;
        }

        public void run() {
            synchronized(this) {
                for (;;) {
                    if (state != WAITING) {
                        return;
                    }
                    long delay = time - System.currentTimeMillis();
                    if (delay <= 0) {
                        break;
                    }
                    try {
                        wait(delay);
                    } catch (InterruptedException e) {
                        // Ignores.
                    }
                }
                state = RUNNING;
                thread = Thread.currentThread();
            }
            try {
                result = callable.call();
            } catch (Exception e) {
                exception = e;
            }
            synchronized(this) {
                if (state == RUNNING) {
                    state = DONE;
                } else {
                    result = null;
                    exception = null;
                }
                thread = null;
            }
        }

    }

}
