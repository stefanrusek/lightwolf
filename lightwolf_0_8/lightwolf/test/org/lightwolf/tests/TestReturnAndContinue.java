package org.lightwolf.tests;

import java.util.concurrent.ArrayBlockingQueue;

import org.junit.Assert;
import org.junit.Test;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.IllegalReturnValueException;
import org.lightwolf.ResumeException;
import org.lightwolf.SuspendSignal;
import org.lightwolf.tools.SimpleFlowManager;

public class TestReturnAndContinue {

    @Test
    @FlowMethod
    public void testBoolean() throws Throwable {
        ArrayBlockingQueue<Object> abq = new ArrayBlockingQueue<Object>(1);
        Counter c = new Counter();
        c.count();
        boolean output = callBoolean(abq, c, false);
        c.count();
        Assert.assertEquals(true, output);
        output = (Boolean) putAndGet(abq, false);
        Assert.assertEquals(true, output);
        c.assertEquals(4, 2);
    }

    @FlowMethod
    private boolean callBoolean(ArrayBlockingQueue<Object> abq, Counter c, boolean input) throws InterruptedException {
        c.count();
        Flow.returnAndContinue(!input);
        c.count();
        input = (Boolean) abq.take();
        abq.put(!input);
        return false;
    }

    @Test
    @FlowMethod
    public void testChar() throws Throwable {
        ArrayBlockingQueue<Object> abq = new ArrayBlockingQueue<Object>(1);
        Counter c = new Counter();
        c.count();
        char input = 12;
        char output = callChar(abq, c, input);
        c.count();
        Assert.assertEquals(input + 1, (int) output);
        input += 2;
        output = (Character) putAndGet(abq, input);
        Assert.assertEquals(input + 1, (int) output);
        c.assertEquals(4, 2);
    }

    @FlowMethod
    private char callChar(ArrayBlockingQueue<Object> abq, Counter c, char input) throws InterruptedException {
        ++input;
        c.count();
        Flow.returnAndContinue(input);
        c.count();
        input = (Character) abq.take();
        ++input;
        abq.put(input);
        return 0;
    }

    @Test
    @FlowMethod
    public void testByte() throws Throwable {
        ArrayBlockingQueue<Object> abq = new ArrayBlockingQueue<Object>(1);
        Counter c = new Counter();
        c.count();
        byte input = 12;
        byte output = callByte(abq, c, input);
        c.count();
        Assert.assertEquals(input + 1, output);
        input += 2;
        output = (Byte) putAndGet(abq, input);
        Assert.assertEquals(input + 1, output);
        c.assertEquals(4, 2);
    }

    @FlowMethod
    private byte callByte(ArrayBlockingQueue<Object> abq, Counter c, byte input) throws InterruptedException {
        ++input;
        c.count();
        Flow.returnAndContinue(input);
        c.count();
        input = (Byte) abq.take();
        ++input;
        abq.put(input);
        return 0;
    }

    @Test
    @FlowMethod
    public void testShort() throws Throwable {
        ArrayBlockingQueue<Object> abq = new ArrayBlockingQueue<Object>(1);
        Counter c = new Counter();
        c.count();
        short input = 12;
        short output = callShort(abq, c, input);
        c.count();
        Assert.assertEquals(input + 1, output);
        input += 2;
        output = (Short) putAndGet(abq, input);
        Assert.assertEquals(input + 1, output);
        c.assertEquals(4, 2);
    }

    @FlowMethod
    private short callShort(ArrayBlockingQueue<Object> abq, Counter c, short input) throws InterruptedException {
        ++input;
        c.count();
        Flow.returnAndContinue(input);
        c.count();
        input = (Short) abq.take();
        ++input;
        abq.put(input);
        return 0;
    }

    @Test
    @FlowMethod
    public void testInteger() throws Throwable {
        ArrayBlockingQueue<Object> abq = new ArrayBlockingQueue<Object>(1);
        Counter c = new Counter();
        c.count();
        int input = 12;
        int output = callInteger(abq, c, input);
        c.count();
        Assert.assertEquals(input + 1, output);
        input += 2;
        output = (Integer) putAndGet(abq, input);
        Assert.assertEquals(input + 1, output);
        c.assertEquals(4, 2);
    }

    @FlowMethod
    private int callInteger(ArrayBlockingQueue<Object> abq, Counter c, int input) throws InterruptedException {
        ++input;
        c.count();
        Flow.returnAndContinue(input);
        c.count();
        input = (Integer) abq.take();
        ++input;
        abq.put(input);
        return 0;
    }

    @Test
    @FlowMethod
    public void testLong() throws Throwable {
        ArrayBlockingQueue<Object> abq = new ArrayBlockingQueue<Object>(1);
        Counter c = new Counter();
        c.count();
        long input = 12;
        long output = callLong(abq, c, input);
        c.count();
        Assert.assertEquals(input + 1, output);
        input += 2;
        output = (Long) putAndGet(abq, input);
        Assert.assertEquals(input + 1, output);
        c.assertEquals(4, 2);
    }

