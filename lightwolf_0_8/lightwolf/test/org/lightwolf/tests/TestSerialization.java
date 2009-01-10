package org.lightwolf.tests;

import java.io.Serializable;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;

import org.junit.Assert;
import org.junit.Test;
import org.lightwolf.FileProcess;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.Process;
import org.lightwolf.SuspendSignal;

public class TestSerialization implements Serializable {

    private static final long serialVersionUID = 1L;
    private static ArrayBlockingQueue<Object> abq;
    private static FileProcess process;

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
    public void testProcessSerialization() throws Throwable {
        final int size = 10;
        abq = new ArrayBlockingQueue<Object>(size);
        process = new FileProcess("testProcessSerialization.dat");

        ProcessFlow[] pfs = new ProcessFlow[size];
        Flow[] flows = new Flow[size];

        System.out.println("0.1");
        for (int i = 0; i < size; ++i) {
            pfs[i] = new ProcessFlow(i);
        }
        System.out.println("0.2");
        for (int i = 0; i < size; ++i) {
            flows[i] = Flow.submit(pfs[i]);
        }
        BitSet bs = new BitSet(size);
        List<ProcessFlow> list = Arrays.asList(pfs);
        System.out.println("0.5");
        for (int i = 0; i < size; ++i) {
            ProcessFlow pf = (ProcessFlow) abq.take();
            int index = list.indexOf(pf);
            Assert.assertFalse(bs.get(index));
            bs.set(index);
        }
        System.out.println("1");
        for (int i = 0; i < size; ++i) {
            flows[i].waitSuspended();
        }
        System.out.println("2");
        Assert.assertTrue(process.passivate());
        process.activate();
        System.out.println("3");
        Random r = new Random(0);
        for (int i = 0; i < size; ++i) {
            Long l = r.nextLong();
            send(i, l);
            Assert.assertEquals(l, abq.take());
            ProcessFlow pf = (ProcessFlow) abq.take();
            Assert.assertNotSame(pfs[i], pf);
            Assert.assertFalse(list.contains(pf));
        }
        for (int i = 0; i < size; ++i) {
            flows[i].join();
        }
    }

    @FlowMethod
    private void send(Object addr, Object data) {
        Flow.joinProcess(process);
        Process.send(addr, data);
    }

    static class ProcessFlow implements Runnable, Serializable {

        private static final long serialVersionUID = 1L;
        final Integer addr;

        ProcessFlow(Integer addr) {
            this.addr = addr;
        }

        @FlowMethod
        public void run() {
            try {
                Flow.joinProcess(process);
                abq.put(this);
                Object data = Process.receive(addr);
                abq.put(data);
                abq.put(this);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }

}
