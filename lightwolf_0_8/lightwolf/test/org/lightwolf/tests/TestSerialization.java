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

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;

import org.junit.Assert;
import org.junit.Test;
import org.lightwolf.FileTask;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.ITaskListener;
import org.lightwolf.SuspendSignal;
import org.lightwolf.Task;
import org.lightwolf.TaskState;

public class TestSerialization implements Serializable {

    private static final long serialVersionUID = 1L;
    private static ArrayBlockingQueue<Object> abq;
    private static FileTask task;

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
        Assert.assertFalse(susp.isEnded());
        Assert.assertFalse(susp.isActive());

        susp = (Flow) Util.streamedCopy(susp);

        abq.put("MNO");
        String output = (String) susp.resume("XYZ");
        c.count();
        Assert.assertEquals("ABCXYZ", output);
        Assert.assertFalse(susp.isSuspended());
        Assert.assertTrue(susp.isEnded());

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

    @Test
    public void testTaskSerialization() throws Throwable {
        final int size = 10;
        abq = new ArrayBlockingQueue<Object>(size);
        task = new FileTask("testTaskSerialization.dat");
        TaskFlow[] pfs = new TaskFlow[size];
        Flow[] flows = new Flow[size];
        for (int i = 0; i < size; ++i) {
            pfs[i] = new TaskFlow(i);
        }
        for (int i = 0; i < size; ++i) {
            flows[i] = Flow.submit(pfs[i]);
        }
        BitSet bs = new BitSet(size);
        List<TaskFlow> list = Arrays.asList(pfs);
        for (int i = 0; i < size; ++i) {
            TaskFlow pf = (TaskFlow) abq.take();
            int index = list.indexOf(pf);
            Assert.assertFalse(bs.get(index));
            bs.set(index);
        }
        for (int i = 0; i < size; ++i) {
            flows[i].waitSuspended();
        }
        Assert.assertTrue(task.passivate());
        Random r = new Random(0);
        for (int i = 0; i < size; ++i) {
            Long l = r.nextLong();
            send(i, l);
            Assert.assertEquals(l, abq.take());
            TaskFlow pf = (TaskFlow) abq.take();
            Assert.assertNotSame(pfs[i], pf);
            Assert.assertFalse(list.contains(pf));
        }
        for (int i = 0; i < size; ++i) {
            flows[i].join();
        }
    }

    @Test
    public void testAutoPassivation() throws Throwable {
        abq = new ArrayBlockingQueue<Object>(2);
        task = new FileTask("testTaskSerialization.dat");
        task.addEventListener(new ITaskListener() {

            public void onEvent(Task sender, int event, Flow flow) {
                if (event == ITaskListener.PE_FLOW_SUSPENDED) {
                    if (sender.activeFlows() == 0) {
                        try {
                            ((FileTask) sender).passivate();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        });
        TaskFlow initial = new TaskFlow(45);
        Flow flow = Flow.submit(initial);
        TaskFlow temp = (TaskFlow) abq.take();
        Assert.assertEquals(initial, temp);
        flow.waitSuspended();
        for (int i = 0; i < 10; ++i) {
            if (task.getState() == TaskState.PASSIVE) {
                break;
            }
            Thread.sleep(100); // Wait for auto passivation.
        }
        Assert.assertEquals(TaskState.PASSIVE, task.getState());
        Object data = new Long(123);
        send(45, data);
        Assert.assertEquals(data, abq.take());
        temp = (TaskFlow) abq.take();
        Assert.assertNotSame(initial, temp);
        flow.join();
    }

    @FlowMethod
    private void send(Object addr, Object data) {
        Flow.joinTask(new Task());
        Task.send(addr, data);
    }

    static class TaskFlow implements Runnable, Serializable {

        private static final long serialVersionUID = 1L;
        final Integer addr;

        TaskFlow(Integer addr) {
            this.addr = addr;
        }

        @FlowMethod
        public void run() {
            try {
                Flow.joinTask(task);
                abq.put(this);
                Object data = Task.receive(addr);
                abq.put(data);
                abq.put(this);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }

}
