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
package org.lightwolf;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.lightwolf.synchronization.EventPicker;
import org.lightwolf.synchronization.ThreadFreeLock;
import org.lightwolf.tools.PublicByteArrayInputStream;
import org.lightwolf.tools.PublicByteArrayOutputStream;
import org.lightwolf.tools.SimpleFlowManager;
import org.objectweb.asm.Type;

/**
 * An execution context similar to that of a thread, but with more capabilities.
 * <p>
 * Compared to normal {@link Thread threads}, flows simplifies the
 * implementation of concurrent algorithms and scalable applications. The
 * following list summarizes the capabilities of a flow:
 * <ul>
 * <li>A flow can be {@link #suspend() suspended} for later
 * {@link #resume(Object) resuming}, without consuming a Java thread meanwhile.</li>
 * <li>The flow's execution state can be serialized and restored, possibly on a
 * different machine (as long as certain conditions are met). This allows
 * implementation of long running processes in pure Java language.</li>
 * <li>A flow can wait for a {@linkplain ThreadFreeLock lock} on a resource, or for
 * the {@linkplain #waitComplete() completion} of an I/O operation, without
 * consuming a Java thread meanwhile, and thus releasing a pooled thread for
 * increased concurrency.</li>
 * <li>Flows can use utilities such as {@link #currentContext()},
 * {@link #fork(int)}, and {@link #returnAndContinue()}.</li>
 * </ul>
 * <p>
 * There are some important concepts regarding flows.
 * <p>
 * <b>Flow-method:</b> A flow-method is a method marked with the
 * {@link FlowMethod} annotation. Whenever a flow-method is executing, there
 * will be an associated flow instance, which can be obtained by invoking
 * {@link #current()} (such flow is automatically instantiated as described
 * below). If a flow-method invokes itself or another flow-method, both methods
 * use the same flow instance. If a flow-method invokes a normal (non-flow)
 * method, the flow is kept active and can be queried, but some flow utilities
 * will disabled until the normal method returns to the invoker flow-method.
 * <p>
 * <b>Flow-creator:</b> Whenever a flow-method is invoked by a normal (non-flow)
 * method, it is called the flow-creator method, because it triggers the
 * creation of a new flow (this is done automatically). The flow finishes when
 * the flow-creator completes normally or by exception. If the flow-creator
 * calls itself or another flow-method, no new flow is created, as described
 * above.
 * <p>
 * <b>Flow-controller:</b> The flow-controller is a normal (non-flow) method
 * that invokes a flow-method. The flow-controller receives
 * {@linkplain #signal(FlowSignal) signals} sent by some flow operations, and
 * must handle those signals accordingly. There are standard implementations of
 * flow-controllers, such as {@link #execute(Callable)} and
 * {@link SimpleFlowManager}. If it is known that a flow-method will never send
 * signals, the flow-controller can be any ordinary method calling a
 * flow-method. Many utilities do not send any signal, such as
 * {@link #split(int)} and {@link #returnAndContinue()}.
 * <p>
 * According to the above specification, when a flow-method <i>A</i> invokes a
 * normal method <i>B</i>, and then <i>B</i> invokes a flow-method <i>C</i>, a
 * nested flow is created. Regarding the nested flow, <i>B</i> will be the
 * flow-controller and <i>C</i> the flow-creator. If <i>C</i> invokes a flow
 * utility such as {@link #fork(int)}, the effect is applied only to the nested
 * flow. The outer flow, on which <i>A</i> is running, is not affected by the
 * fork. When the nested flow finishes or is suspended, the outer flow becomes
 * active again. The number of nesting levels is limited only by memory. Nested
 * flows are uncommon because usually flow-methods are designed to call other
 * flow-methods, which does not cause the creation of new flow, as mentioned
 * above. Nevertheless, nested flows are allowed as an orthogonality feature.
 *
 * @see FlowMethod
 * @author Fernando Colombo
 */
public final class Flow implements Serializable {

    private static final long serialVersionUID = 6770568702848053327L;

    public static final int ACTIVE = 1;
    public static final int SUSPENDED = 2;
    public static final int SUSPENDING = 3;
    public static final int FINISHED = 4;

    private static final String[] stateNames = new String[] { "0", "ACTIVE", "SUSPENDED", "SUSPENDING", "FINISHED" };

    private static final ThreadLocal<Flow> current = new ThreadLocal<Flow>();

    public static void execute(Runnable runnable) {
        Flow previous = current();
        if (previous != null && !previous.isPassive()) {
            throw new IllegalStateException();
        }
        Flow flow = newFlow(previous);
        try {
            MethodFrame frame = flow.newFrame(runnable, "run", "()V");
            frame.notifyInvoke(Integer.MAX_VALUE, 0, 0);
            flow.state = SUSPENDED;
            flow.currentFrame = null;
            flow.suspendedFrame = frame;
            flow.resume();
        } finally {
            current.set(previous);
        }
    }

    public static Object execute(Callable<?> callable) {
        Flow previous = current();
        if (previous != null && !previous.isPassive()) {
            throw new IllegalStateException();
        }
        Flow flow = newFlow(previous);
        try {
            MethodFrame frame = flow.newFrame(callable, "call", "()Ljava/lang/Object;");
            frame.notifyInvoke(Integer.MAX_VALUE, 0, 0);
            flow.state = SUSPENDED;
            flow.currentFrame = null;
            flow.suspendedFrame = frame;
            return flow.resume();
        } finally {
            current.set(previous);
        }
    }

