package org.lightwolf.tests;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;
import org.lightwolf.DelayedCallSignal;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.synchronization.ThreadFreeLock;

public class TestFlowLock {

    @Test
    @FlowMethod
    public void lockOnParentAndChildFlows() {
        ThreadFreeLock lock = new ThreadFreeLock();
        lock.lock();
        int i = callDoLock(lock);
        Assert.assertEquals(5, i);
    }

    private int callDoLock(ThreadFreeLock lock) {
        return doLock(lock);
    }

    @FlowMethod
    private int doLock(ThreadFreeLock lock) {
        lock.lock();
        return 5;
    }

    @Test
    public void testTimeout() throws Throwable {
        try {
            timeout();
        } catch (DelayedCallSignal s) {
            s.schedule();
            s.getFlow().join();
        }
    }

    @FlowMethod
    private void timeout() throws Throwable {
        ThreadFreeLock lock = new ThreadFreeLock();
        lock.lock();
        SynchronousQueue<String> queue = new SynchronousQueue();
        doTimeout(lock, queue);
        String result;
        result = queue.take();
        Assert.assertEquals("timeout", result);
        lock.unlock();
        result = queue.take();
        Assert.assertEquals("locked", result);
        boolean locked = lock.tryLock(500, TimeUnit.MILLISECONDS);
        Assert.assertEquals(false, locked);
        queue.put("unlock");
        result = queue.take();
        Assert.assertEquals("unlocked", result);
        locked = lock.tryLock(500, TimeUnit.MILLISECONDS);
        Assert.assertEquals(true, locked);
    }

    @FlowMethod
    private void doTimeout(ThreadFreeLock lock, SynchronousQueue<String> queue) throws Throwable {
        Flow.returnAndContinue();
        boolean locked;
        locked = lock.tryLock(500, TimeUnit.MILLISECONDS);
        if (locked) {
            queue.put("locked");
        } else {
            queue.put("timeout");
        }
        locked = lock.tryLock(500, TimeUnit.MILLISECONDS);
        if (locked) {
            queue.put("locked");
        } else {
            queue.put("timeout");
        }
        queue.take();
        lock.unlock();
        queue.put("unlocked");
    }

}
