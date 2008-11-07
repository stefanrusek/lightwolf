package org.lightwolf.tests;

import java.util.concurrent.SynchronousQueue;

import junit.framework.Assert;

import org.junit.Test;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.Process;

public class TestProcess {

    @Test
    @FlowMethod
    public void simplest() throws Throwable {
        Process process = new Process();
        Flow.joinProcess(process);
        SynchronousQueue<String> sq = new SynchronousQueue<String>();
        int branch = Flow.fork(1);
        if (branch == 1) {
            String value = (String) Process.receive("key");
            sq.put(value);
            Flow.end();
        }
        Thread.sleep(50); // Wait for the other thread arrive at receive.
        Process.send("key", "value");
        String value = sq.take();
        Assert.assertEquals("value", value);
    }
}