    public static Flow submit(Runnable runnable) {
        Flow previous = current();
        if (previous != null && !previous.isPassive()) {
            throw new IllegalStateException();
        }
        Flow flow = newFlow(previous);
        try {
            MethodFrame frame = flow.newFrame(runnable, "run", "()V");
            frame.notifyInvoke(Integer.MAX_VALUE, 0, 0);
            flow.state = SUSPENDED;
            flow.currentFrame = null;
            flow.suspendedFrame = frame;
            flow.activate();
            return flow;
        } finally {
            current.set(previous);
        }
    }

    public static Flow submit(Callable<?> callable) {
        Flow previous = current();
        if (previous != null && !previous.isPassive()) {
            throw new IllegalStateException();
        }
        Flow flow = newFlow(previous);
        try {
            MethodFrame frame = flow.newFrame(callable, "call", "()Ljava/lang/Object;");
            frame.notifyInvoke(Integer.MAX_VALUE, 0, 0);
            flow.state = SUSPENDED;
            flow.currentFrame = null;
            flow.suspendedFrame = frame;
            flow.activate();
            return flow;
        } finally {
            current.set(previous);
        }
    }

    @FlowMethod(manual = true)
    public static Process process() {
        Flow current = Flow.fromInvoker();
        MethodFrame frame = current.currentFrame;
        try {
            return current.process;
        } finally {
            frame.invoked();
        }
    }

    @FlowMethod(manual = true)
    public static void joinProcess(Process process) {
        Flow current = Flow.fromInvoker();
        MethodFrame frame = current.currentFrame;
        try {
            if (process == null) {
                throw new NullPointerException();
            }
            if (current.process != null) {
                if (current.process == process) {
                    throw new IllegalStateException("Flow already belongs to specified process.");
                } else {
                    throw new IllegalStateException("Flow belongs to another process.");
                }
            }
            process.add(current);
        } finally {
            frame.invoked();
        }
    }

    @FlowMethod(manual = true)
    public static void leaveProcess() {
        Flow current = Flow.fromInvoker();
        MethodFrame frame = current.currentFrame;
        try {
            Process process = current.process;
            if (process == null) {
                throw new IllegalStateException("Flow does not belong to any process.");
            }
            process.remove(current);
        } finally {
            frame.invoked();
        }
    }

    @FlowMethod(manual = true)
    public static boolean forgetProcess() {
        Flow current = Flow.fromInvoker();
        MethodFrame frame = current.currentFrame;
        try {
            Process process = current.process;
            if (process == null) {
                return false;
            }
            process.remove(current);
            return true;
        } finally {
            frame.invoked();
        }
    }

    @FlowMethod(manual = true)
    public static FlowContext currentContext() {
        return currentContext(null);
    }

    @FlowMethod(manual = true)
    public static FlowContext currentContext(Object argument) {
        Flow current = Flow.fromInvoker();
        MethodFrame frame = current.currentFrame;
        try {
            if (frame.isInvoking()) {
                return new FlowContext(frame.copy(null), argument);
            }
            return null;
        } finally {
            frame.invoked();
        }
    }

    public static Object continueWith(FlowContext cont) {
        Flow flow = new Flow(FlowManager.getNext(), null);
        flow.setContext(cont);
        return flow.resume(cont);
    }

    public static Flow snapshot() {
        Flow current = Flow.fromInvoker();
        MethodFrame frame = current.currentFrame;
        try {
            if (frame.isInvoking()) {
                current.suspendInPlace();
                try {
                    return current.copy();
                } finally {
                    current.restoreInPlace();
                }
            }
            return null;
        } finally {
            frame.invoked();
        }
    }

    /**
     * Initiates concurrent execution on the invoker.
     * <p>
     * This method creates <i>n</i> {@linkplain #copy() shallow copies} of the
     * current flow, then executes each copy concurrently and starting from the
     * invocation point. In other words, this method is invoked once, but
     * returns 1+<i>n</i> times: 1 time for the invoker, and <i>n</i> times for
     * new flows. Notice that {@link #split(int) split(0)} is a no-effect
     * operation.
     * <p>
     * The returned value is an <code>int</code> that identifies the flow to
     * which the method returned. It will be <code>0</code> for the invoker, and
     * a distinct positive integer for new flows, ranging from <code>1</code> to
     * <i>n</i>.
     * <p>
     * The following example illustrates this behavior:
     *
     * <pre>
     *    void example() {
     *        System.out.println("Before doFlow()");
     *        int i = doFlow();
     *        System.out.printf("doFlow(): %d\n", i);
     *    }
     *
     *    &#064;{@link FlowMethod}
     *    int doFlow() {
     *        System.out.println("Before performSplit()");
     *        int i = performSplit();
     *        System.out.printf("performSplit(): %d\n", i);
     *        return i;
     *    }
     *
     *    &#064;{@link FlowMethod}
     *    int performSplit() {
     *        System.out.println("Before split(2)");
     *        int i = Flow.split(2);
     *        System.out.printf("Split result: %d\n", i);
     *        return i;
     *    }
     * </pre>
     * The above snippet prints the following (under fair scheduling
     * conditions):
     *
     * <pre>
     *     Before doFlow()
     *     Before performSplit()
     *     Before split(2)
     *     Split result: 0
     *     Split result: 1
     *     Split result: 2
     *     performSplit(): 0
     *     performSplit(): 1
     *     performSplit(): 2
     *     doFlow(): 0
     * </pre>
     * <p>
     * Each of the created flow runs on a possibly different {@linkplain Thread
     * thread} (the actual thread is defined by the {@linkplain #getManager()
     * flow manager}), and will be independent from all other flows, including
     * the invoker. As defined by the {@linkplain #copy() shallow copy}
     * behavior, all flows share heap objects referenced by the invoker stack
     * frames at the time of invocation.
     * <p>
     * If there is an active {@linkplain #fork(int) fork} at the time of
     * invocation, the invoker will remain on such fork, hence methods such as
     * {@link #merge()} and {@link #forgetFork()} are still valid for the
     * invoker. On the other hand, the newly created flows will be outside any
     * fork, which means that invoking {@link #merge()} and
     * {@link #forgetFork()} before an explicit fork on such flows will cause an
     * exception to be thrown.
     * <p>
     *
     * @param n The number of flows to create. Must be non-negative.
     * @throws IllegalArgumentException If n is negative.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @return The flow identifier. Zero for the invoker, and a distinct integer
     *         ranging from <code>1</code> to <code>n</code> for each new
     *         branch.
     * @see #fork(int)
     */
    @FlowMethod(manual = true)
    public static int split(int n) {
        Flow current = fromInvoker();
        MethodFrame frame = current.currentFrame;
        try {
            if (frame.isInvoking()) {
                current.suspendInPlace();
                try {
                    current.manager.fork(current, n);
                } finally {
                    current.restoreInPlace();
                }
            }
            Fork fork = current.currentFork;
            current.currentFork = fork.previous;
            return fork.number;
        } finally {
            frame.invoked();
        }
    }

