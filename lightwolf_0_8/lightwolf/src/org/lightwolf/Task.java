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

import static org.lightwolf.TaskState.ACTIVE;
import static org.lightwolf.TaskState.INTERRUPTED;
import static org.lightwolf.TaskState.PASSIVE;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

/**
 * An unit of work composed by a set of related flows. A task is a manageable
 * unit of work composed by one or more {@linkplain Flow flows}. It can be
 * monitored, paused or interrupted. If certain conditions are met (such as
 * using only primitives and serializable objects), a task can also be
 * serialized and deserialized, which allows development of long running
 * processes in pure Java language.
 * <p>
 * This class contains utilities for communication and synchronization between
 * task flows:
 * <ul>
 * <li>The {@link Flow#wait(Object) wait} and
 * {@link Flow#notifyAll(Object, Object) notify} methods can be used to wait-for
 * and get/provide information about specific conditions and events.</li>
 * <li>The {@link Flow#send(Object, Object) send} and
 * {@link Flow#receive(Object) receive} methods allow safe delivery of one-way
 * messages.</li>
 * <li>The {@link Flow#call(Object, Object) call} and {@link Flow#serve(Object)
 * serve} methods provide a simple request-response mechanism.</li>
 * <li>A connection can be established between two flows, providing both
 * synchronous and asynchronous modes of operation.</li>
 * </ul>
 * A task is created by calling its {@linkplain Task#Task() constructor}.
 * Initially the task contains no flow. A flow can join a task by invoking
 * {@link Flow#joinTask(Task)}.
 * <p>
 * This class can be subclassed. If the implementor wish to provide support for
 * long-running-process, the methods {@link #storeData(Object)},
 * {@link #loadData()} and {@link #discardData()} must all be implemented.
 * 
 * @see Flow
 * @author Fernando Colombo
 */
