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
 * <li>A flow can be {@link #suspend(Object) suspended} for later
 * {@link #resume(Object) resuming}, without consuming a Java thread meanwhile.</li>
 * <li>The flow's execution state can be serialized and restored, possibly on a
 * different machine (as long as certain conditions are met). This makes flows
 * portable and memory-friendly.</li>
 * <li>A flow can wait for a {@linkplain ThreadFreeLock lock} on a resource, or
 * for the {@linkplain IOActivator completion} of an I/O operation, without
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
 * <a name="flowcreator"> <b>Flow-creator:</b> Whenever a flow-method is invoked
 * by a normal (non-flow) method, it is called the flow-creator method, because
 * it triggers the creation of a new flow (this is done automatically). The flow
 * ends when the flow-creator completes normally or by exception. If the
 * flow-creator calls itself or another flow-method, no new flow is created, as
 * described above.
 * <p>
 * <a name="flowcontroller"> <b>Flow-controller:</b> The flow-controller is a
 * normal (non-flow) method that invokes a flow-method. The flow-controller
 * receives {@linkplain #signal(FlowSignal) signals} sent by some flow
 * operations, and must handle those signals accordingly. There are standard
 * implementations of flow-controllers, such as {@link #execute(Callable)} and
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
 * <p>
 * If a flow <i>A</i> belongs to a {@link Task}, then every flow <i>B</i>
 * derived from <i>A</i> will automatically belong to the same task. Derived
 * flows are result of shallow {@linkplain #copy() copies}. Many utilities
 * perform shallow copies, including but not limited to {@link #fork(int)},
 * {@link #returnAndContinue()} and {@link Continuation}.
 * 
 * @see FlowMethod
 * @see Task
 * @author Fernando Colombo
 */
public final class Flow implements Serializable {

    private static final long serialVersionUID = 6770568702848053327L;

    /**
     * A constant indicating that the flow is active. An active flow is running,
     * which means that it is consuming a {@link Thread}. When a
     * {@linkplain FlowMethod flow method} calls a non-flow method, the flow
     * will remain active. A flow will move out from active state when the <a
     * href="#flowcreator">flow-creator</a> returns or when the flow is
     * {@linkplain #suspend(Object) suspended}.
     * 
     * @see #getState()
     */
    public static final int ACTIVE = 1;

    /**
     * A constant indicating that the flow is suspended. A suspended flow is not
     * running, which means that it is not consuming any {@link Thread}. A
     * suspended flow is always stopped at the point of invocation of
     * {@link #signal(FlowSignal)} or higher level methods such as
     * {@link #suspend(Object)} or {@link Continuation#checkpoint()}. This means
     * it can be resumed with one of the {@link #resume(Object) resume()}
     * methods.
     * 
     * @see #getState()
     */
    public static final int SUSPENDED = 2;

    /**
     * A constant indicating that the flow is passive. A passive flow is not
     * running and its data is stored on its {@linkplain #task() task}. Except
     * for that, a passive flow is identical to a {@link #SUSPENDED} flow.
     * 
     * @see #getState()
     */
    public static final int PASSIVE = 3;

    /**
     * A constant indicating that the flow is ended. A flow is ended when the <a
     * href="#flowcreator">flow-creator</a> returns normally or by exception, or
     * when the method {@link #end()} is called. An ended flow cannot be
     * resumed, but it can be passed for the method
     * {@link Continuation#placeOnCheckpoint(Flow)}.
     * 
     * @see #getState()
     */
    public static final int ENDED = 4;

    private static final int SUSPENDING = 5;

    private static final int TEMP_SUSP = 6;

    private static final String[] stateNames = new String[] { "ACTIVE", "SUSPENDED", "PASSIVE", "ENDED", "SUSPENDING", "TEMP_SUSP" };

    private static String stateName(int state) {
        if (state >= 1 && state <= 6) {
            return stateNames[state - 1];
        }
        return "(unknown state: " + state + ")";
    }

    private static final ThreadLocal<Flow> current = new ThreadLocal<Flow>();

    /**
     * Creates and returns a new flow. The new flow will be in {@link #ENDED}
     * state, which is suitable to be passed as argument to methods such as
     * {@link Continuation#resumeOnFlow(Flow, Object)}.
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
        Flow flow = newFlow(null);
        MethodFrame frame = flow.newFrame(runnable, "run", "()V");
        frame.notifyInvoke(Integer.MAX_VALUE, 0, 0);
        flow.state = SUSPENDED;
        flow.currentFrame = null;
        flow.suspendedFrame = frame;
        flow.activate();
        return flow;
    }

    public static Flow submit(Callable<?> callable) {
        Flow flow = newFlow(null);
        MethodFrame frame = flow.newFrame(callable, "call", "()Ljava/lang/Object;");
        frame.notifyInvoke(Integer.MAX_VALUE, 0, 0);
        flow.state = SUSPENDED;
        flow.currentFrame = null;
        flow.suspendedFrame = frame;
        flow.activate();
        return flow;
    }

    /**
     * The task to which this flow belongs.
     * 
     * @return An instance of {@link Task}, or <code>null</code> if this flow
     *         does not belong to any task.
     * @see #joinTask(Task)
     * @see #leaveTask()
     */
    @FlowMethod(manual = true)
    public static Task task() {
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            return cur.task;
        } finally {
            frame.invoked();
        }
    }

    /**
     * Adds the {@linkplain Flow#current() current} flow to the informed
     * {@linkplain Task task}. This method sets the {@linkplain #task() current
     * flow's task} to the informed task. This allows usage of {@linkplain Task
     * task utilities} from the current flow.
     * 
     * @param task The task to which the current flow will be added. Must not be
     *        <code>null</code>.
     * @see Task
     * @see #leaveTask()
     * @see #forgetTask()
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws NullPointerException If the informed task is a <code>null</code>
     *         reference.
     */
    @FlowMethod(manual = true)
    public static void joinTask(Task task) {
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            if (task == null) { // Must be called here, inside the try-finally.
                throw new NullPointerException();
            }
            if (cur.task != null) {
                if (cur.task == task) {
                    throw new IllegalStateException("Flow already belongs to specified task.");
                }
                throw new IllegalStateException("Flow belongs to another task.");
            }
            task.add(cur);
        } finally {
            frame.invoked();
        }
    }

    /**
     * Removes the {@linkplain Flow#current() current flow} from its
     * {@linkplain #task() current task}. This method sets the
     * {@linkplain #task() current flow's task} to <code>null</code>, or throws
     * an exception if the {@linkplain Flow#current() current flow} does not
     * belong to any task.
     * 
     * @see #joinTask(Task)
     * @see #forgetTask()
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod},
     *         or if the current flow does not belong to any task.
     */
    @FlowMethod(manual = true)
    public static void leaveTask() {
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            Task task = cur.task;
            if (task == null) {
                throw new IllegalStateException("Flow does not belong to any task.");
            }
            task.remove(cur);
        } finally {
            frame.invoked();
        }
    }

    /**
     * Removes the {@linkplain Flow#current() current flow} from its
     * {@linkplain #task() current task}. This method sets the
     * {@linkplain #task() current flow's task} to <code>null</code>, or does
     * nothing if the {@linkplain Flow#current() current flow} does not belong
     * to any task.
     * 
     * @see #joinTask(Task)
     * @see #leaveTask()
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     */
    @FlowMethod(manual = true)
    public static boolean forgetTask() {
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            Task task = cur.task;
            if (task == null) {
                return false;
            }
            task.remove(cur);
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

    /**
     * Ends the current flow. This method does not return. It causes execution
     * to continue after the <a href="#flowcreator">flow-creator</a>. The
     * following sample illustrates this behavior:
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
     * the <a href="#flowcreator">flow-creator</a> as it would be if
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
     * the <a href="#flowcreator">flow-creator</a> as it would be if
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

    /**
     * Equivalent to {@link #suspend(Object) suspend(null)}.
     */
    @FlowMethod
    public static Object suspend() {
        return signal(new SuspendSignal(null));
    }

    /**
     * Suspends the current flow, allowing the current thread to be released.
     * This method simply sends a {@link SuspendSignal} to the <a
     * href="#flowcontroller">flow-controller</a>, which by
     * {@linkplain FlowSignal#defaultAction() default} does
     * {@linkplain SuspendSignal#defaultAction() nothing}. This can be used to
     * release the current thread to other tasks.
     * <p>
     * The informed argument is simply passed to the
     * {@linkplain SuspendSignal#SuspendSignal(Object) constructor} of
     * {@link SuspendSignal}, and can be later obtained, usually in the <a
     * href="#flowcontroller">flow-controller</a>, by invoking
     * {@link SuspendSignal#getResult()}. The informed argument is usually used
     * to tell the reason of suspension. That is, its usually a merely
     * informative value, used for debugging and tracing purposes.
     * <p>
     * When a flow chooses to suspend itself, it usually must provide means to
     * be {@linkplain #resume(Object) resumed} when some event occurs, and not
     * rely on the flow-controller for this task. For example, if a flow chooses
     * to suspend itself while waiting for user input, it should store
     * {@linkplain Flow#current() itself} on the user's session storage
     * <i>before</i> suspending, so that when user input happens, the event
     * handler can invoke {@link #resume(Object)} on the suspended flow.
     * <p>
     * This method returns the value passed to {@link #resume(Object)}, and
     * might throw an exception if {@link #resumeThrowing(Throwable)} were
     * instead used. This method can return multiple times and/or in different
     * flows, at the discretion of whoever uses the suspended flow.
     * <p>
     * This method behaves exactly as the expression
     * <code>signal(new SuspendSignal(argument))</code>.
     * 
     * @param argument The argument to be associated with {@link SuspendSignal}.
     * @return The object passed to {@link #resume(Object)}.
     * @see #signal(FlowSignal)
     * @see #suspend()
     * @see SuspendSignal
     * @see #resume()
     * @see #resume(Object)
     */
    @FlowMethod
    public static Object suspend(Object argument) {
        return signal(new SuspendSignal(argument));
    }

    /**
     * Suspends the flow and sends a signal to the flow-controller.
     * <p>
     * This method first suspends the current flow at the point of invocation.
     * Then it sends the specified signal to the <a
     * href="#flowcontroller">flow-controller</a>, as if the signal were thrown
     * by the <a href="#flowcreator">flow-creator</a> (see below). After this,
     * the next actions are fully determined by the flow-controller, which
     * typically uses the signal to decide.
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
     * This method is used to implement utilities such as
     * {@link #suspend(Object)} and {@link ThreadFreeLock}. It is the lowest
     * level API for implementing features such as releasing a pooled thread
     * before completion and serializing thread state for long running
     * processes.
     * 
     * @param signal The signal to be sent to the flow-controller.
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
                //log("Signaling, signal=" + signal);
                if (signal == null) {
                    throw new NullPointerException();
                }
                cur.state = SUSPENDING;
                cur.suspendedFrame = frame.copy(cur);
                // The result will carry the signal, so it can be thrown on finish().
                cur.result = signal;
                signal.flow = cur;
                frame.leaveThread();
                return null;
            }
            // We are restoring from suspended state.
            //log("Restored from signal.");
            // assert signal.flow == cur : signal.flow;
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

    /**
     * Returns the current flow. This method returns non-null if the current
     * thread is executing a {@linkplain FlowMethod flow method}. Otherwise it
     * returns <code>null</code>. The current flow will always be
     * {@link #ACTIVE} .
     * 
     * @return The current flow, or <code>null</code> no {@linkplain FlowMethod
     *         flow method} is running on the current thread.
     */
    public static Flow current() {
        return current.get();
    }

    private static Flow fromInvoker() {
        Flow ret = current();
        if (ret != null) {
            MethodFrame frame = ret.currentFrame;
            if (frame != null && (frame.isInvoking() || frame.isRestoring())) {
                return ret;
            }
        }
        throw new IllegalStateException("Illegal operation for a non-flow method.");
    }

    /**
     * Returns the current flow, or throws an exception if there is no current
     * flow. This method is like {@link #current()}, except in that it never
     * returns <code>null</code>. In the absence of a current flow, it will
     * throw an exception.
     * 
     * @return The current flow.
     * @throws IllegalStateException If there is no current flow.
     */
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
            current.set(cur);
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
        return ret;
    }

    private transient Flow previous;
    private final FlowManager manager;
    transient Task task;
    private int state;
    private MethodFrame currentFrame;
    private MethodFrame suspendedFrame;
    transient Fork currentFork;
    Object result;
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

    /**
     * This flow's state, which will be {@link #ACTIVE}, {@link #SUSPENDED},
     * {@link #PASSIVE} or {@link #ENDED}.
     * 
     * @see #waitSuspended()
     * @see #waitNotRunning()
     */
    public int getState() {
        return state;
    }

    public boolean isActive() {
        return state == ACTIVE || state == TEMP_SUSP;
    }

    public boolean isSuspended() {
        return state == SUSPENDED || state == PASSIVE;
    }

    public boolean isEnded() {
        return state == ENDED;
    }

    /**
     * Equivalent to {@link #resume(Object) resume(null)}.
     */
    public Object resume() {
        return resume(null);
    }

    /**
     * Resumes this flow throwing an exception. This method causes the last
     * invocation of {@link #signal(FlowSignal)} inside this flow to throw a
     * {@link ResumeException} whose {@linkplain Throwable#getCause() cause} is
     * the informed exception.
     * <p>
     * In all other aspects, this method behaves as {@link #resume(Object)}.
     * That is, the invoker will block until the flow completes, will be the new
     * <a href="#flowcontroller">flow-controller</a>, and might need to handle
     * {@linkplain #signal(FlowSignal) signals}.
     * 
     * @param exception The exception that will be the cause of the
     *        {@link ResumeException} to be throwed.
     * @return The value returned by the <a
     *         href="#flowcreator">flow-creator</a>, or <code>null</code> if the
     *         flow-creator is a <code>void</code> method.
     * @throws NullPointerException If <code>exception</code> is null.
     * @throws IllegalStateException If this flow is not suspended.
     * @see #signal(FlowSignal)
     * @see #resume(Object)
     * @see #activateThrowing(Throwable)
     */
    public Object resumeThrowing(Throwable exception) {
        if (exception == null) {
            throw new NullPointerException();
        }
        return resume(new ExceptionEnvelope(exception));
    }

    /**
     * Resumes this flow. This method causes the last invocation of
     * {@link #signal(FlowSignal)} inside this flow to return. The flow resumes
     * execution directly on the current thread, and therefore this method only
     * returns when the flow finishes. This means that the invoker of this
     * method will be the new <a href="#flowcontroller">flow-controller</a>, and
     * might need to handle {@linkplain #signal(FlowSignal) signals}. To resume
     * this flow on another thread, use {@link #activate(Object)}.
     * <p>
     * The informed parameter is simply returned to the flow as a normal result
     * of {@link #signal(FlowSignal)}.
     * <p>
     * The completion of this method is defined as follows:
     * <ul>
     * <li>If the flow finishes normally, this method returns the value returned
     * by the <a href="#flowcreator">flow-creator</a>.</li>
     * <li>If the flow throws an exception, this method throws a
     * {@link FlowException} having the exception as its
     * {@linkplain Throwable#getCause() cause}.
     * <li>If the flow sends a {@linkplain #signal(FlowSignal) signal}, the flow
     * is first suspended, then the signal is thrown as an exception.
     * </ul>
     * In other words, the invoker of {@link #resume(Object)} will behave as an
     * ordinary <a href="#flowcontroller">flow-controller</a>.
     * 
     * @param signalResult The value that {@link #signal(FlowSignal)} must
     *        return to its invoker.
     * @return The value returned by the <a
     *         href="#flowcreator">flow-creator</a>, or <code>null</code> if the
     *         flow-creator is a <code>void</code> method.
     * @throws IllegalStateException If this flow is not suspended.
     * @see #signal(FlowSignal)
     * @see #resume()
     * @see #resumeThrowing(Throwable)
     * @see #activate()
     * @see #activate(Object)
     */
    public Object resume(Object signalResult) {
        synchronized(this) {
            if (state != SUSPENDED && state != PASSIVE) {
                throw new IllegalStateException("Cannot resume if the flow is " + stateName(state) + '.');
            }
            if (task != null) {
                task.notifyResume(this);
            }
            // We redo the check because the task might have changed the state or might forget to
            // set this PASSIVE flow to SUSPENDED.
            if (state != SUSPENDED) {
                throw new IllegalStateException("Cannot resume if the flow is " + stateName(state) + '.');
            }
            state = ACTIVE;
        }
        String debugMethodName = null;
        boolean success = false;
        try {
            restore();
            setCurrent(this);
            result = signalResult;
            Class<?> clazz = suspendedFrame.getTargetClass();

            assert (debugMethodName = clazz.getName() + "#" + suspendedFrame.getMethodName()) != "";
            log("resuming " + debugMethodName);

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
                        result = m.invoke(owner, argValues);
                        return result;
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
                success = true;
                if (e.getCause() instanceof FlowSignal) {
                    throw (FlowSignal) e.getCause();
                }
                throw new FlowException(e.getCause());
            }
        } finally {
            if (!success) {
                log("state == " + stateName(state) + ", method=" + debugMethodName);
            }
        }
    }

    /**
     * Equivalent to {@link #activate(Object) activate(null)}.
     */
    public Future<?> activate() {
        return activate(null);
    }

    /**
     * Schedules this flow to {@linkplain #resume(Object) resume} in another
     * thread, and throws an exception upon resuming. This method is identical
     * to {@link #activate(Object)}, except in that resuming is done with
     * {@link #resumeThrowing(Throwable)} instead of {@link #resume(Object)}.
     * 
     * @see #resumeThrowing(Throwable)
     * @see #activate()
     * @see #activate(Object)
     */
    public Future<?> activateThrowing(Throwable exception) {
        if (exception == null) {
            throw new NullPointerException();
        }
        return activate(new ExceptionEnvelope(exception));
    }

    /**
     * Schedules this flow to {@linkplain #resume(Object) resume} in another
     * thread. The method invokes the flow's manager
     * {@link FlowManager#submit(Flow, Object)} method, which will assign resume
     * execution to some thread. The thread's run is a simple <a
     * href="#flowcontroller">flow-controller</a>. It can be roughly defined by
     * this pseudo-code:
     * 
     * <pre>
     *     public void run() {
     *         try {
     *             flow.resume(signalResult);
     *         } catch (FlowSignal signal) {
     *             signal.defaultAction(); // Always call the default action.
     *         } catch (Throwable e) {
     *             log(e); // Logs the exception.
     *         }
     *     }
     * </pre>
     * 
     * The returned {@link Future} can be use to query execution status. Notice
     * that if such <code>Future</code> is {@linkplain Future#isDone() done}, it
     * does not necessarily means that the flow finished.
     * {@link Future#isDone()} will return <code>true</code> even when the flow
     * was just suspended.
     * 
     * @param signalResult The argument to be passed to {@link #resume(Object)}
     *        method. This argument will be returned to the flow by the
     *        {@link #signal(FlowSignal)} that causes suspension.
     * @return A {@link Future} that can be used to query execution status.
     * @see #resume(Object)
     * @see #activate()
     * @see #activateThrowing(Throwable)
     */
    public Future<?> activate(Object signalResult) {
        return manager.submit(this, signalResult);
    }

    /**
     * Performs a shallow copy on this flow. A shallow copy is a simple copy of
     * stack frames, with all local and stack variables, starting from the <a
     * href="#flowcreator">flow-creator</a> method, and until the current
     * position in the flow's execution. The value of all variables are copied
     * by simple assignment, including references. Because no heap object is
     * ever cloned or serialized by this method, this flow and the copy will
     * share heap objects referenced by the copied stack variables.
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
        ret.state = state == TEMP_SUSP ? SUSPENDED : state;
        ret.suspendedFrame = suspendedFrame.copy(ret);
        ret.result = result;
        if (task != null) {
            task.add(ret);
            assert ret.task == task;
        }
        return ret;
    }

    private synchronized Flow frameCopy() {
        checkSteady();
        Flow ret = new Flow(manager, null);
        ret.state = state == TEMP_SUSP ? SUSPENDED : state;
        ret.suspendedFrame = suspendedFrame.shallowCopy(ret);
        ret.result = result;
        if (task != null) {
            task.add(ret);
            assert ret.task == task;
        }
        return ret;
    }

    private void checkSteady() {
        switch (state) {
            case SUSPENDED:
            case TEMP_SUSP:
                assert suspendedFrame != null;
                break;
            case ENDED:
                assert suspendedFrame == null;
                break;
            default:
                throw new IllegalStateException("Cannot copy flow if state is " + stateName(state) + ".");
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

    public synchronized FlowSignal waitSuspended() throws InterruptedException {
        while (state != SUSPENDED && state != PASSIVE) {
            wait();
        }
        return (FlowSignal) result;
    }

    public synchronized Object waitNotRunning() throws InterruptedException {
        while (state != SUSPENDED && state != PASSIVE && state != ENDED) {
            wait();
        }
        return result;
    }

    /**
     * The current flow result. The actual value depends on the flow state.
     * <ul>
     * <li>If the flow has ended normally, the result is the value returned by
     * the <a href="#flowcreator">flow-creator</a>. In the case where
     * flow-creator is a <code>void</code> method, the result will be
     * <code>null</code>.</li>
     * <li>If the flow has ended by an exception, the result is the thrown
     * exception.</li>
     * <li>If the flow is suspended or passive, the result is the sent
     * {@linkplain FlowSignal signal}.</li>
     * <li>If the flow is active, the result is <code>null</code>.</li>
     * </ul>
     * 
     * @see #getState()
     */
    public synchronized Object getResult() {
        try {
            while (state == SUSPENDING) {
                wait();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (state == ACTIVE) {
            return null;
        }
        return result;
    }

    synchronized FlowData fetchState(long id) {
        assert task != null;
        assert state == SUSPENDED;
        FlowData ret = new FlowData(suspendedFrame, id);
        suspendedFrame = null;
        state = PASSIVE;
        notifyAll();
        return ret;
    }

    synchronized void restoreState(FlowData flowState) {
        assert task != null;
        assert state == PASSIVE;
        suspendedFrame = flowState.suspendedFrame;
        state = SUSPENDED;
        notifyAll();
    }

    void finish() {
        log("finishing...");
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
                Task thisTask = task;
                if (thisTask != null) {
                    thisTask.remove(this);
                    // We still reference the task, for information issues and to
                    // go back to task if this finished flow is resumed.
                    task = thisTask;
                }
                synchronized(this) {
                    state = ENDED;
                    currentFrame = null;
                    notifyAll();
                }
                log("normally");
                return;
            case SUSPENDING:
                assert suspendedFrame != null;
                FlowSignal signal = (FlowSignal) result;
                result = signal;
                currentFrame = null;
                synchronized(this) {
                    state = SUSPENDED;
                    if (task != null) {
                        task.notifySuspend(this);
                    }
                    notifyAll();
                }
                log("throwing signal");
                throw signal;
            default:
                throw new AssertionError("state shouldn't be " + stateName(state));
        }
    }

    MethodFrame newFrame(Object owner, String name, String desc) {
        assert state == ACTIVE : stateName(state);
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
        try {
            while (state == SUSPENDING) {
                wait();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        switch (state) {
            case ENDED:
                suspendedFrame = frame;
                state = SUSPENDED;
                if (task != null) {
                    Task thisTask = task;
                    task = null;
                    thisTask.add(this);
                }
                break;
            case SUSPENDED:
                suspendedFrame = frame;
                break;
            default:
                throw new IllegalStateException("Cannot resume if flow is " + stateName(state) + '.');
        }
        notifyAll();
    }

    private void suspendInPlace() {
        assert state == ACTIVE;
        assert currentFrame != null;
        assert suspendedFrame == null;
        state = TEMP_SUSP;
        suspendedFrame = currentFrame;
        currentFrame = null;
    }

    private synchronized void restoreInPlace() {
        assert state == TEMP_SUSP;
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

    private static final class ExceptionEnvelope {

        Throwable exception;

        public ExceptionEnvelope(Throwable exception) {
            this.exception = exception;
        }

    }

    // TODO: Add wrappers for java.lang.Thread??? Consider static and instance methods.

}
