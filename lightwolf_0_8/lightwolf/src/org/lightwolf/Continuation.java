package org.lightwolf;

/**
 * An object that stores a {@linkplain Flow flow}'s execution context for
 * further resuming. Usage requires invocation of {@link #checkpoint()} and
 * {@link #resume()} on an instance of this class. It is allowed to subclass
 * this class as a way to associate additional data or functionality to a
 * continuation.
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
     * After invocation, it's possible to call {@link #resume()} on this
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
     *        continuation.{@link #resume()};
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
     *     continuation.{@link #resume(Flow) resume(flow)};
     * </pre>
     * Before resuming, this method performs a copy of the current checkpoint,
     * so a future <code>resume</code> operation will succeed. If there is no
     * intention to resume again, one should call {@link #resumeAndForget()}.
     * 
     * @return The <a href="Flow.html#flowcreator">flow-creator</a>'s result.
     * @throws IllegalStateException If there is no stored checkpoint on this
     *         continuation.
     * @see #resume(Flow)
     * @see #resumeAndForget()
     * @see #resumeAndForget(Flow)
     */
    public Object resume() {
        Flow flow = Flow.newFlow();
        return resume(flow);
    }

    /**
     * Resumes the informed flow from the last checkpoint. This method is
     * similar to {@link #resume()}. The difference is that no new flow is
     * created. Instead, the informed {@linkplain Flow flow} is resumed.
     * 
     * @return The <a href="Flow.html#flowcreator">flow-creator</a>'s result.
     * @throws IllegalStateException If there is no stored checkpoint on this
     *         continuation, or if the informed flow is on an invalid state for
     *         resuming.
     * @throws NullPointerException If the informed flow is <code>null</code>.
     * @see #resume()
     * @see #resumeAndForget(Flow)
     */
    public Object resume(Flow flow) {
        synchronized(this) {
            checkFrame();
            flow.setSuspendedFrame(frame.copy(flow));
        }
        return flow.resume();
    }

    /**
     * Creates a new flow and resumes it from the last checkpoint, throwing away
     * the checkpoint. This method is similar to {@link #resume()}. The
     * difference is that after invocation, this continuation will have no
     * {@link #checkpoint()}, which means that further invocations of
     * <code>resume</code> methods will throw an exception.
     * <p>
     * This method is cheaper than {@link #resume()}, because it does not
     * involves a copy of the current checkpoint.
     * 
     * @return The <a href="Flow.html#flowcreator">flow-creator</a>'s result.
     * @throws IllegalStateException If there is no stored checkpoint on this
     *         continuation.
     * @see #resume()
     * @see #resumeAndForget(Flow)
     */
    public Object resumeAndForget() {
        Flow flow = Flow.newFlow();
        return resumeAndForget(flow);
    }

    /**
     * Resumes the informed flow from the last checkpoint, throwing away the
     * checkpoint. This method is similar to {@link #resume(Flow)}. The
     * difference is that after invocation, this continuation will have no
     * {@link #checkpoint()}, which means that further invocations of
     * <code>resume</code> methods will throw an exception.
     * <p>
     * This method is cheaper than {@link #resume(Flow)}, because it does not
     * involves a copy of the current checkpoint.
     * 
     * @return The <a href="Flow.html#flowcreator">flow-creator</a>'s result.
     * @throws IllegalStateException If there is no stored checkpoint on this
     *         continuation, or if the informed flow is on an invalid state for
     *         resuming.
     * @throws NullPointerException If the informed flow is <code>null</code>.
     * @see #resume()
     * @see #resumeAndForget()
     */
    public Object resumeAndForget(Flow flow) {
        synchronized(this) {
            checkFrame();
            flow.setSuspendedFrame(frame);
            frame = null;
        }
        return flow.resume();
    }

    public void activate() {
        Flow flow = Flow.newFlow();
        placeOnCheckpoint(flow);
        flow.activate();
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
