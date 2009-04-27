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

import org.junit.Assert;
import org.junit.Test;
import org.lightwolf.Flow;
import org.lightwolf.FlowInterruptedException;
import org.lightwolf.FlowMethod;
import org.lightwolf.SuspendSignal;
import org.lightwolf.Task;

public class TestInterrupt {

    @Test
    @FlowMethod
    public void unusedInterrupt() throws Throwable {

        Flow[] flow = new Flow[1];
        int[] sync = new int[1];
        if (Flow.split(1) == 1) {
            try {
                flow[0] = Flow.current();
                send(sync, 1);
                wait(sync, 2);
                Flow.end();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }

        wait(sync, 1);
        flow[0].interrupt();
        send(sync, 2);
        flow[0].waitNotRunning();
        Assert.assertTrue(flow[0].isEnded());
    }

    @Test
    @FlowMethod
    public void interruptAfterSignal() throws Throwable {

        Flow[] flow = new Flow[1];
        int[] sync = new int[1];
        if (Flow.split(1) == 1) {
            try {
                flow[0] = Flow.current();
                send(sync, 1);
                try {
                    Flow.suspend();
                } catch (FlowInterruptedException e) {
                    send(sync, 2);
                }
                Flow.end();
            } catch (Throwable e) {
                e.printStackTrace();
                throw e;
            }
        }

        wait(sync, 1);
        Thread.sleep(100); // Wait arrive on suspend().
        flow[0].interrupt();
        wait(sync, 2);
        flow[0].waitNotRunning();
        Assert.assertTrue(flow[0].isEnded());
    }

    @Test
    @FlowMethod
    public void interruptBeforeSignal() throws Throwable {

        Flow[] flow = new Flow[1];
        int[] sync = new int[1];
        if (Flow.split(1) == 1) {
            try {
                flow[0] = Flow.current();
                send(sync, 1);
                wait(sync, 2);
                try {
                    Flow.suspend();
                } catch (FlowInterruptedException e) {
                    send(sync, 3);
                }
                Flow.end();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }

        wait(sync, 1);
        flow[0].interrupt();
        send(sync, 2);
        wait(sync, 3);
        flow[0].waitNotRunning();
        Assert.assertTrue(flow[0].isEnded());
    }

    @Test
    @FlowMethod
    public void interruptBeforeSplit() throws Throwable {

        Flow[] flow = new Flow[1];
        int[] sync = new int[1];
        if (Flow.split(1) == 1) {
            try {
                flow[0] = Flow.current();
                send(sync, 1);
                wait(sync, 2);
                try {
                    if (Flow.split(1) == 1) {
                        Flow.end();
                    }
                } catch (FlowInterruptedException e) {
                    send(sync, 3);
                }
                Flow.end();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }

        wait(sync, 1);
        flow[0].interrupt();
        send(sync, 2);
        wait(sync, 3);
        flow[0].waitNotRunning();
        Assert.assertTrue(flow[0].isEnded());
    }

    @Test
    public void interruptFromController() throws Throwable {

        Flow[] flow = new Flow[1];
        int[] sync = new int[1];
        try {
            callFlow(flow, sync);
            Assert.fail();
        } catch (SuspendSignal e) {
            // OK.
        }
        flow[0].interrupt();
        wait(sync, 1);
        flow[0].waitNotRunning();
        Assert.assertTrue(flow[0].isEnded());
    }

    @FlowMethod
    private void callFlow(Flow[] flow, int[] sync) {
        flow[0] = Flow.current();
        try {
            Flow.suspend();
            Assert.fail();
        } catch (FlowInterruptedException e) {
            // OK.
        }
        send(sync, 1);
    }

    @Test
    public void receiveInterruptFromController() throws Throwable {

        Task task = new Task();
        Flow[] flow = new Flow[1];
        int[] sync = new int[1];
        try {
            callReceiverFlow(task, flow, sync);
            Assert.fail();
        } catch (SuspendSignal e) {
            // OK.   
        }
        flow[0].interrupt();
        wait(sync, 1);
        flow[0].waitNotRunning();
        Assert.assertTrue(flow[0].isEnded());
    }

    @FlowMethod
    private void callReceiverFlow(Task task, Flow[] flow, int[] sync) {
        Flow.joinTask(task);
        flow[0] = Flow.current();
        try {
            Flow.receive("here");
            Assert.fail();
        } catch (FlowInterruptedException e) {
            // OK.
        }
        send(sync, 1);
    }

    @Test
    public void interruptBeforeReceive() throws Throwable {
        Task task = new Task();
        Flow[] flow = new Flow[1];
        int[] sync = new int[1];
        callReceiverFlow2(task, flow, sync);
        wait(sync, 1);
        flow[0].interrupt();
        send(sync, 2);
        wait(sync, 3);
        flow[0].waitNotRunning();
        Assert.assertTrue(flow[0].isEnded());
    }

    @FlowMethod
    private void callReceiverFlow2(Task task, Flow[] flow, int[] sync) throws InterruptedException {
        Flow.joinTask(task);
        Flow.returnAndContinue();
        flow[0] = Flow.current();
        send(sync, 1);
        wait(sync, 2);
        try {
            Flow.receive("here");
            Assert.fail();
        } catch (FlowInterruptedException e) {
            //OK.
        }
        Assert.assertEquals(flow[0], Flow.current());
        send(sync, 3);
    }

    @Test
    public void interruptBeforeReceiveMany() throws Throwable {
        Task task = new Task();
        Flow[] flow = new Flow[1];
        int[] sync = new int[1];
        callManyReceiverFlow(task, flow, sync);
        wait(sync, 1);
        task.interrupt();
        send(sync, 2);
        wait(sync, 3);
        flow[0].waitNotRunning();
        Assert.assertTrue(flow[0].isEnded());
    }

    @Test
    public void interruptDuringReceiveMany() throws Throwable {
        Task task = new Task();
        Flow[] flow = new Flow[1];
        int[] sync = new int[1];
        callManyReceiverFlow(task, flow, sync);
        wait(sync, 1);
        send(sync, 2);
        Thread.sleep(100); // Wait arrive on receiveMany().
        task.interrupt();
        wait(sync, 3);
        flow[0].waitNotRunning();
        Assert.assertTrue(flow[0].isEnded());
    }

    @FlowMethod
    private void callManyReceiverFlow(Task task, Flow[] flow, int[] sync) throws InterruptedException {
        Flow.joinTask(task);
        Flow.returnAndContinue();
        flow[0] = Flow.current();
        send(sync, 1);
        wait(sync, 2);
        try {
            Flow.receiveMany("here");
            Assert.fail();
        } catch (FlowInterruptedException e) {
            //OK.
        }
        Assert.assertEquals(Flow.task(), task);
        send(sync, 3);
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
