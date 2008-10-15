package org.lightwolf.tests;

import java.util.concurrent.Callable;
import java.util.concurrent.SynchronousQueue;

import org.junit.Assert;
import org.junit.Test;
import org.lightwolf.Continuation;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;

public class TestBasics {

    /**
     * Tests whether a method with no calls to {@link FlowMethod} methods is
     * correclty enhanced.
     */
    @Test
    @FlowMethod
    public void empty() throws Throwable {
        Counter c = new Counter();
        c.count();
        c.assertEquals(1, 1);
        callEmpty();
        c.assertEquals(1, 1);
        callCountOnly(c);
        c.assertEquals(2, 1);
    }

    @FlowMethod
    private void callEmpty() {
    // This is the empty method.
    }

    @FlowMethod
    private void callCountOnly(Counter c) {
        c.count();
    }

    /**
     * Tests whether an exception thrown by a called method is correctly
     * propagated to the caller.
     */
    @Test
    @FlowMethod
    public void catchCallerException() throws Throwable {
        boolean gotcha;
        try {
            gotcha = false;
            throwSomething();
            Assert.fail();
        } catch (Exception e) {
            Assert.assertEquals("Something", e.getMessage());
            empty();
            gotcha = true;
        }
        Assert.assertTrue(gotcha);
        empty();
        // Do it again to insure that the state is correct.
        try {
            gotcha = false;
            empty();
            throwSomething();
            Assert.fail();
        } catch (Exception e) {
            gotcha = true;
            Assert.assertEquals("Something", e.getMessage());
            empty();
        }
        Assert.assertTrue(gotcha);
        empty();
    }

    @FlowMethod
    private static void throwSomething() throws Exception {
        throw new Exception("Something");
    }

    /**
     * Tests whether a NPE during a call is handled correclty.
     */
    @Test
    @FlowMethod
    public void npeDuringCall() throws Throwable {
        try {
            throwNPEDuringCall();
            Assert.fail();
        } catch (NullPointerException e) {
            StackTraceElement ste = e.getStackTrace()[0];
            Assert.assertEquals("throwNPEDuringCall", ste.getMethodName());
        }
    }

    @FlowMethod
    private static void throwNPEDuringCall() throws Throwable {
        TestBasics nullRef = getNullRef(); // Returns a null reference.
        nullRef.empty(); // Throws NPE because nullRef == null.
        Assert.fail();
    }

    private static TestBasics getNullRef() {
        return null;
    }

    @Test
    public void continuation() {
        Counter c = new Counter();
        Continuation cont = doContinuation(c, 0);
        c.assertEquals(1, 1);
        if (cont.resume() != null) {
            Assert.fail();
        }
        c.assertEquals(2, 1, 2);
    }

    @FlowMethod
    private static Continuation doContinuation(Counter c, int count) {
        c.count();
        c.assertEquals(++count, 1);
        Continuation cont = new Continuation();
        if (!cont.checkpoint()) {
            c.count();
            c.assertEquals(++count, 1, 2);
            return null;
        }
        c.assertEquals(count, 1);
        return cont;
    }

    @Test
    public void runnable() throws Throwable {
        final SynchronousQueue<String> q = new SynchronousQueue<String>();
        Runnable obj = new Runnable() {

            @FlowMethod
            public void run() {
                try {
                    String s;
                    s = q.take();
                    q.put(s + "ABC");
                    String s1 = (String) Flow.suspend("DEF");
                    s = q.take();
                    q.put(s + "GHI" + s1);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        Flow flow = Flow.submit(obj);
        String s;
        q.put("123");
        s = q.take();
        Assert.assertEquals("123ABC", s);
        s = (String) flow.waitSuspended();
        Assert.assertTrue(flow.isSuspended());
        Assert.assertEquals("DEF", s);
        flow.activate("456");
        q.put("789");
        s = q.take();
        Assert.assertEquals("789GHI456", s);
        flow.join();
        Assert.assertTrue(flow.isFinished());
    }

    @Test
    public void callable() throws Throwable {
        final SynchronousQueue<String> q = new SynchronousQueue<String>();
        Callable<?> obj = new Callable<String>() {

            @FlowMethod
            public String call() {
                try {
                    String s;
                    s = q.take();
                    q.put(s + "ABC");
                    String s1 = (String) Flow.suspend("DEF");
                    s = q.take();
                    q.put(s + "GHI" + s1);
                    return s1 + "JKL" + s;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        Flow flow = Flow.submit(obj);
        String s;
        q.put("123");
        s = q.take();
        Assert.assertEquals("123ABC", s);
        s = (String) flow.waitSuspended();
        Assert.assertTrue(flow.isSuspended());
        Assert.assertEquals("DEF", s);
        flow.activate("456");
        q.put("789");
        s = q.take();
        Assert.assertEquals("789GHI456", s);
        flow.join();
        Assert.assertTrue(flow.isFinished());
    }

}
