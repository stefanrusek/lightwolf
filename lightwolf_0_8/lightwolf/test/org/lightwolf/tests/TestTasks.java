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

import java.util.concurrent.Callable;
import java.util.concurrent.SynchronousQueue;

import junit.framework.Assert;

import org.junit.Test;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.IRequest;
import org.lightwolf.Task;

public class TestTasks {

    @Test
    @FlowMethod
    public void simpleWaitNotify() throws Throwable {
        Task task = new Task();
        Flow.joinTask(task);
        SynchronousQueue<String> sq = new SynchronousQueue<String>();
        int branch = Flow.split(1);
        if (branch == 1) {
            String value = (String) Task.wait("key");
            sq.put(value);
            Flow.end();
        }
        Thread.sleep(50); // Wait for the other thread arrive at receive.
        Task.notifyAll("key", "value");
        String value = sq.take();
        Assert.assertEquals("value", value);
    }

    @Test
    @FlowMethod
    public void waitManyNotify() throws Throwable {
        Task task = new Task();
        Flow.joinTask(task);
        SynchronousQueue<Integer> sq = new SynchronousQueue<Integer>();
        int branch = Flow.split(1);
        if (branch == 1) {
            Integer value = (Integer) Task.waitMany("key");
            System.out.println("Received " + value);
            sq.put(value);
            Flow.end();
        }
        Thread.sleep(50); // Wait for the other thread arrive at receive.
        Integer value;

        value = 123;
        Task.notifyAll("key", value);
        Assert.assertEquals(value, sq.take());
        System.out.println("Took " + value);

        value = 456;
        Task.notifyAll("key", value);
        Assert.assertEquals(value, sq.take());

        boolean[] test = new boolean[8];
        branch = Flow.split(test.length);
        if (branch > 0) {
            Task.notifyAll("key", branch);
            System.out.println("Sent " + branch);
            branch = sq.take();
            synchronized(test) {
                test[branch - 1] = true;
                test.notify();
            }
            Flow.end();
        }
        synchronized(test) {
            loop: for (;;) {
                for (int i = 0; i < test.length; ++i) {
                    if (!test[i]) {
                        test.wait();
                        continue loop;
                    }
                }
                break;
            }
        }

    }

    @Test
    public void simpleSendReceive() throws Throwable {
        final boolean[] success = new boolean[] { false };
        Callable<Object> callable = new Callable<Object>() {

            @FlowMethod
            public Object call() throws Exception {
                Task task = new Task();
                Flow.joinTask(task);
                SynchronousQueue<String> sq = new SynchronousQueue<String>();
                int branch = Flow.split(1);
                if (branch == 1) {
                    String value = (String) Task.receive("key");
                    sq.put(value);
                    Flow.end();
                }
                Task.send("key", "value");
                String value = sq.take();
                Assert.assertEquals("value", value);
                success[0] = true;
                return null;
            }
        };
        Flow flow = Flow.submit(callable);
        flow.join();
        Assert.assertTrue(success[0]);
    }

    @Test
    public void sendReceiveMany() throws Throwable {
        final boolean[] success = new boolean[] { false };
        Callable<Object> callable = new Callable<Object>() {

            @FlowMethod
            public Object call() throws Exception {
                Task task = new Task();
                Flow.joinTask(task);
                SynchronousQueue<Integer> sq = new SynchronousQueue<Integer>();
                int branch = Flow.split(1);
                if (branch == 1) {
                    Integer value = (Integer) Task.receiveMany("key");
                    System.out.println("Received " + value);
                    sq.put(value);
                    Flow.end();
                }
                boolean[] test = new boolean[8];
                branch = Flow.split(test.length);
                if (branch > 0) {
                    Task.send("key", branch);
                    System.out.println("Sent " + branch);
                    branch = sq.take();
                    synchronized(test) {
                        test[branch - 1] = true;
                        test.notify();
                    }
                    Flow.end();
                }
                synchronized(test) {
                    loop: for (;;) {
                        for (int i = 0; i < test.length; ++i) {
                            if (!test[i]) {
                                test.wait();
                                continue loop;
                            }
                        }
                        break;
                    }
                }
                success[0] = true;
                return null;
            }
        };
        Flow flow = Flow.submit(callable);
        flow.join();
        Assert.assertTrue(success[0]);
    }

    @Test
    public void serveManyCalls() throws Throwable {
        final SynchronousQueue<Object> sq = new SynchronousQueue<Object>();
        Runnable runnable = new Runnable() {

            @FlowMethod
            public void run() {
                try {
                    Flow.joinTask(new Task());
                    if (Flow.split(1) == 0) {
                        IRequest request = Task.serveMany("PeerA");
                        sq.put("Req:" + request.request());
                        request.respond(sq.take());
                    } else {
                        for (;;) {
                            Object request = sq.take();
                            if (request.equals("end")) {
                                return;
                            }
                            Object response = Task.call("PeerA", request);
                            sq.put("Res:" + response);
                        }
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

        };

        Flow.submit(runnable);

        sq.put("1");
        Assert.assertEquals("Req:1", sq.take());
        sq.put("2");
        Assert.assertEquals("Res:2", sq.take());

        sq.put("11");
        Assert.assertEquals("Req:11", sq.take());
        sq.put("22");
        Assert.assertEquals("Res:22", sq.take());

        sq.put("end");

    }

}
