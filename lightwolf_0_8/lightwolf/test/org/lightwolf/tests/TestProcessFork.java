package org.lightwolf.tests;

import org.junit.Test;
import org.lightwolf.FlowMethod;
import org.lightwolf.SuspendSignal;
import org.lightwolf.process.CurrentProcess;
import org.lightwolf.process.OldProcess;

public class TestProcessFork extends OldProcess {

    @Test
    @FlowMethod
    public void simplest() throws Throwable {
        enter();
        Counter c = new Counter();
        fork();
        for (int i = 0; i < 5; ++i) {
            if (onPath()) {
                c.count();
            }
        }
        join();
        c.assertEquals(5, 5);
        exit();
    }

    @Test
    @FlowMethod
    public void forkInsideFork() throws Throwable {
        enter();
        Counter c = new Counter();
        c.count(); // 1
        fork();
        for (int i = 0; i < 2; ++i) {
            if (onPath()) {
                c.count(); // 2
                fork();
                for (int j = 0; j < 2; ++j) {
                    if (onPath()) {
                        c.count(); // 4
                    }
                }
                join();
                c.count(); // 2
            }
        }
        join();
        c.count(); // 1
        c.assertEquals(10, 10, 7);
        exit();
    }

    @Test
    public void intermitentForkInsideFork() throws Throwable {
        for (int i = 0; i < 50; ++i) {
            CurrentProcess.setCurrent(new OldProcess());
            forkInsideFork();
        }
    }

    @Test
    @FlowMethod
    public void callThenForkThenThrow() throws Throwable {
        enter();
        Counter c = new Counter();
        c.count(); // 1
        try {
            forkThenThrow(c); // 3
            c.count(); // 1
        } catch (TestException e) {
            c.count(); // 2
        }
        c.count(); // 3
        join();
        c.count(); // 1
        c.assertEquals(11, 3);
        exit();
    }

    @FlowMethod
    public void forkThenThrow(Counter c) {
        c.count(); // 1
        fork();
        for (int i = 0; i < 2; ++i) {
            if (onPath()) {
                c.count(); // 2
                throw new TestException();
            }
        }
    }

    @Test
    @FlowMethod
    public void forkThenCallThenFork() throws Throwable {
        enter();
        Counter c = new Counter();
        c.count(); // 1
        fork();
        for (int i = 0; i < 2; ++i) {
            if (onPath()) {
                c.count(); // 2
                new TestProcessFork().callThenFork(c); // 12
                c.count(); // 2
            }
        }
        join();
        c.count(); // 1
        c.assertEquals(18, 7, 9);
        exit();
    }

    private void callThenFork(Counter c) throws Throwable {
        c.count(); // 2
        try {
            fork(c); // 8
        } catch (SuspendSignal signal) {
            signal.getFlow().join();
        }
        c.count(); // 2
    }

    @FlowMethod
    private void fork(Counter c) throws Throwable {
        enter();
        c.count(); // 2
        fork();
        for (int i = 0; i < 2; ++i) {
            if (onPath()) {
                c.count(); // 4
            }
        }
        join();
        c.count(); // 2
        exit();
    }

    @Test
    @FlowMethod
    public void forkOnSecondCall() throws Throwable {
        enter();
        Counter c = new Counter();
        callString(false, "ABC", c); // 1.
        c.count(); // 1.
        callInteger(true, 123, c); // 2.
        c.count(); // 3.
        join();
        c.assertEquals(7, 3);
        exit();
    }

    @FlowMethod
    private void callString(boolean fork, String arg, Counter c) {
        assert arg == arg;
        if (fork) {
            fork();
            for (int i = 0; i < 2; ++i) {
                if (onPath()) {
                    c.count();
                    break;
                }
            }
            return;
        }
        c.count();
    }

    @FlowMethod
    private void callInteger(boolean fork, Integer arg, Counter c) {
        assert arg == arg;
        if (fork) {
            fork();
            for (int i = 0; i < 2; ++i) {
                if (onPath()) {
                    c.count();
                    break;
                }
            }
            return;
        }
        c.count();
    }

    @Test
    @FlowMethod
    public void recursive() throws Throwable {
        enter();
        Counter c = new Counter();
        int levels = 5;
        int count = 3;
        forkInside(levels, count, c);
        join();
        c.assertEquals(count + levels * (count + 1), count + 1);
        exit();
    }

    @FlowMethod
    private void forkInside(int level, int count, Counter c) {
        if (level == 0) {
            fork();
            for (int i = 0; i < count; ++i) {
                if (onPath()) {
                    c.count(); // count.
                    break;
                }
            }
            return;
        }
        forkInside(level - 1, count, c);
        c.count(); // count + 1
    }

    @Test
    public void callThenForkAndForget() throws Throwable {
        Counter c = new Counter();
        c.count(); // 1
        callThenFork(4, c); // 5
        for (int i = 0; i < 10; ++i) {
            if (c.getCount() == 6) {
                break;
            }
            Thread.sleep(200);
        }
        c.assertEquals(6, 6);
    }

    @FlowMethod
    public void callThenFork(int count, Counter c) {
        enter();
        c.count(); // 1
        fork();
        for (int i = 0; i < count; ++i) {
            if (onPath()) {
                c.count(); // count
                // Forget - do not call join().
                exit();
                return;
            }
        }
        exit();
    }

}
