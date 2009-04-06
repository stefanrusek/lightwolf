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

import java.util.concurrent.Future;

/**
 * An object that stores a {@linkplain Flow flow}'s execution context for
 * further resuming. Usage requires invocation of {@link #checkpoint()} and
 * {@link #resume(Object)} on an instance of this class. It is allowed to
 * subclass this class as a way to associate additional data or functionality to
 * a continuation.
 * 
 * @author Fernando Colombo
 */
public class Continuation implements Cloneable {

    private MethodFrame frame;

    /**
     * Creates a new continuation. This constructor doesn't set any checkpoint.
     * Use {@link #checkpoint()} for that.
     * 
     * @see #checkpoint()
     */
    public Continuation() {
    // Does nothing. This is a place to add Javadoc.
    }

    /**
     * Returns an identical, independent copy of this continuation. If this
     * continuation contains a {@linkplain #checkpoint() checkpoint}, the
     * returned object will have a copy of such checkpoint.
     */
    @Override
    public Continuation clone() throws CloneNotSupportedException {
        Continuation ret = (Continuation) super.clone();
        ret.frame = MethodFrame.copy(frame, null);
        return ret;
    }

    /**
     * Places a checkpoint on the current {@linkplain Flow flow}, which can be
     * used resume execution from the point of invocation. The checkpoint
     * contains the values of parameters, local variables, temporary data and
     * instruction pointers, of all frames from the <a
     * href="Flow.html#flowcreator">flow-creator</a> (inclusive) to the point of
     * invocation.
     * <p>
     * After invocation, it's possible to call {@link #resume(Object)} on this
     * continuation, so a flow can resume from the checkpoint. When a checkpoint
     * is created, this method returns <code>true</code>. Upon resuming, this
     * method returns <code>false</code>. The following example illustrates this
     * behavior:
     * 
     * <pre>
     *    void example() {
     *        System.out.println(&quot;Before doFlow()&quot;);
     *        Continuation continuation = doFlow();
     *        System.out.println(&quot;After doFlow()&quot;);
     *        continuation.{@link #resume(Object) resume(null)};
     *        System.out.println(&quot;After continuation.resume()&quot;);
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    FlowContext doFlow() {
     *        System.out.println(&quot;Before doCheckpoint()&quot;);
     *        Continuation continuation = doCheckpoint();
     *        System.out.println(&quot;After doCheckpoint()&quot;);
     *        return continuation;
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    Continuation doCheckpoint() {
     *        Continuation continuation = new Continuation();
     *        System.out.println(&quot;Before continuation.checkpoint()&quot;);
     *        if (continuation.checkpoint()) {
     *            System.out.println(&quot;Checkpoint is set.&quot;);
     *        } else {
     *            System.out.println(&quot;We are resuming.&quot;);
     *        }
     *        return continuation;
     *    }
     * </pre>
     * The above example prints the following:
     * 
     * <pre>
     *     Before doFlow()
     *     Before doCheckpoint()
     *     Before continuation.checkpoint()
     *     Checkpoint is set.
     *     After doCheckpoint()
     *     After doFlow()
     *     We are resuming.
     *     After doCheckpoint()
     *     After continuation.resume()
     * </pre>
     * This method may return multiple times, at the discretion of whoever is
     * using the continuation. Each time it might return in a different
     * {@linkplain Flow flow}. But it's guaranteed that whenever this method
     * returns <code>true</code>, it will return in the same flow as the
     * invoker.
     * <p>
     * A continuation can hold at most one checkpoint, so this method throws
     * away any previous checkpoint that this continuation was holding.
     * 
     * @return <code>true</code> if a checkpoint was created, <code>false</code>
     *         if this method is resuming from a previously created checkpoint.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     */
    @FlowMethod(manual = true)
    public final boolean checkpoint() {
        MethodFrame invokerFrame = Flow.invokerFrame();
        try {
            if (invokerFrame.isInvoking()) {
                frame = invokerFrame.copy(null);
                return true;
            }
            return false;
        } finally {
            invokerFrame.invoked();
        }
    }

    /**
     * Returns the object specified by whoever resumed this continuation. This
     * is the <code>result</code> parameter sent to one of the
     * {@link #resume(Object) resume} methods.
     */
    public Object getResult() {
        return Flow.current().result;
    }

    /**
     * Equivalent to {@link #resume(Object) resume(null)}.
     */
    public Object resume() {
        return resume(null);
    }

    /**
     * Creates a new flow and resumes it from the last checkpoint. Execution
     * resumes from the last invocation of {@link #checkpoint()} on this
     * continuation. Such invocation returns <code>false</code>, indicating that
     * execution is being resumed.
     * <p>
     * The new flow will execute synchronously. This method returns only then
     * the <a href="Flow.html#flowcreator">flow-creator</a> returns, as if by
     * invocation of {@link Flow#resume()}. If the resumed flow sends a
     * {@linkplain Flow#signal(FlowSignal) signal}, this method throws the
     * corresponding {@link FlowSignal}. In other words, the invoker will be the
     * <a href="Flow.html#flowcontroller">flow-controller</a>.
     * <p>
     * This method behaves exactly as:
     * 
     * <pre>
     *     Flow flow = Flow.{@link Flow#newFlow() newFlow()};
     *     continuation.{@link #resumeOnFlow(Flow, Object) resume(flow, null)};
     * </pre>
     * Before resuming, this method performs a {@linkplain Flow#copy() copy} of
     * the current checkpoint, so a future <code>resume</code> operation will
     * succeed. If there is no intention to resume again, one should call
     * {@link #resumeAndForget(Object)}.
     * 
     * @param result An object sent to the resumed flow. The flow can retrieve
     *        it by invoking {@link #getResult()}.
     * @return The <a href="Flow.html#flowcreator">flow-creator</a>'s result.
     * @throws IllegalStateException If there is no stored checkpoint on this
     *         continuation.
     * @see #resumeOnFlow(Flow, Object)
     * @see #resumeAndForget(Object)
     * @see #resumeAndForgetOnFlow(Flow, Object)
     */
    public Object resume(Object result) {
        Flow flow = Flow.newFlow();
        return resumeOnFlow(flow, result);
    }

