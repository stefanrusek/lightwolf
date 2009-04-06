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