    /**
     * Initiates fork on the invoker. A fork is a temporary work executed by
     * concurrent {@linkplain Flow flows}.
     * <p>
     * This method is similar to {@link #split(int)}. In addition to create new
     * flows, it makes the invoker and each of the created flows to run in the
     * context of a fork. The invoker will stay in the fork until it explicitly
     * {@linkplain #merge() merges} or {@linkplain #forgetFork() forgets}. As a
     * convention in this documentation, new flows created by this method are
     * called <i>branches</i>, and the flow that invokes this method is called
     * the <i>fork-creator</i>.
     * <p>
     * If this method is invoked during a previous fork, a nested fork is
     * established. This method changes the behavior of {@link #merge()} and
     * other fork-finishing methods, which always applies to the innermost fork.
     * Because of this, it is recommended to use the structured fork/merge
     * style, which use <code>try</code>/<code>finally</code> blocks as in the
     * following example:
     *
     * <pre>
     *    ... // Single-threaded execution.
     *    int branch = Flow.fork(n);
     *    try {
     *        ... // Concurrent execution by n threads.
     *    } finally {
     *        Flow.{@link #merge()};
     *    }
     *    ... // Single-threaded execution.
     * </pre>
     * <p>
     * <p>
     * The returned value is an <code>int</code> that identifies the branch to
     * which the method returned. It will be <code>0</code> for the invoker, and
     * a distinct positive integer for new branches, ranging from <code>1</code>
     * to <i>n</i>.
     * <p>
     * If parameter <i>n</i> is zero, no new flow will be instantiated and the
     * invoker will continue execution in single-threaded mode. Still, the next
     * fork-finishing operation will apply to such fork.
     * <p>
     *
     * @param n The number of branches to create. Must be non-negative.
     * @throws IllegalArgumentException If n is negative.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @return The branch identifier. Zero for the invoker, and a distinct
     *         integer ranging from <code>1</code> to <code>n</code> for each
     *         new branch.
     * @see #merge(long, TimeUnit)
     * @see #forgetFork()
     */
    @FlowMethod(manual = true)
    public static int fork(int n) {
        Flow current = fromInvoker();
        MethodFrame frame = current.currentFrame;
        try {
            if (frame.isInvoking()) {
                // We are performing a fork.
                current.suspendInPlace();
                try {
                    current.manager.fork(current, n);
                } finally {
                    current.restoreInPlace();
                }
            }
            return current.currentFork.number;
        } finally {
            frame.invoked();
        }
    }

    /**
     * Finishes a fork by restoring single-threaded execution. This method
     * causes the invoker to leave the current {@linkplain #fork(int) fork}.
     * Depending on who is the invoker, the behavior will be different.
     * <p>
     * <b>If the invoker is the fork-creator:</b> This method blocks until each
     * of the corresponding branches finish (see below), or until this thread is
     * interrupted, whichever comes first. If the merge is successful (all
     * branches have finished), the invoker leaves the fork, restoring the next
     * outer fork, if any. Otherwise (the thread is interrupted while at least
     * one branch is active), the invoker will stay in the fork. If all other
     * branches have finished before invocation of this method, it will return
     * immediately without checking the thread's interrupt flag.
     * <p>
     * <b>If the invoker is a branch:</b> This method does not return. It
     * finishes the flow immediately, as if {@link #leave()} were invoked.
     * Notice that even <code>finally</code> blocks will not execute.
     * <p>
     * This method considers only branches created by the last invocation of
     * {@link #fork(int)}.
     * <p>
     * A branch will be active (that is, it will not finish) until one of the
     * following happens:
     * <ul>
     * <li>The {@linkplain Flow flow creator} method completes normally or by
     * exception.</li>
     * <li>The branch calls {@link Flow#leave()}.</li>
     * <li>The branch calls a merge method, either {@link #merge()} or
     * {@link #merge(long, TimeUnit)}.</li>
     * <li>The branch forgets the fork by calling {@link #forgetFork()}.</li>
     * </ul>
     * A suspended branch is considered to be active. Hence, this method will
     * block the fork-creator even if all other branches are suspended. If this
     * happens on a real scenario, another thread must resume the branches,
     * which will allow them to finish normally, so this method can return to
     * the fork-creator.
     * <p>
     * Whenever this method returns normally, it is guaranteed that the invoker
     * was the fork-creator and no branch will be active.
     *
     * @throws InterruptedException If the current thread was interrupted while
     *         there was at least one active branch.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @see #fork(int)
     * @see #merge(long, TimeUnit)
     * @see #forgetFork()
     */
    @FlowMethod(manual = true)
    public static void merge() throws InterruptedException {
        Flow current = fromInvoker();
        MethodFrame frame = current.currentFrame;
        boolean leaving = false;
        try {
            Fork fork = current.currentFork;
            if (fork != null) {
                switch (fork.merge(0, null)) {
                    case Fork.LEAVE:
                        assert fork.previous == null;
                        current.currentFrame.leaveThread();
                        leaving = true;
                        return;
                    case Fork.MERGED:
                        current.currentFork = fork.previous;
                        return;
                    default:
                        throw new AssertionError();
                }
            }
            throw new IllegalStateException("No active fork.");
        } finally {
            if (!leaving) {
                frame.invoked();
            }
        }
    }

