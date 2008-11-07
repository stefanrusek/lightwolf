package org.lightwolf.tests;

import org.junit.Test;
import org.lightwolf.FlowMethod;
import org.lightwolf.process.CurrentProcess;
import org.lightwolf.process.Process;

public class TestCurrentProcessFork {

    @Test
    @FlowMethod
    public void simplest() throws Throwable {
        CurrentProcess.setCurrent(new Process());
        CurrentProcess.enter();
        Counter c = new Counter();
        CurrentProcess.fork();
        for (int i = 0; i < 5; ++i) {
            if (CurrentProcess.onPath()) {
                c.count();
            }
        }
        CurrentProcess.join();
        c.assertEquals(5, 5);
        CurrentProcess.exit();
    }

    @Test
    @FlowMethod
    public void forkInsideFork() throws Throwable {
        CurrentProcess.setCurrent(new Process());
        CurrentProcess.enter();
        Counter c = new Counter();
        c.count(); // 1
        CurrentProcess.fork();
        for (int i = 0; i < 2; ++i) {
            if (CurrentProcess.onPath()) {
                c.count(); // 2
                CurrentProcess.fork();
                for (int j = 0; j < 2; ++j) {
                    if (CurrentProcess.onPath()) {
                        c.count(); // 4
                    }
                }
                CurrentProcess.join();
                c.count(); // 2
            }
        }
        CurrentProcess.join();
        c.count(); // 1
        c.assertEquals(10, 10, 7);
        CurrentProcess.exit();
    }

    @Test
    public void intermitentForkInsideFork() throws Throwable {
        for (int i = 0; i < 50; ++i) {
            forkInsideFork();
        }
    }

    @Test
    @FlowMethod
    public void callThenForkThenThrow() throws Throwable {
        CurrentProcess.setCurrent(new Process());
        CurrentProcess.enter();
        Counter c = new Counter();
        c.count(); // 1
        try {
            forkThenThrow(c); // 3
            c.count(); // 1
        } catch (TestException e) {
            c.count(); // 2
        }
        c.count(); // 3
        CurrentProcess.join();
        c.count(); // 1
        c.assertEquals(11, 3);
        CurrentProcess.exit();
    }

    @FlowMethod
    public void forkThenThrow(Counter c) {
        c.count(); // 1
        CurrentProcess.fork();
        for (int i = 0; i < 2; ++i) {
            if (CurrentProcess.onPath()) {
                c.count(); // 2
                throw new TestException();
            }
        }
    }

    @Test
    @FlowMethod
    public void forkThenCallThenFork() throws Throwable {
        CurrentProcess.setCurrent(new Process());
        CurrentProcess.enter();
        Counter c = new Counter();
        c.count(); // 1
        CurrentProcess.fork();
        for (int i = 0; i < 2; ++i) {
            if (CurrentProcess.onPath()) {
                c.count(); // 2
                callThenFork(c); // 12
                c.count(); // 2
            }
        }
        CurrentProcess.join();
        c.count(); // 1
        c.assertEquals(18, 7, 9);
        CurrentProcess.exit();
    }

    private void callThenFork(Counter c) throws Throwable {
        c.count(); // 2
        fork(c); // 8
        c.count(); // 2
    }

    @FlowMethod
    private void fork(Counter c) throws Throwable {
        CurrentProcess.setCurrent(new Process());
        CurrentProcess.enter();
        c.count(); // 2
        CurrentProcess.fork();
        for (int i = 0; i < 2; ++i) {
            if (CurrentProcess.onPath()) {
                c.count(); // 4
            }
        }
        CurrentProcess.join();
        c.count(); // 2
        CurrentProcess.exit();
    }

    @Test
    @FlowMethod
    public void forkOnSecondCall() throws Throwable {
        CurrentProcess.setCurrent(new Process());
        CurrentProcess.enter();
        Counter c = new Counter();
        callString(false, "ABC", c); // 1.
        c.count(); // 1.
        callInteger(true, 123, c); // 2.
        c.count(); // 3.
        CurrentProcess.join();
        c.assertEquals(7, 3);
        CurrentProcess.exit();
    }

    @FlowMethod
    private void callString(boolean fork, String arg, Counter c) {
        System.out.println(arg);
        if (fork) {
            CurrentProcess.fork();
            for (int i = 0; i < 2; ++i) {
                if (CurrentProcess.onPath()) {
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
        System.out.println(arg);
        if (fork) {
            CurrentProcess.fork();
            for (int i = 0; i < 2; ++i) {
                if (CurrentProcess.onPath()) {
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
        CurrentProcess.setCurrent(new Process());
        CurrentProcess.enter();
        Counter c = new Counter();
        int levels = 5;
        int count = 3;
        forkInside(levels, count, c);
        CurrentProcess.join();
        c.assertEquals(count + levels * (count + 1), count + 1);
        CurrentProcess.exit();
    }

    @FlowMethod
    private void forkInside(int level, int count, Counter c) {
        if (level == 0) {
            CurrentProcess.fork();
            for (int i = 0; i < count; ++i) {
                if (CurrentProcess.onPath()) {
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
        CurrentProcess.setCurrent(new Process());
        CurrentProcess.enter();
        c.count(); // 1
        CurrentProcess.fork();
        for (int i = 0; i < count; ++i) {
            if (CurrentProcess.onPath()) {
                c.count(); // count
                // Forget - do not call join().
                CurrentProcess.exit();
                return;
            }
        }
        CurrentProcess.exit();
    }

}
