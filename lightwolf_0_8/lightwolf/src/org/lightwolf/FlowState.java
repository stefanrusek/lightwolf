package org.lightwolf;

public enum FlowState {

    /**
     * A constant indicating that the flow is active. An active flow is running,
     * which means that it is consuming a {@link Thread}. When a
     * {@linkplain FlowMethod flow method} calls a non-flow method, the flow
     * will remain active. A flow will move out from active state when the <a
     * href="Flow#flowcreator">flow-creator</a> returns or when the flow is
     * {@linkplain Flow#suspend(Object) suspended}.
     * 
     * @see Flow#getState()
     */
    ACTIVE,

    /**
     * A constant indicating that the flow is suspended. A suspended flow is not
     * running, which means that it is not consuming any {@link Thread}. A
     * suspended flow is always stopped at the point of invocation of
     * {@link Flow#signal(FlowSignal)} or higher level methods such as
     * {@link Flow#suspend(Object)} or {@link Continuation#checkpoint()}. This
     * means it can be resumed with one of the {@link Flow#resume(Object)
     * resume()} methods.
     * 
     * @see Flow#getState()
     */
    SUSPENDED,

    /**
     * A constant indicating that the flow is passive. A passive flow is not
     * running and its data is stored on its {@linkplain Flow#task() task}.
     * Except for that, a passive flow is identical to a {@link Flow#SUSPENDED}
     * flow.
     * 
     * @see Flow#getState()
     */
    PASSIVE,

    /**
     * A constant indicating that the flow is ended. A flow is ended when the <a
     * href="#flowcreator">flow-creator</a> returns normally or by exception, or
     * when the method {@link Flow#end()} is called. An ended flow cannot be
     * resumed, but it can be passed for the method
     * {@link Continuation#placeOnCheckpoint(Flow)}.
     * 
     * @see Flow#getState()
     */
    ENDED,

    SUSPENDING,

    /**
     * An internal temporary state used by {@link Flow#fork(int)},
     * {@link Flow#snapshot()} and other utilities. The flow will be in this
     * state only briefly.
     * 
     * @see Flow#getState()
     */
    TEMP_SUSP,

    INTERRUPTED

}
