package org.lightwolf.tests;

import java.io.Serializable;
import java.util.concurrent.ArrayBlockingQueue;

import org.junit.Assert;
import org.junit.Test;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.SuspendSignal;

public class TestSerialization implements Serializable {

    private static final long serialVersionUID = 1L;
    private static ArrayBlockingQueue<Object> abq;

    @Test
    public void testSuspend() throws Throwable {
        abq = new ArrayBlockingQueue<Object>(1);
        Counter c = new Counter();
        c.count();
        SuspendSignal signal;
        try {
            callAndSuspend(c, "ABC");
            Assert.fail("Didn't threw suspend signal.");
            throw new AssertionError();
        } catch (SuspendSignal s) {
            signal = s;
        }
        c.count();
        Flow susp = signal.getFlow();
        Assert.assertTrue(susp.isSuspended());
        Assert.assertFalse(susp.isFinished());
        Assert.assertFalse(susp.isActive());

        susp = (Flow) Util.streamedCopy(susp);

        abq.put("MNO");
        String output = (String) susp.resume("XYZ");
        c.count();
        Assert.assertEquals("ABCXYZ", output);
        Assert.assertFalse(susp.isSuspended());
        Assert.assertTrue(susp.isFinished());

        Assert.assertFalse(susp.isActive());

        output = (String) abq.take();
        Assert.assertEquals("XYZMNO", output);

        c.assertEquals(5, 1, 3);

    }

    @FlowMethod
    private String callAndSuspend(Counter c, String s1) throws InterruptedException {
        c.count();
        String s2 = (String) Flow.suspend();
        c.count();
        String s3 = (String) abq.take();
        abq.put(s2 + s3);
        return s1 + s2;
    }

}