    @FlowMethod
    private long callLong(ArrayBlockingQueue<Object> abq, Counter c, long input) throws InterruptedException {
        ++input;
        c.count();
        Flow.returnAndContinue(input);
        c.count();
        input = (Long) abq.take();
        ++input;
        abq.put(input);
        return 0;
    }

    @Test
    @FlowMethod
    public void testFloat() throws Throwable {
        ArrayBlockingQueue<Object> abq = new ArrayBlockingQueue<Object>(1);
        Counter c = new Counter();
        c.count();
        float input = 12;
        float output = callFloat(abq, c, input);
        c.count();
        Assert.assertEquals(input + 1, output);
        input += 2;
        output = (Float) putAndGet(abq, input);
        Assert.assertEquals(input + 1, output);
        c.assertEquals(4, 2);
    }

    @FlowMethod
    private float callFloat(ArrayBlockingQueue<Object> abq, Counter c, float input) throws InterruptedException {
        ++input;
        c.count();
        Flow.returnAndContinue(input);
        c.count();
        input = (Float) abq.take();
        ++input;
        abq.put(input);
        return 0;
    }

    @Test
    @FlowMethod
    public void testDouble() throws Throwable {
        ArrayBlockingQueue<Object> abq = new ArrayBlockingQueue<Object>(1);
        Counter c = new Counter();
        c.count();
        double input = 12;
        double output = callDouble(abq, c, input);
        c.count();
        Assert.assertEquals(input + 1, output);
        input += 2;
        output = (Double) putAndGet(abq, input);
        Assert.assertEquals(input + 1, output);
        c.assertEquals(4, 2);
    }

    @FlowMethod
    private double callDouble(ArrayBlockingQueue<Object> abq, Counter c, double input) throws InterruptedException {
        ++input;
        c.count();
        Flow.returnAndContinue(input);
        c.count();
        input = (Double) abq.take();
        ++input;
        abq.put(input);
        return 0;
    }

    @Test
    @FlowMethod
    public void testObject() throws Throwable {
        ArrayBlockingQueue<Object> abq = new ArrayBlockingQueue<Object>(1);
        Counter c = new Counter();
        c.count();
        String input = "ABC";
        String output = callObject(abq, c, input);
        c.count();
        Assert.assertEquals(input + "XYZ", output);
        input = "MNO";
        output = (String) putAndGet(abq, input);
        Assert.assertEquals(input + "RST", output);
        c.assertEquals(4, 2);
    }

    @FlowMethod
    private String callObject(ArrayBlockingQueue<Object> abq, Counter c, String input) throws InterruptedException {
        c.count();
        Flow.returnAndContinue(input + "XYZ");
        c.count();
        input = (String) abq.take();
        abq.put(input + "RST");
        return null;
    }

    @Test
    @FlowMethod
    public void testClassCast() throws Throwable {
        String output = callClassCast();
        if (output != "123") {
            Assert.fail("Wrong output: " + output);
        }
    }

    @FlowMethod
    private String callClassCast() {
        Integer notAtString = new Integer(123);
        try {
            Flow.returnAndContinue(notAtString);
            Assert.fail("Class cast wasn't thrown.");
        } catch (ClassCastException e) {
            // fine.
        }
        return "123";
    }

    @Test
    @FlowMethod
    public void testInvalidBooleanResult() throws Throwable {
        byte ret = returnBooleanOnByte();
        Assert.assertEquals(2, ret);
    }

    @FlowMethod
    private byte returnBooleanOnByte() {
        try {
            Flow.returnAndContinue(true);
            Assert.fail();
        } catch (IllegalReturnValueException e) {
            Assert.assertEquals("Attempt to return boolean from method 'byte TestReturnAndContinue.returnBooleanOnByte()'.", e.getMessage());
            return 2;
        }
        return 0;
    }

    @Test
    @FlowMethod
    public void testReturnInSynchronized() throws Throwable {
        if (true) {
            Assert.fail();
        }
        Object lock = new Object();
        try {
            returnInSynchronized(lock);
            Assert.fail("JVM is not enforcing structured locking.");
        } catch (IllegalMonitorStateException e) {
            // Ok.
        }
        Thread.sleep(200); // Allow the other thread to start.
        SimpleFlowManager sfm = (SimpleFlowManager) Flow.current().getManager();
        for (int i = 0; i < 30; ++i) {
            if (sfm.getActiveCount() == 0) {
                break;
            }
            Thread.sleep(100);
        }
        int ac = sfm.getActiveCount();
        if (ac != 0) {
            Assert.fail("There are " + ac + " active thread(s).");
        }
        synchronized(lock) {
            Thread.yield();
        }
    }

    @FlowMethod
    private void returnInSynchronized(Object lock) {
        synchronized(lock) {
            Flow.returnAndContinue();
        }
    }

