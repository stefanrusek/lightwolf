package org.lightwolf;

/**
 * A listener for {@link Process} events.
 * 
 * @see Process#addEventListener(IProcessListener)
 * @author Fernando Colombo
 */
public interface IProcessListener {

    /**
     * A constant indicating that a flow was added to the process. Flows can be
     * added to a process using {@link Flow#joinProcess(Process)}. If a flow
     * that belongs to the process is subject to {@link Flow#fork(int)} or
     * {@link Flow#split(int)}, the new flows are automatically added to the
     * process, triggering this event.
     * <p>
     * The added flow may be {@link Flow#ACTIVE} or {@link Flow#SUSPENDED}.
     */
    int PE_FLOW_ADDED = 1;

    /**
     * A constant indicating that a flow was removed from the process. When a
     * flow finishes normally or by the {@link Flow#end()} method, it's removed
     * from its process. Flows can be explicitly removed from a process using
     * {@link Flow#leaveProcess()}. If a flow that belongs to the process is
     * subject to {@link Flow#merge()}, it's also removed from the process,
     * triggerind this process.
     */
    int PE_FLOW_REMOVED = 2;

    /**
     * A constant indicating that a flow was suspended. There are many
     * situations that cause a flow to be suspended. The simplest way is the
     * {@link Flow#suspend(Object)} method, but some APIs such as
     * {@link Process#receive(Object)} may cause a flow to be temporarily
     * suspended.
     */
    int PE_FLOW_SUSPENDED = 3;

    /**
     * A constant indicating that a suspended flow is about to be resumed. There
     * are many situations that cause a flow to be resumed. For example, if
     * {@link Flow#split(int)} is issued on a flow that belongs to a process,
     * new suspended flows are created, then added to the process, and finally
     * they are resumed. Some APIs such as {@link Process#receive(Object)} may
     * cause a flow to be temporarily suspended and resumed.
     */
    int PE_RESUMING_FLOW = 4;

    /**
     * Called when an event happens on a process. The implementation may query,
     * {@linkplain Process#activate() activate} or
     * {@linkplain Process#passivate() passivate} the process on which the event
     * occurred. Normally this method is implemented to detect when a
     * {@link Process} becomes idle, so it can be
     * {@linkplain Process#passivate() passivated} immediately or in the future.
     * <p>
     * This method should execute briefly and must not throw an exception. Any
     * thrown exception is simply logged and then ignored. This method is called
     * inside a synchronized block on the informed process, so that
     * {@link Thread#holdsLock(Object) Thread.holdsLock(sender)} always return
     * <code>true</code> during execution of this method.
     * 
     * @param sender The process on which the event happened.
     * @param event The event that happened on the process. See the
     *        <code>PE_</code> constants declared on this interface.
     * @param flow The flow associated with the event. This is the flow being
     *        added, removed, suspended or resumed. May be <code>null</code> if
     *        the event is not about a flow.
     */
    void onEvent(Process sender, int event, Flow flow);

}
