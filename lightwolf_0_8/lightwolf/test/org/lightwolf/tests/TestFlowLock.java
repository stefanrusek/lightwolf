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
package org.lightwolf.tests;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.FlowSignal;
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
        } catch (FlowSignal s) {
            s.defaultAction();
            s.getFlow().join();
        }
    }

    @FlowMethod
    private void timeout() throws Throwable {
        ThreadFreeLock lock = new ThreadFreeLock();
        lock.lock();
        SynchronousQueue<String> queue = new SynchronousQueue<String>();
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
