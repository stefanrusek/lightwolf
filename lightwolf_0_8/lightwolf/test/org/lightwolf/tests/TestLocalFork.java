package org.lightwolf.tests;

import org.junit.Assert;
import org.junit.Test;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;

public class TestLocalFork {

    @Test
    @FlowMethod
    public void simplest() throws Throwable {
        Counter c = new Counter();
        Flow.fork(4);
        c.count();
        Flow.merge();
        c.assertEquals(5, 5);
    }

    @Test
    @FlowMethod
    public void forkInsideFork() throws Throwable {
        Counter c = new Counter();
        c.count(); // 1
        Flow.fork(1);
        c.count(); // 2
        Flow.fork(1);
        c.count(); // 4
        Flow.merge();
        c.count(); // 2
        Flow.merge();
        c.count(); // 1
        c.assertEquals(10, 4);
    }

    @Test
    @FlowMethod
    public void callThenforkThenThrow() throws Throwable {
        Counter c = new Counter();
        c.count(); // 1
        try {
            forkThenThrow(c); // 3
            c.count(); // 0
            Assert.fail("Expected an exception.");
        } catch (TestException e) {
            c.count(); // 2
        }
        c.count(); // 2
        Flow.merge();
        c.count(); // 1
        c.assertEquals(9, 2);
    }

    @FlowMethod
    public void forkThenThrow(Counter c) {
        c.count(); // 1
        Flow.fork(1);
        c.count(); // 2
        throw new TestException();
    }

    @Test
    @FlowMethod
    public void forkThenCallThenFork() throws Throwable {
        Counter c = new Counter();
        c.count(); // 1
        Flow.fork(1);
        c.count(); // 2
        callThenFork(c); // 12
        c.count(); // 2
        Flow.merge();
        c.count(); // 1
        c.assertEquals(18, 4, 6);
    }

    private void callThenFork(Counter c) throws Throwable {
        c.count(); // 2
        fork(c); // 8
        c.count(); // 2
    }

    @FlowMethod
    public void fork(Counter c) throws Throwable {
        c.count(); // 2
        Flow.fork(1);
        c.count(); // 4
        Flow.merge();
        c.count(); // 2
    }

    @Test
    @FlowMethod
    public void forkOnSecondCall() throws Throwable {
        Counter c = new Counter();
        callString(false, "ABC", c); // count 1.
        c.count(); // count 1.
        callInteger(true, 123, c); // count 2.
        c.count(); // count 2.
        Flow.merge();
        c.assertEquals(6, 2);
    }

    @FlowMethod
    private void callString(boolean fork, String arg, Counter c) {
        System.out.println(arg);
        if (fork) {
            Flow.fork(1);
        }
        c.count();
    }

    @FlowMethod
    private void callInteger(boolean fork, Integer arg, Counter c) {
        System.out.println(arg);
        if (fork) {
            Flow.fork(1);
        }
        c.count();
    }

    @Test
    @FlowMethod
    public void recursive() throws Throwable {
        Counter c = new Counter();
        forkInside(7, 3, c);
        Flow.merge();
        c.assertEquals(8 * 4, 4);
    }

    @FlowMethod
    private void forkInside(int level, int count, Counter c) {
        if (level == 0) {
            Flow.fork(count);
        } else {
            forkInside(level - 1, count, c);
        }
        c.count();
    }

    @Test
    public void callThenForkAndForget() throws Throwable {
        Counter c = new Counter();
        c.count(); // 1
        callThenFork(4, c); // 6
        for (int i = 0; i < 10; ++i) {
            if (c.getCount() == 7) {
                break;
            }
            Thread.sleep(200);
        }
        c.assertEquals(7, 6);
    }

    @FlowMethod
    private void callThenFork(int count, Counter c) {
        c.count(); // 1
        Flow.fork(count);
        c.count(); // count+1
        // Forget.
    }

}