    /**
     * Equivalent to {@link #resumeOnFlow(Flow, Object) resume(flow, null)}.
     */
    public Object resumeOnFlow(Flow flow) {
        return resumeOnFlow(flow, null);
    }

    /**
     * Resumes the informed flow from the last checkpoint. This method is
     * similar to {@link #resume(Object)}. The difference is that no new flow is
     * created. Instead, the informed {@linkplain Flow flow} is resumed.
     * 
     * @param result An object sent to the resumed flow. The flow can retrieve
     *        it by invoking {@link #getResult()}.
     * @return The <a href="Flow.html#flowcreator">flow-creator</a>'s result.
     * @throws IllegalStateException If there is no stored checkpoint on this
     *         continuation, or if the informed flow is on an invalid state for
     *         resuming.
     * @throws NullPointerException If the informed flow is <code>null</code>.
     * @see #resume(Object)
     * @see #resumeAndForgetOnFlow(Flow, Object)
     */
    public Object resumeOnFlow(Flow flow, Object result) {
        synchronized(this) {
            checkFrame();
            flow.setSuspendedFrame(frame.copy(flow));
        }
        return flow.resume(result);
    }

    /**
     * Equivalent to {@link #resumeAndForget(Object) resumeAndForget(null)}.
     */
    public Object resumeAndForget() {
        return resumeAndForget(null);
    }

    /**
     * Creates a new flow and resumes it from the last checkpoint, throwing away
     * the checkpoint. This method is similar to {@link #resume(Object)}. The
     * difference is that after invocation, this continuation will have no
     * {@link #checkpoint()}, which means that further invocations of
     * <code>resume</code> methods will throw an exception.
     * <p>
     * This method is cheaper than {@link #resume(Object)}, because it does not
     * involves a copy of the current checkpoint.
     * 
     * @param result An object sent to the resumed flow. The flow can retrieve
     *        it by invoking {@link #getResult()}.
     * @return The <a href="Flow.html#flowcreator">flow-creator</a>'s result.
     * @throws IllegalStateException If there is no stored checkpoint on this
     *         continuation.
     * @see #resume(Object)
     * @see #resumeAndForgetOnFlow(Flow, Object)
     */
    public Object resumeAndForget(Object result) {
        Flow flow = Flow.newFlow();
        return resumeAndForgetOnFlow(flow, result);
    }

    /**
     * Equivalent to {@link #resumeAndForgetOnFlow(Flow, Object)
     * resumeAndForget(flow, null)}.
     */
    public Object resumeAndForgetOnFlow(Flow flow) {
        return resumeAndForgetOnFlow(flow, null);
    }

    /**
     * Resumes the informed flow from the last checkpoint, throwing away the
     * checkpoint. This method is similar to {@link #resumeOnFlow(Flow, Object)}
     * . The difference is that after invocation, this continuation will have no
     * {@link #checkpoint()}, which means that further invocations of
     * <code>resume</code> methods will throw an exception.
     * <p>
     * This method is cheaper than {@link #resumeOnFlow(Flow, Object)}, because
     * it does not involves a copy of the current checkpoint.
     * 
     * @param result An object sent to the resumed flow. The flow can retrieve
     *        it by invoking {@link #getResult()}.
     * @return The <a href="Flow.html#flowcreator">flow-creator</a>'s result.
     * @throws IllegalStateException If there is no stored checkpoint on this
     *         continuation, or if the informed flow is on an invalid state for
     *         resuming.
     * @throws NullPointerException If the informed flow is <code>null</code>.
     * @see #resume(Object)
     * @see #resumeAndForget(Object)
     */
    public Object resumeAndForgetOnFlow(Flow flow, Object result) {
        synchronized(this) {
            checkFrame();
            flow.setSuspendedFrame(frame);
            frame = null;
        }
        return flow.resume(result);
    }

    public Future<?> activate() {
        Flow flow = Flow.newFlow();
        placeOnCheckpoint(flow);
        return flow.activate();
    }

    public Future<?> activate(Object result) {
        Flow flow = Flow.newFlow();
        placeOnCheckpoint(flow);
        return flow.activate(result);
    }

    public Future<?> activateThrowing(Throwable exception) {
        Flow flow = Flow.newFlow();
        placeOnCheckpoint(flow);
        return flow.activateThrowing(exception);
    }

    public void placeOnCheckpoint(Flow flow) {
        synchronized(this) {
            checkFrame();
            flow.setSuspendedFrame(frame.copy(null));
        }
    }

    public void placeOnCheckpointAndForget(Flow flow) {
        synchronized(this) {
            checkFrame();
            flow.setSuspendedFrame(frame);
            frame = null;
        }
    }

    private void checkFrame() {
        if (frame == null) {
            throw new IllegalStateException("No stored checkpoint.");
        }
    }

}
