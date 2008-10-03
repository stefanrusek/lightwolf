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