    /**
     * Attempts to finish a fork, or give-up after a timeout elapses. This
     * method is the time-constrained version of {@link #merge()}. Depending on
     * who is the invoker, the behavior will be different.
     * <p>
     * <b>If the invoker is the fork-creator:</b> This method blocks until each
     * of the corresponding branches finish (see {@link #merge()}), or until the
     * given timeout expires, or until this thread is interrupted, whichever
     * comes first. If the merge is successful (all branches have finished
     * before the timeout expire), the invoker leaves the fork, restoring the
     * next outer fork, if any, and this method returns <code>true</code>. If
     * the timeout expires while at least on branch is active, the invoker will
     * stay in the fork, and this method returns <code>false</code>. Otherwise
     * (the thread is interrupted while at least one branch is active), the
     * invoker will stay in the fork. If all other branches have finished before
     * invocation of this method, it will return immediately without checking
     * the thread's interrupt flag.
     * <p>
     * <b>If the invoker is a branch:</b> This method does not return. It
     * finishes the flow immediately, as if {@link #leave()} were invoked.
     * Notice that even <code>finally</code> blocks will not execute.
     * <p>
     * Whenever this method returns normally, it is guaranteed that the invoker
     * will be the fork-creator.
     *
     * @return <code>true</code> if all other branches have finished,
     *         <code>false</code> if there was at least one active branch when
     *         the timeout elapsed.
     * @throws InterruptedException If the current thread was interrupted during
     *         the wait branches to finish.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws NullPointerException If <code>unit</code> is null.
     * @see #fork(int)
     * @see #merge()
     * @see #forgetFork()
     */
    @FlowMethod(manual = true)
    public static boolean merge(long timeout, TimeUnit unit) throws InterruptedException {
        Flow current = fromInvoker();
        MethodFrame frame = current.currentFrame;
        boolean leaving = false;
        try {
            if (unit == null) {
                throw new NullPointerException();
            }
            Fork fork = current.currentFork;
            if (fork == null) {
                throw new IllegalStateException("No active fork.");
            }
            switch (fork.merge(timeout, unit)) {
                case Fork.LEAVE:
                    assert fork.previous == null;
                    current.currentFrame.leaveThread();
                    leaving = true;
                    return false;
                case Fork.MERGED:
                    current.currentFork = fork.previous;
                    return true;
                case Fork.TIMEOUT:
                    return false;
                default:
                    throw new AssertionError();
            }
        } finally {
            if (!leaving) {
                frame.invoked();
            }
        }
    }

    /**
     * Ignores the current fork, restoring the next outer fork, if any. This
     * method is used leave the current fork without waiting for, nor causing,
     * any branch to finish.
     * <p>
     * This method does not cause the current flow to leave, even if the invoker
     * is a branch. If it's necessary that only the fork-creator remains active,
     * one should call {@link #merge(long, TimeUnit) merge(0, TimeUnit.SECONDS)}
     * instead of this method. If the invoker is a branch, it will be considered
     * finished, and the flow will run "detached" from the fork, as if created
     * by {@link #split(int)}. This means that it might unblock an ongoing call
     * to {@link #merge()} in the fork-creator.
     *
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @see #fork(int)
     * @see #merge(long, TimeUnit)
     */
    @FlowMethod(manual = true)
    public static void forgetFork() {
        Flow current = fromInvoker();
        MethodFrame frame = current.currentFrame;
        try {
            Fork fork = current.currentFork;
            if (fork == null) {
                throw new IllegalStateException("No active fork.");
            }
            if (fork.unfork()) {
                current.currentFork = fork.previous;
            } else {
                assert fork.previous == null;
            }
        } finally {
            frame.invoked();
        }
    }

    @FlowMethod(manual = true)
    public static void waitFor(SelectableChannel channel, int ops) throws ClosedChannelException {
        Flow flow = fromInvoker();
        MethodFrame frame = flow.currentFrame;
        if (frame.isInvoking()) {

        }
        throw new AssertionError();

        //flow.manager.doWait(flow, channel, ops);
    }

    public static void replicate(int n) {
        throw new AssertionError("Pending implementation.");
    }

    @FlowMethod(manual = true)
    public static void leave() {
        Flow current = fromInvoker();
        MethodFrame frame = current.currentFrame;
        frame.leaveThread();
    }

