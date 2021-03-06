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

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.SynchronousQueue;

import org.junit.Assert;
import org.junit.Test;
import org.lightwolf.Continuation;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.FlowSignal;
import org.lightwolf.SuspendSignal;

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

    @Test
    public void catchSignal() {
        try {
            suspend();
            Assert.fail("Must never get here.");
        } catch (SuspendSignal s) {
            // Fine.
        }
    }

    @FlowMethod
    public void suspend() {
        try {
            Flow.suspend();
        } catch (Throwable e) {
            throw new RuntimeException("Must never get here.", e);
        }
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
        Flow.setProperty("test", 123);
        c.count();
        c.assertEquals(++count, 1);
        Continuation cont = new Continuation();
        if (!cont.checkpoint()) {
            Assert.assertEquals(123, Flow.getProperty("test"));
            c.count();
            c.assertEquals(++count, 1, 2);
            return null;
        }
        c.assertEquals(count, 1);
        return cont;
    }

    private Flow testFlow;

    @Test
    public void execRunnable() throws Throwable {
        SynchronousQueue<String> q = new SynchronousQueue<String>();
        final Runnable obj = getRunnable(q);
        testFlow = null;
        new Thread() {

            @Override
            public void run() {
                try {
                    Flow.execute(obj);
                } catch (FlowSignal s) {
                    s.defaultAction();
                }
            }
        }.start();
        Flow flow;
        synchronized(this) {
            while (testFlow == null) {
                wait();
            }
            flow = testFlow;
        }
        checkRunnable(flow, q);
    }

    @Test
    public void submitRunnable() throws Throwable {
        final SynchronousQueue<String> q = new SynchronousQueue<String>();
        Runnable obj = getRunnable(q);
        Flow flow = Flow.submit(obj);
        checkRunnable(flow, q);
    }

    private Runnable getRunnable(final SynchronousQueue<String> q) {
        return new Runnable() {

            @FlowMethod
            public void run() {
                try {
                    synchronized(TestBasics.this) {
                        testFlow = Flow.current();
                        TestBasics.this.notifyAll();
                    }
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
    }

    private void checkRunnable(Flow flow, SynchronousQueue<String> q) throws InterruptedException {
        String s;
        q.put("123");
        s = q.take();
        Assert.assertEquals("123ABC", s);
        s = (String) flow.waitSuspended().getResult();
        Assert.assertTrue(flow.isSuspended());
        Assert.assertEquals("DEF", s);
        flow.activate("456");
        q.put("789");
        s = q.take();
        Assert.assertEquals("789GHI456", s);
        flow.join();
        Assert.assertTrue(flow.isEnded());
    }

    @Test
    public void submitCallable() throws Throwable {
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
        checkRunnable(flow, q);
    }

    @Test
    @FlowMethod
    public void invokeJavaLangMethod() throws Throwable, NoSuchMethodException {
        Method m = getClass().getMethod("theMethod", Counter.class, int[].class, Continuation[].class);
        Counter counter = new Counter();
        int[] sync = new int[1];
        Continuation[] cont = new Continuation[1];
        if (Flow.split(1) == 1) {
            int v = (Integer) Flow.invoke(m, this, counter, sync, cont);
            counter.count();
            send(sync, v + 1);
            Flow.end();
        }
        wait(sync, 123 + 1);
        counter.assertEquals(7, 5);
        sync[0] = 1;
        cont[0].activate();
        wait(sync, 1 + 1);
        sync[0] = 2;
        cont[0].activate();
        wait(sync, 2 + 1);
        counter.assertEquals(11, 7);
    }

    @FlowMethod
    public int theMethod(Counter counter, int[] sync, Continuation[] cont) throws Throwable {
        counter.count(); // 1
        Flow.fork(4);
        counter.count(); // 1 + 4
        Flow.merge();
        cont[0] = new Continuation();
        if (cont[0].checkpoint()) {
            return 123;
        }
        counter.count(); // N
        return sync[0];
    }

    private static void send(int[] sync, int i) {
        synchronized(sync) {
            sync[0] = i;
            sync.notifyAll();
        }
    }

    private static void wait(int[] sync, int i) throws InterruptedException {
        synchronized(sync) {
            while (sync[0] != i) {
                sync.wait();
            }
        }
    }

}
