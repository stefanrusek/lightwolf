package org.lightwolf.tests;

import org.junit.Test;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.Process;

public class TestProcess {

    @Test
    @FlowMethod
    public void simplest() throws Throwable {
        int branch = Flow.fork(1);
        if (branch == 1) {
            Process.receive("key");
        }
    }
}