public class Task implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Returns the current task. This method returns non-null if the
     * {@linkplain current current flow} is running inside a task. Otherwise it
     * returns <code>null</code>. If there is no current flow, this method also
     * returns <code>null</code>.
     * 
     * @return The current task, or <code>null</code> if the current flow is not
     *         running in the context of a task, or if there is no current flow.
     */
    public static Task current() {
        Flow flow = Flow.current();
        return flow == null ? null : flow.task;
    }

    /**
     * Returns the current task, or throws an exception if there is no current
     * task. This method is similar to {@link #current()}, except in that it
     * never returns <code>null</code>. In the absence of a current task, it
     * will throw an exception.
     * 
     * @return The current task.
     * @throws IllegalStateException If there is no current task.
     */
    public static Task safeCurrent() {
        Task ret = current();
        if (ret == null) {
            throw new IllegalStateException("No current task.");
        }
        return ret;
    }

    final TaskManager manager;
    private TaskState state;
    private FlowContext properties;
    private final HashSet<Flow> flows;
    private int activeFlows;
    private int suspendedFlows;
    private long passivationTime;
    private transient ITaskListener listeners;

    /**
     * Creates a new task. The new task will belong to the
     * {@linkplain TaskManager#getDefault() default} task manager. The task will
     * contain no flow. To add a flow, it is necessary to call
     * {@link Flow#joinTask(Task)} specifying this task.
     */
    public Task() {
        this(TaskManager.getDefault());
    }

    public Task(TaskManager manager) {
        if (manager == null) {
            throw new NullPointerException();
        }
        this.manager = manager;
        state = ACTIVE;
        flows = new HashSet<Flow>();
    }

    /**
     * Return an <code>int</code> whose value represents this task state.
     * 
     * @see #ACTIVE
     * @see #PASSIVE
     * @see #INTERRUPTED
     */
    public final TaskState getState() {
        return state;
    }

    public synchronized Object getProperty(String propId) {
        if (propId == null) {
            throw new NullPointerException();
        }
        if (properties == null) {
            return null;
        }
        return properties.getProperty(propId);
    }

    public synchronized Object setProperty(String propId, Object value) {
        if (propId == null) {
            throw new NullPointerException();
        }
        if (properties == null) {
            if (value == null) {
                return null;
            }
            properties = new FlowContext();
        }
        return properties.setProperty(propId, value);
    }

    /**
     * The number of active flows in this task. This number can vary quickly as
     * flows are joined, leaved, suspended and resumed.
     */
    public final int activeFlows() {
        return activeFlows;
    }

    /**
     * The number of suspended flows in this task. This number can vary quickly
     * as flows are joined, leaved, suspended and resumed.
     */
    public final int suspendedFlows() {
        return suspendedFlows;
    }

    public final synchronized Flow[] getFlows() {
        Flow[] ret = new Flow[flows.size()];
        return flows.toArray(ret);
    }

    /**
     * Adds an event listener to this task.
     * 
     * @param listener The listener to be added. Must not be <code>null</code>
     *        otherwise a {@link NullPointerException} is thrown.
     * @return <code>true</code> if the listener was added, <code>false</code>
     *         if the informed listener was added this invocation.
     * @see ITaskListener
     * @see #removeEventListener(ITaskListener)
     */
    public final boolean addEventListener(ITaskListener listener) {
        if (listener == null) {
            throw new NullPointerException();
        }
        if (listeners == null) {
            listeners = listener;
            return true;
        }
        if (listeners.equals(listener)) {
            return false;
        }
        if (listeners instanceof TaskEventDispatcher) {
            return ((TaskEventDispatcher) listeners).add(listener);
        }
        listeners = new TaskEventDispatcher(listeners, listener);
        return true;
    }

    /**
     * Removes an event listener from this task.
     * 
     * @param listener The listener to be removed. Must not be <code>null</code>
     *        otherwise a {@link NullPointerException} is thrown.
     * @return <code>true</code> if the listener was removed, <code>false</code>
     *         if the informed listener was not found in the internal list of
     *         listeners.
     * @see #addEventListener(ITaskListener)
     */
    public final boolean removeEventListener(ITaskListener listener) {
        if (listener == null) {
            throw new NullPointerException();
        }
        if (listeners == null) {
            return false;
        }
        if (listeners.equals(listener)) {
            listeners = null;
            return true;
        }
        if (listeners instanceof TaskEventDispatcher) {
            return ((TaskEventDispatcher) listeners).remove(listener);
        }
        return false;
    }

    public synchronized void interrupt() {
        if (state == INTERRUPTED) {
            return;
        }
        try {
            activate();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        if (state != ACTIVE) {
            throw new IllegalStateException("Cannot interrupt while task is " + state + ".");
        }
        state = INTERRUPTED;
        manager.notifyInterrupt(this);
        for (Flow flow : flows) {
            // TODO: interrupt() might throw an exception, lefting half of the flows interrupted and half not.
            flow.interrupt();
        }
    }

    FlowContext readContext() {
        return properties == null ? null : properties.copy();
    }

    final void add(Flow flow) {
        add(flow, false);
    }

    synchronized final void add(Flow flow, boolean force) {
        assert Thread.holdsLock(this);
        if (flow.task != null) {
            if (flow.task == this) {
                assert flows.contains(flow);
                throw new IllegalArgumentException("Flow already belongs to this task.");
            }
            assert !flows.contains(flow);
            throw new IllegalArgumentException("Flow belongs to another task.");
        }
        switch (state) {
            case ACTIVE:
                break;
            case INTERRUPTED:
                if (force) {
                    break;
                }
                // Yes, fall.
            default:
                throw new IllegalStateException("Cannot add flows while task is " + state);
        }
        boolean added = flows.add(flow);
        assert added;
        flow.task = this;
        if (flow.isActive()) {
            ++activeFlows;
        } else {
            assert flow.isSuspended() || force;
            ++suspendedFlows;
        }
        notify(ITaskListener.PE_FLOW_ADDED, flow);
    }

    synchronized final void remove(Flow flow) {
        assert Thread.holdsLock(this);
        if (flow.task != this) {
            assert !flows.contains(flow);
            throw new IllegalArgumentException("Flow does not belong to this task.");
        }
        if (state != ACTIVE && state != INTERRUPTED) {
            throw new IllegalStateException("Cannot remove flows while task is " + state);
        }
        boolean removed = flows.remove(flow);
        assert removed;
        flow.task = null;
        if (flow.isActive()) {
            --activeFlows;
        } else {
            assert flow.isSuspended();
            --suspendedFlows;
        }
        notify(ITaskListener.PE_FLOW_REMOVED, flow);
    }

    synchronized void notifySuspend(Flow flow) {
        assert Thread.holdsLock(this);
        assert state == ACTIVE;
        assert flows.contains(flow);
        --activeFlows;
        ++suspendedFlows;
        notify(ITaskListener.PE_FLOW_SUSPENDED, flow);
    }

    synchronized void notifyResume(Flow flow) {
        assert Thread.holdsLock(this);
        assert flows.contains(flow);
        try {
            activate();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to resume flow due to I/O error.", e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Unable to resume flow because a class it uses wasn't found.", e);
        }
        notify(ITaskListener.PE_RESUMING_FLOW, flow);
        --suspendedFlows;
        ++activeFlows;
    }

    void checkNotInterrupted() {
        if (state == INTERRUPTED) {
            throw new FlowInterruptedException();
        }
    }

    protected void notify(int event, Flow flow) {
        if (listeners == null) {
            return;
        }
        try {
            listeners.onEvent(this, event, flow);
        } catch (Throwable e) {
            // TODO: Should log this somewhere, not on standard error output. 
            e.printStackTrace();
        }
    }

    /**
     * Stores this task and all its flows on a place outside memory. The actual
     * storage place is defined by subclasses of {@link Task}.
     * <p>
     * If all of this task flows are suspended, this method stores all flow data
     * on an external storage and then returns <code>true</code>, indicating
     * that the task was passivated. If this task was already passive when this
     * method is called, it simply returns <code>true</code> doing nothing.
     * <p>
     * If the task of storing task data on the external storage fails, this
     * method throws {@link IOException} and the task is kept active.
     * <p>
     * If one or more of this task flows is {@linkplain Flow#isActive() active},
     * the task is kept active and this method returns <code>false</code>.
     * 
     * @return <code>true</code> if the task was passivated, <code>false</code>
     *         otherwise.
     * @throws IOException If some error happens during access to storage.
     * @see #activate()
     * @see #ACTIVE
     * @see #PASSIVE
     */
    public final synchronized boolean passivate() throws IOException {
        if (state == PASSIVE) {
            return true;
        }
        if (state != ACTIVE) {
            throw new IllegalStateException("Cannot passivate while task is " + state);
        }
        if (activeFlows() != 0) {
            return false;
        }
        Flow[] fs = getFlows();
        FlowData[] data = new FlowData[fs.length];
        int lastSuccess = -1;
        try {
            passivationTime = System.currentTimeMillis();
            Random r = new Random(passivationTime);
            for (int i = 0; i < fs.length; ++i) {
                data[i] = fs[i].fetchState(r.nextLong());
                lastSuccess = i;
            }
            storeData(data);
            state = PASSIVE;
            lastSuccess = -1;
        } finally {
            for (int i = 0; i <= lastSuccess; ++i) {
                fs[i].restoreState(data[i]);
            }
        }
        return true;
    }

    /**
     * Reloads this task and all its flows from external storage back to memory.
     * If this task is already active, this method does nothing. Otherwise, this
     * method does the opposite of {@link #passivate()}.
     * 
     * @throws IOException If some error happens during access to storage.
     * @throws ClassNotFoundException If the class is not
     * @see #passivate()
     * @see #ACTIVE
     * @see #PASSIVE
     */
    public final synchronized void activate() throws IOException, ClassNotFoundException {
        if (state == ACTIVE || state == INTERRUPTED) {
            return;
        }
        if (state != PASSIVE) {
            throw new IllegalStateException("Cannot activate while task is " + state);
        }
        Object rawData = loadData();
        if (!(rawData instanceof FlowData[])) {
            throw new IOException("Bad data: object should be instance of FlowData[].");
        }
        Flow[] fs = getFlows();
        FlowData[] data = (FlowData[]) rawData;
        if (data.length != fs.length) {
            throw new IOException("Bad data: number of data entries should be " + fs.length + ", not " + data.length + ".");
        }
        Random r = new Random(passivationTime);
        for (int i = 0; i < fs.length; ++i) {
            if (data[i] == null) {
                throw new IOException("Bad data: flow data entry is null.");
            }
            if (data[i].id != r.nextLong()) {
                throw new IOException("Bad data: invalid flow data entry id.");
            }
        }
        int lastRestore = -1;
        try {
            for (int i = 0; i < fs.length; ++i) {
                fs[i].restoreState(data[i]);
                lastRestore = i;
            }
            state = ACTIVE;
            discardData();
        } finally {
            if (state != ACTIVE) {
                for (int i = 0; i <= lastRestore; ++i) {
                    fs[i].fetchState(-1);
                }
            }
        }
    }

    /**
     * Called by {@link #passivate()} to store data on some media. The default
     * implementation throws {@link IllegalStateException} indicating that the
     * task cannot be passivated.
     * <p>
     * This method must be implemented along with {@link #loadData()} and
     * {@link #discardData()} by subclasses that support long-running-processes.
     * The implementation must associate the informed data with this task. This
     * method might be called more than once. Any data stored by the previous
     * invocation to this method must be discarded.
     * <p>
     * This method typically serializes the informed <code>data</code> object,
     * so that an unserialized version is returned by the next call to
     * {@link #loadData()}.
     * 
     * @param data A serializable object to be stored on some media.
     * @throws IOException If some error happens during access to storage.
     */
    protected void storeData(Object data) throws IOException {
        throw new IllegalStateException("Instances of " + getClass().getName() + " cannot be passivated.");
    }

    /**
     * Called by {@link #activate()} to retrieve data stored by
     * {@link #storeData(Object)}. The default implementation throws
     * {@link AssertionError}.
     * <p>
     * This method must be implemented along with {@link #storeData(Object)} and
     * {@link #discardData()} by subclasses that support long-running-processes.
     * This method must not discard data from the storage.
     * 
     * @return A serializable object that was read from storage.
     * @throws IOException If some error happens during access to storage.
     * @throws ClassNotFoundException If the system can't find a class for a
     *         serialized object while assembling the result.
     */
    protected Object loadData() throws IOException, ClassNotFoundException {
        throw new AssertionError();
    }

    /**
     * Called to indicate that the stored data is not necessary anymore. The
     * default implementation throws {@link AssertionError}.
     * <p>
     * This method must be implemented along with {@link #storeData(Object)} and
     * {@link #loadData()} by subclasses that support long-running-processes.
     * This method must simply discard data from the storage.
     * 
     * @throws IOException If some error happens during the storage access.
     */
    protected void discardData() throws IOException {
        throw new AssertionError();
    }

    private static class TaskEventDispatcher implements ITaskListener {

        private final ArrayList<ITaskListener> items;

        TaskEventDispatcher(ITaskListener l1, ITaskListener l2) {
            items = new ArrayList<ITaskListener>(2);
            items.add(l1);
            items.add(l2);
        }

        boolean add(ITaskListener listener) {
            if (items.contains(listener)) {
                return false;
            }
            items.add(listener);
            return true;
        }

        boolean remove(ITaskListener listener) {
            return items.remove(listener);
        }

        public void onEvent(Task sender, int event, Flow flow) {
            for (int i = 0; i < items.size(); ++i) {
                try {
                    items.get(i).onEvent(sender, event, flow);
                } catch (Throwable e) {
                    // TODO: Should log this somewhere, not on standard error output. 
                    e.printStackTrace();
                }
            }
        }

    }

}