    @FlowMethod(manual = true)
    public static void returnAndContinue() {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_VOID);
        if (frame != null) {
            frame.result();
        }
    }

    @FlowMethod(manual = true)
    public static void returnAndContinue(boolean v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_BOOLEAN);
        if (frame != null) {
            frame.result(v);
        }
    }

    @FlowMethod(manual = true)
    public static void returnAndContinue(char v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_CHAR);
        if (frame != null) {
            frame.result(v);
        }
    }

    @FlowMethod(manual = true)
    public static void returnAndContinue(byte v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_BYTE);
        if (frame != null) {
            frame.result(v);
        }
    }

    @FlowMethod(manual = true)
    public static void returnAndContinue(short v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_SHORT);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Causes a method to return an <code>int</code> while continuing
     * asynchronously.
     * <p>
     * This method is used to perform a local {@linkplain #split(int) split}
     * operation. In the current flow, the invoker returns the <code>int</code>
     * to its invoker, as if it were executed <code>return v</code> instead of
     * calling this method. The invoker method is resumed from the point of
     * invocation, in a new flow created by this method. The new flow appears to
     * have been started from the invoker method, and not from the original
     * {@linkplain FlowMethod flow-creator} as it would be if
     * {@link #split(int)} were used. The following snippet illustrates this
     * behavior:
     *
     * <pre>
     *    &#064;{@link FlowMethod}
     *    void example() {
     *        System.out.println("Before doFlow()");
     *        int i = doFlow();
     *        System.out.printf("doFlow(): %d\n", i);
     *        Thread.sleep(50);
     *        System.out.println("Done");
     *    }
     *
     *    &#064;{@link FlowMethod}
     *    int doFlow() {
     *        System.out.println("Before returnAndContinue()");
     *        Flow.returnAndContinue(123);
     *        System.out.println("After returnAndContinue()");
     *        return 456;
     *    }
     *
     * </pre>
     * The above snippet prints the following (under fair scheduling
     * conditions):
     *
     * <pre>
     *     Before doFlow()
     *     Before returnAndContinue()
     *     doFlow(): 123
     *     After returnAndContinue()
     *     Done
     * </pre>
     * <p>
     * Notice that the new flow finishes when the method <code>doFlow()</code>
     * finishes. Hence the value of the actual <code>return</code> statement (
     * <code>456</code> in the example) is discarded. Calling this method more
     * than once in the same method works, but is redundant because in the
     * second and subsequent calls, the work doesn't need to be finished in a
     * new flow.
     *
     * @param v The value to return to the invoker's invoker.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(int v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_INT);
        if (frame != null) {
            frame.result(v);
        }
    }

    @FlowMethod(manual = true)
    public static void returnAndContinue(long v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_LONG);
        if (frame != null) {
            frame.result(v);
        }
    }

    @FlowMethod(manual = true)
    public static void returnAndContinue(float v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_FLOAT);
        if (frame != null) {
            frame.result(v);
        }
    }

    @FlowMethod(manual = true)
    public static void returnAndContinue(double v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_DOUBLE);
        if (frame != null) {
            frame.result(v);
        }
    }

    @FlowMethod(manual = true)
    public static void returnAndContinue(Object v) {
        // Use ';' because an object descriptor is "L<package>/<class>;" .
        MethodFrame frame = forkAndReturn(';');
        if (frame != null) {
            frame.result(v);
        }
    }

    private static MethodFrame forkAndReturn(char type) {
        Flow current = fromInvoker();
        MethodFrame frame = current.currentFrame;
        try {
            current.currentFrame.checkResultType(type);
            if (frame.isInvoking()) {
                // We are performing a fork.
                Flow copy;
                current.suspendInPlace();
                try {
                    copy = current.shallowCopy();
                } finally {
                    current.restoreInPlace();
                }
                copy.activate();
                return frame;
            } else {
                // We are restoring from a fork.
                return null;
            }
        } finally {
            frame.invoked();
        }
    }

    @FlowMethod
    public static Object suspend() {
        return signal(new SuspendSignal(null));
    }

    @FlowMethod
    public static Object suspend(Object message) {
        return signal(new SuspendSignal(message));
    }

    /**
     * Suspends the flow and sends a signal to the flow-controller.
     * <p>
     * This method first suspends the current flow at the point of invocation.
     * Then it sends the specified signal to the {@linkplain Flow
     * flow-controller}, as if the signal were thrown by the {@linkplain Flow
     * flow-creator} (see below). After this, the next actions are fully
     * determined by the flow-controller, which typically uses the signal to
     * decide.
     * <p>
     * This method returns only when the flow-controller invokes a
     * <code>resume</code> method (see below) on this flow or on a copy. Notice
     * that this method may complete on a different flow and it may also
     * complete more than once, at the discretion of the flow-controller.
     * <p>
     * Although the signal is an exception, this method doesn't throw the signal
     * on the current flow. The signal is thrown by the subsystem after the flow
     * have been suspended. To the flow-controller, the signal appears to have
     * been thrown by the flow-creator. This mechanism is illustrated in the
     * following snippet:
     *
     * <pre>
     *    void example() { // This is the flow-controller.
     *        try {
     *            doFlow();
     *            System.out.println("doFlow() finished");
     *        } catch (FlowSignal signal) {
     *            System.out.println("Caught by the flow-controller");
     *            System.out.println("Calling Flow.resume()");
     *            signal.getFlow().{@link #resume()};
     *            System.out.println("Flow finished");
     *        }
     *    }
     *
     *    &#064;{@link FlowMethod}
     *    void doFlow() { // This is the flow-creator.
     *        try {
     *            doSignal();
     *            System.out.println("doSignal() finished");
     *        } catch (FlowSignal signal) {
     *            System.out.println("Caught by the flow-creator");
     *        }
     *    }
     *
     *    &#064;{@link FlowMethod}
     *    void doSignal() { // This is an ordinary flow method.
     *        try {
     *            System.out.println("Sending signal");
     *            FlowSignal signal = new MyFlowSignal();
     *            Flow.signal(signal);
     *            System.out.println("Returned from signal (resumed)");
     *        } catch (FlowSignal signal) {
     *            System.out.println("Caught by doSignal()");
     *        }
     *    }
     *
     * </pre>
     * The above snippet prints the following:
     *
     * <pre>
     *     Sending signal
     *     Caught by the flow-controller
     *     Calling Flow.resume()
     *     Returned from signal (resumed)
     *     doSignal() finished
     *     Flow finished
     * </pre>
     * The flow-controller can, among other actions, create a
     * {@linkplain #copy() shallow copy} of this flow, serialize this flow and
     * deserialize in another machine instance, ignore the signal and never
     * {@linkplain #resume() resume}, or wait for some specific condition before
     * resuming. To provide satisfactory usability, the flow-controller must
     * proceed as specified by the received {@linkplain FlowSignal signal}
     * object. There are some well-known signal classes such as
     * {@link DelayedCallSignal} and {@link SuspendSignal}, but developers are
     * free to create new signals, as long as they provide support for them in
     * the flow-controller.
     * <p>
     * The completion of this method depends on the chosen <code>resume</code>
     * method:
     * <ul>
     * <li>If the flow is resumed with {@link #resume()}, this method returns
     * <code>null</code>.</li>
     * <li>If the flow is resumed with {@link #resume(Object)}, this method
     * returns the object passed to the <code>resume</code> method.</li>
     * <li>If the flow is resumed with {@link #resumeThrowing(Throwable)}, this
     * method throws {@link ResumeException} whose
     * {@linkplain Throwable#getCause() cause} is the exception passed to the
     * <code>resumeThrowing</code> method.</li>
     * </ul>
     * <p>
     * This method is used to implement utilities such as {@link #suspend()} and
     * {@link ThreadFreeLock}. It is the lowest level API for implementing features
     * such as releasing a pooled thread before completion and serializing
     * thread state for long running processes.
     *
     * @param signal The signal that will be sent to the flow-controller.
     * @return The object passed to {@link #resume(Object)} method.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws NullPointerException If <code>signal</code> is <code>null</code>.
     * @throws ResumeException If the flow is resuming through
     *         {@link #resumeThrowing(Throwable)}.
     */
    @FlowMethod(manual = true)
    public static Object signal(FlowSignal signal) {
        Flow current = fromInvoker();
        MethodFrame frame = current.currentFrame;
        try {
            if (frame.isInvoking()) {
                // We are suspending.
                log("Signaling, signal=" + signal);
                if (signal == null) {
                    throw new NullPointerException();
                }
                current.state = SUSPENDING;
                current.suspendedFrame = frame.copy(current);
                current.result = signal;
                signal.flow = current;
                frame.leaveThread();
                return null;
            } else {
                // We are restoring from suspended state.
                log("Restored from signal.");
                assert signal.flow == current : signal.flow;
                Object result = current.result;
                current.result = null;
                if (result instanceof ExceptionEnvelope) {
                    Throwable exception = ((ExceptionEnvelope) result).exception;
                    throw new ResumeException(exception);
                }
                return result;
            }
        } finally {
            frame.invoked();
        }
    }

    public static Flow current() {
        return current.get();
    }

    public static Flow fromInvoker() {
        Flow ret = current();
        if (ret != null) {
            MethodFrame frame = ret.currentFrame;
            if (frame != null && (frame.isInvoking() || frame.isRestoring())) {
                return ret;
            }
        }
        throw new IllegalStateException("Invalid operation: no current flow.");
    }

    public static Flow safeCurrent() {
        Flow ret = current.get();
        if (ret == null) {
            throw new IllegalStateException("No current flow.");
        }
        return ret;
    }

    public static void log(String msg) {
        if (true) {
            return;
        }
        synchronized(System.out) {
            System.out.print('[');
            System.out.print(Thread.currentThread().getName());
            System.out.print(',');
            System.out.print(Flow.current());
            System.out.print(',');
            StackTraceElement[] st = new Exception().getStackTrace();
            System.out.print(st[1].toString());
            System.out.print("] ");
            System.out.println(msg);
        }
    }

    static void clearThread() {
        current.set(null);
    }

    static MethodFrame enter(Object owner, String name, String desc) {
        Flow cur = current();
        if (cur == null || cur.isPassive()) {
            cur = newFlow(cur);
        }
        return cur.newFrame(owner, name, desc);
    }

    static Object getLocal(FlowLocal local) {
        return safeCurrent().doGetLocal(local);
    }

    static Object setLocal(FlowLocal local, Object value) {
        return safeCurrent().doSetLocal(local, value);
    }

    static Object removeLocal(FlowLocal local) {
        return safeCurrent().doRemoveLocal(local);
    }

    private static void setCurrent(Flow flow) {
        if (flow == null) {
            throw new NullPointerException();
        }
        assert flow.previous == null;
        flow.previous = current.get();
        current.set(flow);
    }

    private static Flow newFlow(Flow previous) {
        Flow ret = new Flow(FlowManager.getNext(), previous);
        ret.state = ACTIVE;
        current.set(ret);
        return ret;
    }

    private transient Flow previous;
    private final FlowManager manager;
    transient Process process;
    private int state;
    private MethodFrame currentFrame;
    private MethodFrame suspendedFrame;
    transient Fork currentFork;
    private Object result;
    private WeakHashMap<FlowLocal, Object> locals;

    private Flow(FlowManager manager, Flow previous) {
        this.manager = manager;
        this.previous = previous;
        state = FINISHED;
    }

    public FlowManager getManager() {
        return manager;
    }

    public Flow getPrevious() {
        return previous;
    }

    public boolean isActive() {
        return state == ACTIVE;
    }

    public boolean isSuspended() {
        return state == SUSPENDED;
    }

    public boolean isFinished() {
        return state == FINISHED;
    }

    public Object getResult() {
        return result;
    }

    public void setContext(FlowContext cont) {
        switch (state) {
            case FINISHED:
                suspendedFrame = cont.fetchFrame();
                state = SUSPENDED;
                if (process != null) {
                    Process process = this.process;
                    this.process = null;
                    process.add(this);
                }
                break;
            case SUSPENDED:
                suspendedFrame = cont.fetchFrame();
                break;
            default:
                throw new IllegalStateException("Cannot set continuation while flow is " + stateNames[state] + '.');
        }
    }

    public Object resume() {
        return resume(null);
    }

    public Object resumeThrowing(Throwable exception) {
        if (exception == null) {
            throw new NullPointerException();
        }
        return resume(new ExceptionEnvelope(exception));
    }

    public Object resume(Object result) {
        if (state != SUSPENDED) {
            throw new IllegalStateException("Cannot resume if the flow is " + stateNames[state] + '.');
        }
        boolean success = false;
        state = ACTIVE;
        try {
            restore();
            setCurrent(this);
            this.result = result;
            Class clazz = getRootClass();

            try {
                Class[] argClasses = getRootParameterTypes();
                Object[] argValues = new Object[argClasses.length];

                /* TODO:
                 *   For now, invokes the root BookKeep method; in the future, the enhancer
                 *   should add a synthetic static method to the root class. This synthetic
                 *   method will invoke the root BookKeep method passing primitive argument
                 *   values obtained directly through the MethodFrame. This will save
                 *   reflection and boxing costs, and will remove visibility problems.
                 */
                NoSuchMethodException fe = null;
                for (;;) {
                    try {
                        Method m = clazz.getDeclaredMethod(getRootName(), argClasses);
                        m.setAccessible(true);
                        Object owner = getRootValues(m, argValues);
                        Object ret = m.invoke(owner, argValues);
                        success = true;
                        return ret;
                    } catch (NoSuchMethodException e) {
                        if (fe == null) {
                            fe = e;
                        }
                        clazz = clazz.getSuperclass();
                        if (clazz == null) {
                            throw fe;
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                }
                if (e.getCause() instanceof Error) {
                    throw (Error) e.getCause();
                }
                throw new RuntimeException(e);
            }
        } finally {
            if (success) {
                assert state == FINISHED || state == SUSPENDED : "state==" + state;
            }
        }
    }

    public Future<?> activate() {
        return activate(null);
    }

    public Future<?> activateThrowing(Throwable exception) {
        if (exception == null) {
            throw new NullPointerException();
        }
        return activate(new ExceptionEnvelope(exception));
    }

    public Future<?> activate(Object result) {
        return manager.submit(this, result);
    }

    /**
     * Performs a shallow copy on this flow. A shallow copy is a simple copy of
     * stack frames, with all local and stack variables, starting from the
     * {@linkplain Flow flow-creator} method, and until the current position in
     * the flow's execution. The value of all variables are copied by simple
     * assignment, including references. Because no heap object is ever cloned
     * or serialized by this method, this flow and the copy will share heap
     * objects referenced by the copied stack variables.
     * <p>
     * If this flow is suspended, the returned copy can be
     * {@linkplain #resume() resumed} and it will run just after the suspend
     * invocation, as if it were suspended by itself. This process does not
     * affect the current flow, which was only the source in a copy operation.
     * Many copies can be created from a single flow, which allows
     * implementation of utilities such as {@link #split(int)} and
     * {@link EventPicker}.
     * <p>
     * The target flow must be either suspended or finished, and hence this
     * method cannot be used to copy a running flow. To get the state of a
     * running flow, use {@link #currentContext()}.
     * <p>
     *
     * @return A flow whose execution context is identical to this flow, sharing
     *         all heap objects referenced by stack frames.
     * @throws IllegalStateException If this flow is not suspended nor finished.
     */
    public Flow copy() {
        checkSteady();
        Flow ret = new Flow(manager, null);
        ret.state = state;
        ret.suspendedFrame = suspendedFrame.copy(ret);
        ret.result = result;
        if (process != null) {
            process.add(ret);
            assert ret.process == process;
        }
        return ret;
    }

    public Flow shallowCopy() {
        checkSteady();
        Flow ret = new Flow(manager, null);
        ret.state = state;
        ret.suspendedFrame = suspendedFrame.shallowCopy(ret);
        ret.result = result;
        if (process != null) {
            process.add(ret);
            assert ret.process == process;
        }
        return ret;
    }

    private void checkSteady() {
        switch (state) {
            case SUSPENDED:
                assert suspendedFrame != null;
                break;
            case FINISHED:
                assert suspendedFrame == null;
                break;
            default:
                throw new IllegalStateException("Cannot copy flow in current state.");
        }
        assert currentFrame == null;
    }

    public Flow streamedCopy() {
        try {
            PublicByteArrayOutputStream baos = new PublicByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(this);
            oos.close();
            PublicByteArrayInputStream bais = new PublicByteArrayInputStream(baos.getBuffer(), 0, baos.size());
            ObjectInputStream ois = new ObjectInputStream(bais);
            Object ret = ois.readObject();
            ois.close();
            return (Flow) ret;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public Class getRootClass() {
        Object owner = suspendedFrame.target;
        return owner instanceof Class ? (Class) owner : owner.getClass();
    }

    public String getRootName() {
        return suspendedFrame.name;
    }

    public String getRootDesc() {
        return suspendedFrame.desc;
    }

    public Class[] getRootParameterTypes() throws ClassNotFoundException {
        Type[] argTypes = Type.getArgumentTypes(suspendedFrame.desc);
        return Types.typeToClass(argTypes);
    }

    public synchronized Object join() throws InterruptedException {
        while (state != FINISHED) {
            wait();
        }
        return result;
    }

    public synchronized Object waitSuspended() throws InterruptedException {
        while (state != SUSPENDED) {
            wait();
        }
        return result;
    }

    public synchronized Object waitNotRunning() throws InterruptedException {
        while (state != SUSPENDED && state != FINISHED) {
            wait();
        }
        return result;
    }

    void finish() {
        assert current.get() == this;
        current.set(previous);
        previous = null;
        switch (state) {
            case ACTIVE:
                assert suspendedFrame == null;
                Fork fork = currentFork;
                while (fork != null) {
                    if (fork.number > 0) {
                        assert fork.previous == null;
                        fork.finished();
                        break;
                    }
                    fork = fork.previous;
                }
                Process process = this.process;
                if (process != null) {
                    process.remove(this);
                    // We still reference the process, for information issues and to
                    // go back to process if this finished flow is resumed.
                    this.process = process;
                }
                synchronized(this) {
                    state = FINISHED;
                    currentFrame = null;
                    notifyAll();
                }
                return;
            case SUSPENDING:
                assert suspendedFrame != null;
                FlowSignal signal = (FlowSignal) result;
                synchronized(this) {
                    state = SUSPENDED;
                    currentFrame = null;
                    result = signal.getArgument();
                    notifyAll();
                }
                throw signal;
            default:
                throw new AssertionError("state == " + state);
        }
    }

    MethodFrame newFrame(Object owner, String name, String desc) {
        assert state == ACTIVE;
        if (currentFrame == null) {
            if (suspendedFrame != null) {
                suspendedFrame.checkMatch(owner, name, desc);
                currentFrame = suspendedFrame;
                suspendedFrame = null;
            } else {
                currentFrame = new MethodFrame(this, owner, name, desc);
            }
        } else {
            assert suspendedFrame == null;
            currentFrame = currentFrame.newFrame(owner, name, desc);
        }
        return currentFrame;
    }

    void setCurrentFrame(MethodFrame frame) {
        currentFrame = frame;
    }

    Fork currentFork() {
        return currentFork;
    }

    void setCurrentFork(Fork fork) {
        currentFork = fork;
    }

    private void suspendInPlace() {
        assert state == ACTIVE;
        assert currentFrame != null;
        assert suspendedFrame == null;
        state = SUSPENDED;
        suspendedFrame = currentFrame;
        currentFrame = null;
    }

    private void restoreInPlace() {
        assert state == SUSPENDED;
        assert currentFrame == null;
        assert suspendedFrame != null;
        state = ACTIVE;
        currentFrame = suspendedFrame;
        suspendedFrame = null;
    }

    private void restore() {
        MethodFrame frame = suspendedFrame;
        assert frame.state == MethodFrame.INVOKING;
        assert frame.resumePoint > 0;
        MethodFrame next = null;
        for (;;) {
            frame.flow = this;
            frame.state = MethodFrame.RESTORING;
            frame.next = next;
            if (frame.prior == null) {
                break;
            }
            next = frame;
            frame = frame.prior;
            assert frame.state == MethodFrame.INVOKING;
            assert frame.resumePoint > 0;
        }
        suspendedFrame = frame;
        currentFrame = null;
    }

    private boolean isPassive() {
        return currentFrame != null && currentFrame.isActive();
    }

    private Object getRootValues(Method m, Object[] argValues) {
        Class<?>[] argClasses = m.getParameterTypes();
        int pv = 0;
        int ov = 0;
        for (int i = 0; i < argClasses.length; ++i) {
            Class argClass = argClasses[i];
            if (argClass == boolean.class) {
                argValues[i] = suspendedFrame.getBoolean(pv++);
            } else if (argClass == char.class) {
                argValues[i] = suspendedFrame.getChar(pv++);
            } else if (argClass == byte.class) {
                argValues[i] = suspendedFrame.getByte(pv++);
            } else if (argClass == short.class) {
                argValues[i] = suspendedFrame.getShort(pv++);
            } else if (argClass == int.class) {
                argValues[i] = suspendedFrame.getInt(pv++);
            } else if (argClass == long.class) {
                argValues[i] = suspendedFrame.getLong(pv);
                pv += 2;
            } else if (argClass == float.class) {
                argValues[i] = suspendedFrame.getFloat(pv++);
            } else if (argClass == double.class) {
                argValues[i] = suspendedFrame.getDouble(pv);
                pv += 2;
            } else {
                argValues[i] = suspendedFrame.getObject(ov++);
            }
        }
        Object owner;
        if (Modifier.isStatic(m.getModifiers())) {
            owner = null;
        } else {
            owner = suspendedFrame.target;
        }
        return owner;
    }

    private Object doGetLocal(FlowLocal local) {
        WeakHashMap<FlowLocal, Object> map = getLocals();
        Object ret = map.get(local);
        if (ret != null) {
            return ret;
        }
        if (!map.containsKey(local)) {
            ret = local.initialValue();
            map.put(local, ret);
        }
        return ret;
    }

    private Object doSetLocal(FlowLocal local, Object value) {
        return getLocals().put(local, value);
    }

    private Object doRemoveLocal(FlowLocal local) {
        if (locals != null) {
            return null;
        }
        return locals.remove(local);
    }

    private WeakHashMap<FlowLocal, Object> getLocals() {
        if (locals == null) {
            locals = new WeakHashMap<FlowLocal, Object>();
        }
        return locals;
    }

    // TODO: Add wrappers for java.lang.Thread??? Consider static and instance methods.

}
