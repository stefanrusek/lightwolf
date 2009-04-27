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

/**
 * A listener for {@link Task} events.
 * 
 * @see Task#addEventListener(ITaskListener)
 * @author Fernando Colombo
 */
public interface ITaskListener {

    /**
     * A constant indicating that a flow was added to the task. Flows can be
     * added to a task using {@link Flow#joinTask(Task)}. If a flow that
     * belongs to the task is subject to {@link Flow#fork(int)} or
     * {@link Flow#split(int)}, the new flows are automatically added to the
     * task, triggering this event.
     * <p>
     * The added flow may be {@link Flow#ACTIVE} or {@link Flow#SUSPENDED}.
     */
    int PE_FLOW_ADDED = 1;

    /**
     * A constant indicating that a flow was removed from the task. When a
     * flow finishes normally or by the {@link Flow#end()} method, it's removed
     * from its task. Flows can be explicitly removed from a task using
     * {@link Flow#leaveTask()}. If a flow that belongs to the task is
     * subject to {@link Flow#merge()}, it's also removed from the task,
     * triggering this task.
     */
    int PE_FLOW_REMOVED = 2;

    /**
     * A constant indicating that a flow was suspended. There are many
     * situations that cause a flow to be suspended. The simplest way is the
     * {@link Flow#suspend(Object)} method, but some APIs such as
     * {@link Flow#receive(Object)} may cause a flow to be temporarily
     * suspended.
     */
    int PE_FLOW_SUSPENDED = 3;

    /**
     * A constant indicating that a suspended flow is about to be resumed. There
     * are many situations that cause a flow to be resumed. For example, if
     * {@link Flow#split(int)} is issued on a flow that belongs to a task,
     * new suspended flows are created, then added to the task, and finally
     * they are resumed. Some APIs such as {@link Flow#receive(Object)} may
     * cause a flow to be temporarily suspended and resumed.
     */
    int PE_RESUMING_FLOW = 4;

    /**
     * Called when an event happens on a task. The implementation may query,
     * {@linkplain Task#activate() activate} or {@linkplain Task#passivate()
     * passivate} the task on which the event occurred. Normally this method
     * is implemented to detect when a {@link Task} becomes idle, so it can be
     * {@linkplain Task#passivate() passivated} immediately or in the future.
     * <p>
     * This method should execute briefly and must not throw an exception. Any
     * thrown exception is simply logged and then ignored. This method is called
     * inside a synchronized block on the informed task, so that
     * {@link Thread#holdsLock(Object) Thread.holdsLock(sender)} always return
     * <code>true</code> during execution of this method.
     * 
     * @param sender The task on which the event happened.
     * @param event The event that happened on the task. See the
     *        <code>PE_</code> constants declared on this interface.
     * @param flow The flow associated with the event. This is the flow being
     *        added, removed, suspended or resumed. May be <code>null</code> if
     *        the event is not about a flow.
     */
    void onEvent(Task sender, int event, Flow flow);

}
