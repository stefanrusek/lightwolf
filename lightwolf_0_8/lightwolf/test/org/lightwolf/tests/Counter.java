/**
 *
 */
package org.lightwolf.tests;

import java.io.Serializable;
import java.util.HashMap;
import java.util.IdentityHashMap;

import org.junit.Assert;
import org.lightwolf.Flow;
import org.lightwolf.MethodFrame;

public class Counter implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final HashMap<Integer, Counter> savedCounters = new HashMap<Integer, Counter>();

    static Counter restore(int id) {
        return savedCounters.get(id);
    }

    private int counter;
    private final IdentityHashMap<Thread, ?> threads = new IdentityHashMap<Thread, Object>();
    private final IdentityHashMap<Flow, ?> flowThreads = new IdentityHashMap<Flow, Object>();

    public synchronized void count() {
        ++counter;
        threads.put(Thread.currentThread(), null);
        Flow thread = Flow.current();
        if (thread == null) {
            thread = MethodFrame.enter(null, null, null).getFlow();
        }
        flowThreads.put(thread, null);
    }

    public synchronized int getCount() {
        return counter;
    }

    public synchronized void assertEquals(int count, int threadCount) {
        assertEquals(count, threadCount, threadCount);
    }

    public synchronized void assertEquals(int count, int threadCount, int flowThreadCount) {
        Assert.assertEquals(count, counter);
        Assert.assertEquals(flowThreadCount, flowThreads.size());
        if (threads.size() > threadCount) {
            Assert.fail("Too much threads. Expected at most " + threadCount + ", but found " + threads.size());
        }
    }

    private Object writeReplace() {
        synchronized(savedCounters) {
            int id = savedCounters.size() + 1;
            savedCounters.put(id, this);
            return new SavedCounter(id);
        }
    }

}

class SavedCounter implements Serializable {

    private static final long serialVersionUID = 1L;
    private final int id;

    SavedCounter(int id) {
        this.id = id;
    }

    private Object readResolve() {
        return Counter.restore(id);
    }

}
