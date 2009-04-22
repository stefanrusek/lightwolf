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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;
import org.lightwolf.DelayedCallSignal;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.FlowSignal;
import org.lightwolf.synchronization.EventPicker;

public class TestEventPicker {

    @Test
    public void simplest() throws Throwable {
        Counter c = new Counter();
        EventPicker picker = new EventPicker();
        ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<String>(1);
        Flow flow = null;
        try {
            callPicker(c, picker, queue);
            Assert.fail();
        } catch (FlowSignal s) {
            flow = s.getFlow();
        }
        Assert.assertTrue(flow.isSuspended());
        c.assertEquals(7, 1);
        picker.notify("B", "xis");
        flow.join();
        c.assertEquals(11, 2, 1);
        Assert.assertEquals("B12", queue.take());
    }

    @Test
    public void timeout() throws Throwable {
        Counter c = new Counter();
        EventPicker picker = new EventPicker();
        ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<String>(1);
        DelayedCallSignal signal = null;
        try {
            callPicker(c, picker, queue);
            Assert.fail();
        } catch (DelayedCallSignal s) {
            signal = s;
        }
        Flow flow = signal.getFlow();
        Assert.assertTrue(flow.isSuspended());
        c.assertEquals(7, 1);
        signal.schedule();
        flow.join();
        c.assertEquals(11, 2, 1);
        Assert.assertEquals("D12", queue.take());
    }

    @FlowMethod
    public void callPicker(Counter c, EventPicker picker, ArrayBlockingQueue<String> queue) throws Throwable {
        c.count();
        String s = pick(c, picker, 12);
        c.count();
        queue.put(s);
    }

    @FlowMethod
    private String pick(Counter c, EventPicker picker, int N) {
        try {
            c.count();
            if (picker.onEvent("A")) {
                c.count();
                return "A" + N;
            }
            c.count();
            if (picker.onEvent("B")) {
                c.count();
                return "B" + N;
            }
            c.count();
            if (picker.onEvent("C")) {
                c.count();
                return "C" + N;
            }
            c.count();
            if (picker.onTimeout(1, TimeUnit.SECONDS)) {
                c.count();
                return "D" + N;
            }
            c.count();
        } finally {
            c.count();
            picker.pick();
            c.count();
        }
        c.count();
        Assert.fail();
        throw new AssertionError();
    }

}