    @Test
    public void testSuspend() throws Throwable {
        ArrayBlockingQueue<Object> abq = new ArrayBlockingQueue<Object>(1);
        Counter c = new Counter();
        c.count();
        SuspendSignal signal;
        try {
            callAndSuspend(abq, c, "ABC");
            Assert.fail("Didn't threw suspend signal.");
            throw new AssertionError();
        } catch (SuspendSignal s) {
            signal = s;
        }
        c.count();
        Flow susp = signal.getFlow();
        Assert.assertTrue(susp.isSuspended());
        Assert.assertFalse(susp.isEnded());
        Assert.assertFalse(susp.isActive());

        abq.put("MNO");
        String output = (String) susp.resume("XYZ");
        c.count();
        Assert.assertEquals("ABCXYZ", output);
        Assert.assertFalse(susp.isSuspended());
        Assert.assertTrue(susp.isEnded());
        Assert.assertFalse(susp.isActive());

        output = (String) abq.take();
        Assert.assertEquals("XYZMNO", output);

        c.assertEquals(5, 1, 2);

    }

    @FlowMethod
    private String callAndSuspend(ArrayBlockingQueue<Object> abq, Counter c, String s1) throws InterruptedException {
        c.count();
        String s2 = (String) Flow.suspend();
        c.count();
        String s3 = (String) abq.take();
        abq.put(s2 + s3);
        return s1 + s2;
    }

    @Test
    public void testSuspendObj() throws Throwable {
        ArrayBlockingQueue<Object> abq = new ArrayBlockingQueue<Object>(1);
        Counter c = new Counter();
        c.count();
        SuspendSignal signal;
        try {
            callAndSuspendObj(abq, c, "ABC");
            Assert.fail("Didn't threw suspend signal.");
            throw new AssertionError();
        } catch (SuspendSignal s) {
            signal = s;
        }
        c.count();
        Flow susp = signal.getFlow();
        Assert.assertTrue(susp.isSuspended());
        Assert.assertFalse(susp.isEnded());
        Assert.assertFalse(susp.isActive());

        Assert.assertEquals("(ABC)", signal.getResult());

        abq.put("MNO");
        String output = (String) susp.resume("XYZ");
        c.count();
        Assert.assertEquals("ABCXYZ", output);
        Assert.assertFalse(susp.isSuspended());
        Assert.assertTrue(susp.isEnded());
        Assert.assertFalse(susp.isActive());

        output = (String) abq.take();
        Assert.assertEquals("XYZMNO", output);

        c.assertEquals(5, 1, 2);

    }

    @FlowMethod
    private String callAndSuspendObj(ArrayBlockingQueue<Object> abq, Counter c, String s1) throws InterruptedException {
        c.count();
        String s2 = (String) Flow.suspend("(" + s1 + ")");
        c.count();
        String s3 = (String) abq.take();
        abq.put(s2 + s3);
        return s1 + s2;
    }

    @Test
    public void testSuspendThrowing() throws Throwable {
        ArrayBlockingQueue<Object> abq = new ArrayBlockingQueue<Object>(1);
        Counter c = new Counter();
        c.count();
        SuspendSignal signal;
        try {
            callAndSuspendCatching(abq, c, "ABC");
            Assert.fail("Didn't threw suspend signal.");
            throw new AssertionError();
        } catch (SuspendSignal s) {
            signal = s;
        }
        c.count();
        Flow susp = signal.getFlow();
        Assert.assertTrue(susp.isSuspended());
        Assert.assertFalse(susp.isEnded());
        Assert.assertFalse(susp.isActive());

        Assert.assertEquals("(ABC)", signal.getResult());

        abq.put("MNO");
        String output = (String) susp.resumeThrowing(new Exception("DEF"));
        c.count();
        Assert.assertEquals("ABCDEF", output);
        Assert.assertFalse(susp.isSuspended());
        Assert.assertTrue(susp.isEnded());
        Assert.assertFalse(susp.isActive());

        output = (String) abq.take();
        Assert.assertEquals("DEFMNO", output);

        c.assertEquals(5, 2);

    }

    @FlowMethod
    private String callAndSuspendCatching(ArrayBlockingQueue<Object> abq, Counter c, String s1) throws InterruptedException {
        c.count();
        String s2;
        try {
            s2 = (String) Flow.suspend("(" + s1 + ")");
            Assert.fail();
        } catch (ResumeException e) {
            s2 = e.getCause().getMessage();
        }
        c.count();
        String s3 = (String) abq.take();
        abq.put(s2 + s3);
        return s1 + s2;
    }

    private Object putAndGet(ArrayBlockingQueue<Object> abq, Object input) throws InterruptedException {
        abq.put(input);
        for (int i = 0; i < 10; ++i) {
            Object got = abq.peek();
            if (got != null && got != input) {
                if (abq.poll() != got) {
                    throw new IllegalStateException();
                }
                return got;
            }
            Thread.sleep(100);
        }
        Assert.fail("No response from the queue.");
        throw new AssertionError();
    }

}
