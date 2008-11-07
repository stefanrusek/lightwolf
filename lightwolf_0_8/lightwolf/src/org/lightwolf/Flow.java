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
 * <li>A flow can wait for a {@linkplain ThreadFreeLock lock} on a resource, or
 * for the {@linkplain #waitComplete() completion} of an I/O operation, without
 * consuming a Java thread meanwhile, and thus releasing a pooled thread for
 * increased concurrency.</li>
 * <li>Flows can use utilities such as {@link Continuation}, {@link #fork(int)},
 * and {@link #returnAndContinue()}.</li>
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
 * creation of a new flow (this is done automatically). The flow ends when the
 * flow-creator completes normally or by exception. If the flow-creator calls
 * itself or another flow-method, no new flow is created, as described above.
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
 * fork. When the nested flow ends or is suspended, the outer flow becomes
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
    public static final int ENDED = 4;

    private static final String[] stateNames = new String[] { "0", "ACTIVE", "SUSPENDED", "SUSPENDING", "ENDED" };

    private static final ThreadLocal<Flow> current = new ThreadLocal<Flow>();

    /**
     * Creates and returns a new flow. The new flow will be in {@link #ENDED}
     * state, which is suitable to be passed as argument to methods such as
     * {@link Continuation#resume(Flow)}.
     * 
     * @return A newly created flow instance, in {@link #ENDED} state.
     */
    public static Flow newFlow() {
        return new Flow(FlowManager.getNext(), null);
    }

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
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            return cur.process;
        } finally {
            frame.invoked();
        }
    }

    @FlowMethod(manual = true)
    public static void joinProcess(Process process) {
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            if (process == null) {
                throw new NullPointerException();
            }
            if (cur.process != null) {
                if (cur.process == process) {
                    throw new IllegalStateException("Flow already belongs to specified process.");
                }
                throw new IllegalStateException("Flow belongs to another process.");
            }
            process.add(cur);
        } finally {
            frame.invoked();
        }
    }

    @FlowMethod(manual = true)
    public static void leaveProcess() {
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            Process process = cur.process;
            if (process == null) {
                throw new IllegalStateException("Flow does not belong to any process.");
            }
            process.remove(cur);
        } finally {
            frame.invoked();
        }
    }

    @FlowMethod(manual = true)
    public static boolean forgetProcess() {
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            Process process = cur.process;
            if (process == null) {
                return false;
            }
            process.remove(cur);
            return true;
        } finally {
            frame.invoked();
        }
    }

    public static Flow snapshot() {
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            if (frame.isInvoking()) {
                cur.suspendInPlace();
                try {
                    return cur.copy();
                } finally {
                    cur.restoreInPlace();
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
     *        System.out.println(&quot;Before doFlow()&quot;);
     *        int i = doFlow();
     *        System.out.printf(&quot;doFlow(): %d\n&quot;, i);
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    int doFlow() {
     *        System.out.println(&quot;Before performSplit()&quot;);
     *        int i = performSplit();
     *        System.out.printf(&quot;performSplit(): %d\n&quot;, i);
     *        return i;
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    int performSplit() {
     *        System.out.println(&quot;Before split(2)&quot;);
     *        int i = Flow.split(2);
     *        System.out.printf(&quot;Split result: %d\n&quot;, i);
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
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            if (frame.isInvoking()) {
                cur.suspendInPlace();
                try {
                    cur.manager.fork(cur, n);
                } finally {
                    cur.restoreInPlace();
                }
            }
            Fork fork = cur.currentFork;
            cur.currentFork = fork.previous;
            return fork.number;
        } finally {
            frame.invoked();
        }
    }

    /**
     * Starts a fork on the invoker. A fork is a temporary work executed by
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
     * other fork-ending methods, which always applies to the innermost fork.
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
     * fork-ending operation will apply to such fork.
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
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            if (frame.isInvoking()) {
                // We are performing a fork.
                cur.suspendInPlace();
                try {
                    cur.manager.fork(cur, n);
                } finally {
                    cur.restoreInPlace();
                }
            }
            return cur.currentFork.number;
        } finally {
            frame.invoked();
        }
    }

    /**
     * Ends a fork by restoring single-threaded execution. This method causes
     * the invoker to exit the current {@linkplain #fork(int) fork}. Depending
     * on who is the invoker, the behavior will be different.
     * <p>
     * <b>If the invoker is the fork-creator:</b> This method blocks until each
     * of the corresponding branches ends (see below), or until this thread is
     * interrupted, whichever comes first. If the merge is successful (all
     * branches have ended), the invoker exits the fork, restoring the next
     * outer fork, if any. Otherwise (the thread is interrupted while at least
     * one branch is active), the invoker will stay in the fork. If all other
     * branches have ended before invocation of this method, it will return
     * immediately without checking the thread's interrupt flag.
     * <p>
     * <b>If the invoker is a branch:</b> This method does not return. It ends
     * the flow immediately, as if {@link #end()} were invoked. Notice that even
     * <code>finally</code> blocks will not execute.
     * <p>
     * This method considers only branches created by the last invocation of
     * {@link #fork(int)}.
     * <p>
     * A branch will be active (that is, it will not end) until one of the
     * following happens:
     * <ul>
     * <li>The {@linkplain Flow flow creator} method completes normally or by
     * exception.</li>
     * <li>The branch calls {@link Flow#end()}.</li>
     * <li>The branch calls a merge method, either {@link #merge()} or
     * {@link #merge(long, TimeUnit)}.</li>
     * <li>The branch forgets the fork by calling {@link #forgetFork()}.</li>
     * <li>The branch ends the fork by calling {@link #endFork()}.</li>
     * </ul>
     * A suspended branch is considered to be active. Hence, this method will
     * block the fork-creator even if all other branches are suspended. If this
     * happens on a real scenario, another thread must resume the branches,
     * which will allow them to end normally, so this method can return to the
     * fork-creator.
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
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            Fork fork = cur.currentFork;
            if (fork != null) {
                switch (fork.merge(0, null)) {
                    case Fork.EXIT:
                        assert fork.previous == null;
                        cur.currentFrame.leaveThread();
                        return;
                    case Fork.MERGED:
                        cur.currentFork = fork.previous;
                        return;
                    default:
                        throw new AssertionError();
                }
            }
            throw new IllegalStateException("No active fork.");
        } finally {
            frame.invoked();
        }
    }

    /**
     * Attempts to end a fork, or give-up after a timeout elapses. This method
     * is the time-constrained version of {@link #merge()}. Depending on who is
     * the invoker, the behavior will be different.
     * <p>
     * <b>If the invoker is the fork-creator:</b> This method blocks until each
     * of the corresponding branches ends (see {@link #merge()}), or until the
     * given timeout expires, or until this thread is interrupted, whichever
     * comes first. If the merge is successful (all branches have ended before
     * the timeout expire), the invoker exits the fork, restoring the next outer
     * fork, if any, and this method returns <code>true</code>. If the timeout
     * expires while at least on branch is active, the invoker will stay in the
     * fork, and this method returns <code>false</code>. Otherwise (the thread
     * is interrupted while at least one branch is active), the invoker will
     * stay in the fork. If all other branches have ended before invocation of
     * this method, it will return immediately without checking the thread's
     * interrupt flag.
     * <p>
     * <b>If the invoker is a branch:</b> This method does not return. It ends
     * the flow immediately, as if {@link #end()} were invoked. Notice that even
     * <code>finally</code> blocks will not execute.
     * <p>
     * Whenever this method returns normally, it is guaranteed that the invoker
     * will be the fork-creator.
     * 
     * @return <code>true</code> if all other branches have ended,
     *         <code>false</code> if there was at least one active branch when
     *         the timeout elapsed.
     * @throws InterruptedException If the current thread was interrupted during
     *         the wait for branches to end.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws NullPointerException If <code>unit</code> is null.
     * @see #fork(int)
     * @see #merge()
     * @see #forgetFork()
     */
    @FlowMethod(manual = true)
    public static boolean merge(long timeout, TimeUnit unit) throws InterruptedException {
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            if (unit == null) {
                throw new NullPointerException();
            }
            Fork fork = cur.currentFork;
            if (fork == null) {
                throw new IllegalStateException("No active fork.");
            }
            switch (fork.merge(timeout, unit)) {
                case Fork.EXIT:
                    assert fork.previous == null;
                    cur.currentFrame.leaveThread();
                    return false;
                case Fork.MERGED:
                    cur.currentFork = fork.previous;
                    return true;
                case Fork.TIMEOUT:
                    return false;
                default:
                    throw new AssertionError();
            }
        } finally {
            frame.invoked();
        }
    }

    /**
     * Forgets the current fork without waiting for any branch. This method is
     * used to exit the current fork without waiting for, nor causing, any
     * branch to end.
     * <p>
     * If the invoker is the fork-creator, this method exits the fork and
     * restores the next outer fork, if any, and then immediately returns. If
     * the invoker is a branch, the flow will continue to run on the same
     * thread, but "detached" from the fork, as if created by
     * {@link #split(int)}. Therefore the fork-creator will consider this branch
     * as ended, which means that it might unblock an ongoing call to
     * {@link #merge()} in the fork-creator.
     * <p>
     * This method does not cause the current flow to end, even if the invoker
     * is a branch. If you need this, use {@link #endFork()} instead.
     * 
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @see #fork(int)
     * @see #merge(long, TimeUnit)
     * @see #endFork()
     */
    @FlowMethod(manual = true)
    public static void forgetFork() {
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            Fork fork = cur.currentFork;
            if (fork == null) {
                throw new IllegalStateException("No active fork.");
            }
            if (fork.unfork()) {
                cur.currentFork = fork.previous;
            } else {
                assert fork.previous == null;
            }
        } finally {
            frame.invoked();
        }
    }

    /**
     * Ends the current fork without waiting for any branch. This method is used
     * to exit the current fork without waiting for any branch to end.
     * <p>
     * If the invoker is the fork-creator, this method exits the fork and
     * restores the next outer fork, if any, and then immediately returns. If
     * the invoker is a branch, it will end the flow immediately, as if
     * {@link #end()} were invoked. This means that it might unblock an ongoing
     * call to {@link #merge()} in the fork-creator.
     * 
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @see #fork(int)
     * @see #merge(long, TimeUnit)
     * @see #forgetFork()
     */
    @FlowMethod(manual = true)
    public static void endFork() {
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            Fork fork = cur.currentFork;
            if (fork == null) {
                throw new IllegalStateException("No active fork.");
            }
            if (fork.unfork()) {
                cur.currentFork = fork.previous;
            } else {
                assert fork.previous == null;
                frame.leaveThread();
            }
        } finally {
            frame.invoked();
        }
    }

    @FlowMethod(manual = true)
    public static void waitFor(SelectableChannel channel, int ops) {
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

    /**
     * Ends the current flow. This method does not return. It causes execution
     * to continue after the {@linkplain Flow flow-creator}. The following
     * sample illustrates this behavior:
     * 
     * <pre>
     *    void example() {
     *        System.out.println(&quot;Before doFlow()&quot;);
     *        int i = doFlow();
     *        System.out.printf(&quot;doFlow(): %d\n&quot;, i);
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    int doFlow() {
     *        System.out.println(&quot;Before end()&quot;);
     *        Flow.end();
     *        System.out.println(&quot;After end()&quot;);
     *        return 5;
     *    }
     * 
     * </pre>
     * 
     * The above snippet prints the following:
     * 
     * <pre>
     *     Before doFlow()
     *     Before end()
     *     doFlow(): 0
     * </pre>
     * <p>
     * This method causes the flow-creator to return a "zero" value
     * corresponding to its return type:
     * <ul>
     * <li><b>Reference-type:</b> returns <code>null</code>.</li>
     * <li><b>Numeric-type:</b> returns <code>0</code>.</li>
     * <li><b>boolean:</b> returns <code>false</code>.</li>
     * <li><b>char:</b> returns <code>(char) 0</code>.</li>
     * </ul>
     * <p>
     * If the current flow was created by an utility such as {@link #split(int)}
     * or {@link #returnAndContinue()}, this method never returns and simply
     * releases the current thread.
     * 
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     */
    @FlowMethod(manual = true)
    public static void end() {
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        frame.leaveThread();
    }

    /**
     * Performs <code>return</code> while continuing asynchronously.
     * <p>
     * This method is used to perform a local {@linkplain #split(int) split}
     * operation. In the current flow, it works as if <code>return</code> were
     * executed. In a newly created flow, the invoker is resumed from the point
     * of invocation. The new flow starts from the invoker method, and not from
     * the original {@linkplain FlowMethod flow-creator} as it would be if
     * {@link #split(int)} were used. The following example illustrates this
     * behavior:
     * 
     * <pre>
     *    &#064;{@link FlowMethod}
     *    void example() {
     *        System.out.println(&quot;Before doFlow()&quot;);
     *        doFlow();
     *        System.out.println(&quot;After doFlow()&quot;);
     *        Thread.sleep(50); // Schedule the new flow's thread. 
     *        System.out.println(&quot;Done&quot;);
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    void doFlow() {
     *        System.out.println(&quot;Before returnAndContinue()&quot;);
     *        Flow.returnAndContinue();
     *        System.out.println(&quot;After returnAndContinue()&quot;);
     *    }
     * 
     * 
     * </pre>
     * <p>
     * The above example prints the following:
     * 
     * <pre>
     *     Before doFlow()
     *     Before returnAndContinue()
     *     After doFlow()
     *     After returnAndContinue()
     *     Done
     * </pre>
     * <p>
     * Notice that the new flow ends when the method <code>doFlow()</code> ends.
     * Calling this method more than once in the same method works, but is
     * redundant because in the second and subsequent calls, the work doesn't
     * need to be done in a new flow.
     * <p>
     * This method must be called only by a <code>void</code> method. There is
     * one version of this method for each possible return value.
     * 
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>void</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue() {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_VOID);
        if (frame != null) {
            frame.result();
        }
    }

    /**
     * Performs <code>return</code> <i>boolean-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>boolean</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>boolean</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(boolean v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_BOOLEAN);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>char-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>char</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>char</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(char v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_CHAR);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>byte-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>byte</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>byte</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(byte v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_BYTE);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>short-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>short</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>short</code> method.
     */
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
     * operation. In the current flow, it works as if <code>return v</code> were
     * executed. In a newly created flow, the invoker is resumed from the point
     * of invocation. The new flow starts from the invoker method, and not from
     * the original {@linkplain FlowMethod flow-creator} as it would be if
     * {@link #split(int)} were used. The following example illustrates this
     * behavior:
     * <p>
     * 
     * <pre>
     *    &#064;{@link FlowMethod}
     *    void example() {
     *        System.out.println(&quot;Before doFlow()&quot;);
     *        int i = doFlow();
     *        System.out.printf(&quot;doFlow(): %d\n&quot;, i);
     *        Thread.sleep(50); // Schedule the new flow's thread. 
     *        System.out.println(&quot;Done&quot;);
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    int doFlow() {
     *        System.out.println(&quot;Before returnAndContinue()&quot;);
     *        Flow.returnAndContinue(123);
     *        System.out.println(&quot;After returnAndContinue()&quot;);
     *        return 456;
     *    }
     * 
     * 
     * </pre>
     * The above example prints the following:
     * 
     * <pre>
     *     Before doFlow()
     *     Before returnAndContinue()
     *     doFlow(): 123
     *     After returnAndContinue()
     *     Done
     * </pre>
     * <p>
     * Notice that the new flow ends when the method <code>doFlow()</code> ends.
     * Hence the value of the actual <code>return</code> statement (
     * <code>456</code> in the example) is discarded. Calling this method more
     * than once in the same method works, but is redundant because in the
     * second and subsequent calls, the work doesn't need to be done in a new
     * flow.
     * <p>
     * This method must be called only by an <code>int</code> method. There is
     * one version of this method for each possible return value.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not an
     *         <code>int</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(int v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_INT);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>long-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>long</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>long</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(long v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_LONG);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>float-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>float</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>float</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(float v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_FLOAT);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>double-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>double</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>double</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(double v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_DOUBLE);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>reference-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for an reference method.
     * <p>
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a reference
     *         method.
     * @throws ClassCastException If the returned object is not assignable to
     *         the invoker's return type.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(Object v) {
        Flow cur = fromInvoker();
        MethodFrame returning;
        MethodFrame frame = cur.currentFrame;
        try {
            // Use ';' because an object descriptor is "L<package>/<class>;" .
            frame.checkResultType(';');
            if (v != null) {
                Class<?> clazz;
                try {
                    clazz = frame.getMethodReturnType();
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
                clazz.cast(v);
            }
            if (frame.isInvoking()) {
                // We are performing a fork.
                Flow copy;
                cur.suspendInPlace();
                try {
                    copy = cur.frameCopy();
                } finally {
                    cur.restoreInPlace();
                }
                copy.activate();
                returning = frame;
            } else {
                returning = null;
            }
        } finally {
            frame.invoked();
        }
        if (returning != null) {
            returning.result(v);
        }
    }

    private static MethodFrame forkAndReturn(char type) {
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            frame.checkResultType(type);
            if (frame.isInvoking()) {
                // We are performing a fork.
                Flow copy;
                cur.suspendInPlace();
                try {
                    copy = cur.frameCopy();
                } finally {
                    cur.restoreInPlace();
                }
                copy.activate();
                return frame;
            }
            // We are restoring from a fork.
            return null;
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
     *            System.out.println(&quot;doFlow() returned&quot;);
     *        } catch (FlowSignal signal) {
     *            System.out.println(&quot;Caught by the flow-controller&quot;);
     *            System.out.println(&quot;Calling Flow.resume()&quot;);
     *            signal.getFlow().{@link #resume()};
     *            System.out.println(&quot;Flow ended&quot;);
     *        }
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    void doFlow() { // This is the flow-creator.
     *        try {
     *            doSignal();
     *            System.out.println(&quot;doSignal() returned&quot;);
     *        } catch (FlowSignal signal) {
     *            System.out.println(&quot;Caught by the flow-creator&quot;);
     *        }
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    void doSignal() { // This is an ordinary flow method.
     *        try {
     *            System.out.println(&quot;Sending signal&quot;);
     *            FlowSignal signal = new MyFlowSignal();
     *            Flow.signal(signal);
     *            System.out.println(&quot;Returned from signal (resumed)&quot;);
     *        } catch (FlowSignal signal) {
     *            System.out.println(&quot;Caught by doSignal()&quot;);
     *        }
     *    }
     * 
     * 
     * </pre>
     * The above snippet prints the following:
     * 
     * <pre>
     *     Sending signal
     *     Caught by the flow-controller
     *     Calling Flow.resume()
     *     Returned from signal (resumed)
     *     doSignal() returned
     *     Flow ended
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
     * {@link ThreadFreeLock}. It is the lowest level API for implementing
     * features such as releasing a pooled thread before completion and
     * serializing thread state for long running processes.
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
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            if (frame.isInvoking()) {
                // We are suspending.
                log("Signaling, signal=" + signal);
                if (signal == null) {
                    throw new NullPointerException();
                }
                cur.state = SUSPENDING;
                cur.suspendedFrame = frame.copy(cur);
                cur.result = signal;
                signal.flow = cur;
                frame.leaveThread();
                return null;
            }
            // We are restoring from suspended state.
            log("Restored from signal.");
            assert signal.flow == cur : signal.flow;
            Object result = cur.result;
            cur.result = null;
            if (result instanceof ExceptionEnvelope) {
                Throwable exception = ((ExceptionEnvelope) result).exception;
                throw new ResumeException(exception);
            }
            return result;
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

    @FlowMethod(manual = true)
    static MethodFrame invokerFrame() {
        return fromInvoker().currentFrame;
    }

    static MethodFrame enter(Object owner, String name, String desc) {
        Flow cur = current();
        if (cur == null || cur.isPassive()) {
            cur = newFlow(cur);
        }
        return cur.newFrame(owner, name, desc);
    }

    static Object getLocal(FlowLocal<?> local) {
        return safeCurrent().doGetLocal(local);
    }

    static Object setLocal(FlowLocal<?> local, Object value) {
        return safeCurrent().doSetLocal(local, value);
    }

    static Object removeLocal(FlowLocal<?> local) {
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
    private WeakHashMap<FlowLocal<?>, Object> locals;

    private Flow(FlowManager manager, Flow previous) {
        this.manager = manager;
        this.previous = previous;
        state = ENDED;
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

    public boolean isEnded() {
        return state == ENDED;
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

    public Object resume(Object signalResult) {
        synchronized(this) {
            if (state != SUSPENDED) {
                throw new IllegalStateException("Cannot resume if the flow is " + stateNames[state] + '.');
            }
            state = ACTIVE;
        }
        boolean success = false;
        try {
            restore();
            setCurrent(this);
            result = signalResult;
            Class<?> clazz = suspendedFrame.getTargetClass();

            try {
                Class<?>[] argClasses = suspendedFrame.getMethodParameterTypes();
                Object[] argValues = new Object[argClasses.length];

                /* TODO:
                 *   For now, invokes the root FlowMethod method; in the future, the enhancer
                 *   should add a synthetic static method to the root class. This synthetic
                 *   method will invoke the root FlowMethod method passing primitive argument
                 *   values obtained directly through the MethodFrame. This will save
                 *   reflection and boxing costs, and will remove visibility problems.
                 */
                NoSuchMethodException fe = null;
                for (;;) {
                    try {
                        Method m = clazz.getDeclaredMethod(suspendedFrame.getMethodName(), argClasses);
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
                assert state == ENDED || state == SUSPENDED : "state==" + state;
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

    public Future<?> activate(Object signalResult) {
        return manager.submit(this, signalResult);
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
     * The target flow must be either suspended or ended, and hence this method
     * cannot be used to copy a running flow. To get the state of a running
     * flow, use {@link Continuation}.
     * <p>
     * 
     * @return A flow whose execution context is identical to this flow, sharing
     *         all heap objects referenced by stack frames.
     * @throws IllegalStateException If this flow is not suspended nor ended.
     */
    public synchronized Flow copy() {
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

    private synchronized Flow frameCopy() {
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
            case ENDED:
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

    public synchronized Object join() throws InterruptedException {
        while (state != ENDED) {
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
        while (state != SUSPENDED && state != ENDED) {
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
                Process thisProcess = process;
                if (thisProcess != null) {
                    thisProcess.remove(this);
                    // We still reference the process, for information issues and to
                    // go back to process if this finished flow is resumed.
                    process = thisProcess;
                }
                synchronized(this) {
                    state = ENDED;
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
                    result = signal.getResult();
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

    synchronized void setSuspendedFrame(MethodFrame frame) {
        switch (state) {
            case ENDED:
                suspendedFrame = frame;
                state = SUSPENDED;
                if (process != null) {
                    Process thisProcess = process;
                    process = null;
                    thisProcess.add(this);
                }
                break;
            case SUSPENDED:
                suspendedFrame = frame;
                break;
            default:
                throw new IllegalStateException("Cannot resume if flow is " + stateNames[state] + '.');
        }
    }

    private void suspendInPlace() {
        assert state == ACTIVE;
        assert currentFrame != null;
        assert suspendedFrame == null;
        state = SUSPENDED;
        suspendedFrame = currentFrame;
        currentFrame = null;
    }

    private synchronized void restoreInPlace() {
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
            Class<?> argClass = argClasses[i];
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

    private Object doGetLocal(FlowLocal<?> local) {
        WeakHashMap<FlowLocal<?>, Object> map = getLocals();
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

    private Object doSetLocal(FlowLocal<?> local, Object value) {
        return getLocals().put(local, value);
    }

    private Object doRemoveLocal(FlowLocal<?> local) {
        if (locals != null) {
            return null;
        }
        return locals.remove(local);
    }

    private WeakHashMap<FlowLocal<?>, Object> getLocals() {
        if (locals == null) {
            locals = new WeakHashMap<FlowLocal<?>, Object>();
        }
        return locals;
    }

    // TODO: Add wrappers for java.lang.Thread??? Consider static and instance methods.

}
