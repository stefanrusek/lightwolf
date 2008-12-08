package org.lightwolf.tests;

import java.util.concurrent.Callable;
import java.util.concurrent.SynchronousQueue;

import junit.framework.Assert;

import org.junit.Test;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.Process;

public class TestProcess {

    @Test
    @FlowMethod
    public void simpleWaitNotify() throws Throwable {
        Process process = new Process();
        Flow.joinProcess(process);
        SynchronousQueue<String> sq = new SynchronousQueue<String>();
        int branch = Flow.split(1);
        if (branch == 1) {
            String value = (String) Process.wait("key");
            sq.put(value);
            Flow.end();
        }
        Thread.sleep(50); // Wait for the other thread arrive at receive.
        Process.notifyAll("key", "value");
        String value = sq.take();
        Assert.assertEquals("value", value);
    }

    @Test
    @FlowMethod
    public void waitManyNotify() throws Throwable {
        Process process = new Process();
        Flow.joinProcess(process);
        SynchronousQueue<Integer> sq = new SynchronousQueue<Integer>();
        int branch = Flow.split(1);
        if (branch == 1) {
            Integer value = (Integer) Process.waitMany("key");
            System.out.println("Received " + value);
            sq.put(value);
            Flow.end();
        }
        Thread.sleep(50); // Wait for the other thread arrive at receive.
        Integer value;

        value = 123;
        Process.notifyAll("key", value);
        Assert.assertEquals(value, sq.take());

        value = 456;
        Process.notifyAll("key", value);
        Assert.assertEquals(value, sq.take());

        boolean[] test = new boolean[8];
        branch = Flow.split(test.length);
        if (branch > 0) {
            Process.notifyAll("key", branch);
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
                Process process = new Process();
                Flow.joinProcess(process);
                SynchronousQueue<String> sq = new SynchronousQueue<String>();
                int branch = Flow.split(1);
                if (branch == 1) {
                    String value = (String) Process.receive("key");
                    sq.put(value);
                    Flow.end();
                }
                Process.send("key", "value");
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
                Process process = new Process();
                Flow.joinProcess(process);
                SynchronousQueue<Integer> sq = new SynchronousQueue<Integer>();
                int branch = Flow.split(1);
                if (branch == 1) {
                    Integer value = (Integer) Process.receiveMany("key");
                    System.out.println("Received " + value);
                    sq.put(value);
                    Flow.end();
                }
                boolean[] test = new boolean[8];
                branch = Flow.split(test.length);
                if (branch > 0) {
                    Process.send("key", branch);
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

}
