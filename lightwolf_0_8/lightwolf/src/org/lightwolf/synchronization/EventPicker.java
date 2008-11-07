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
package org.lightwolf.synchronization;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.lightwolf.Continuation;
import org.lightwolf.DelayedCallSignal;
import org.lightwolf.Flow;
import org.lightwolf.FlowLocal;
import org.lightwolf.FlowMethod;

public class EventPicker {

    private static final int REGISTERING = 0;
    private static final int WAITING = 1;
    private static final int HANDLING = 2;
    private final HashMap<Object, LinkedList<FlowContext>> queues = new HashMap<Object, LinkedList<FlowContext>>();
    private final FlowLocal<State> state = new FlowLocal<State>();

    @FlowMethod
    public void pick() {
        State st = state.get();
        if (st == null) {
            // Nothing to pick.
            return;
        }
        if (st.state == HANDLING) {
            state.set(null); // Set state to null, so we can reuse this picker on this flow.
            return;
        }
        if (st.state != REGISTERING) {
            throw new AssertionError();
        }
        st.state = WAITING;
        waitForEvents(st);
    }

    // Do not inline this method, because we need this synchronized block.
    @FlowMethod
    private synchronized void waitForEvents(State st) throws AssertionError {
        boolean suspend = false;
        Map<Object, FlowContext> events = st.events;
        if (events != null) {
            // Copy all events from the state to the queues.
            for (Entry<Object, FlowContext> entry : events.entrySet()) {
                Object key = entry.getKey();
                FlowContext context = entry.getValue();
                LinkedList<FlowContext> queue = queues.get(key);
                if (queue == null) {
                    queue = new LinkedList<FlowContext>();
                    queues.put(key, queue);
                } else {
                    assert !queue.contains(context);
                }
                queue.add(context);
                suspend = true;
            }
        }
        FlowContext context = st.timeoutContext;
        if (context != null) {
            Flow.signal(new TimeoutWaiter(context, st.timeoutTime, st.timeoutUnit));
            throw new AssertionError(); // Must not wake-up here.
        } else if (suspend) {
            // Wait until some event wake me up.
            Flow.suspend(this);
            throw new AssertionError(); // Must not wake-up here.
        } else {
            // When suspend==false, this means that no event was registered. Hence we must not wait.
            state.set(null); // Set state to null, so we can reuse this picker on this flow.
        }
    }

    @FlowMethod
    public boolean onEvent(Object event) {
        State st = getState();
        if (st.state == HANDLING) {
            // We are handling another event, so we must not wait.
            return false;
        }
        FlowContext context = currentContext(st);
        if (context == null) {
            assert st.state == HANDLING;
            return true;
        }
        st.onEvent(event, context);
        return false;
    }

    @FlowMethod
    public boolean onTimeout(long time, TimeUnit unit) {
        State st = getState();
        if (st.state == HANDLING) {
            // We are handling another event, so we must not wait.
            return false;
        }
        FlowContext context = currentContext(st);
        if (context == null) {
            assert st.state == HANDLING;
            return true;
        }
        st.onTimeout(time, unit, context);
        return false;
    }

    public void notify(Object event, Object message) {
        for (;;) {
            FlowContext context;
            synchronized(this) {
                LinkedList<FlowContext> queue = queues.get(event);
                if (queue == null) {
                    return;
                }
                context = queue.poll();
                if (context == null) {
                    return;
                }
            }
            if (wakeUp(context, message)) {
                return;
            }
        }
    }

    public void notifyAll(Object event, Object message) {
        LinkedList<FlowContext> queue;
        synchronized(this) {
            // Remove all queues of this event, since we'll schedule every flow.
            queue = queues.remove(event);
        }
        for (;;) {
            FlowContext context = queue.poll();
            if (context == null) {
                break;
            }
            wakeUp(context, message);
        }
    }

    private State getState() {
        State ret = state.get();
        if (ret == null) {
            ret = new State(Flow.current());
            state.set(ret);
        }
        return ret;
    }

    private static boolean wakeUp(FlowContext context, Object message) {
        State st = context.getState();
        synchronized(st) {
            switch (st.state) {
                case WAITING:
                    st.state = HANDLING;
                    break;
                case HANDLING:
                    return false;
                default:
                    throw new AssertionError();
            }
        }
        Flow flow = st.flow;
        context.placeOnCheckpointAndForget(flow);
        flow.activate(message);
        return true;
    }

    @FlowMethod
    private static FlowContext currentContext(State state) {
        FlowContext cont = new FlowContext(state);
        return cont.checkpoint() ? cont : null;
    }

    private static class State extends Continuation {

        final Flow flow;
        int state;
        Map<Object, FlowContext> events;
        long timeoutTime;
        TimeUnit timeoutUnit;
        FlowContext timeoutContext;

        State(Flow flow) {
            this.flow = flow;
        }

        void onEvent(Object event, FlowContext context) {
            if (event == null || context == null) {
                throw new NullPointerException();
            }
            if (events == null) {
                events = new HashMap<Object, FlowContext>();
            } else if (events.containsKey(event)) {
                throw new IllegalArgumentException("Event already registered by flow.");
            }
            events.put(event, context);
        }

        void onTimeout(long time, TimeUnit unit, FlowContext context) {
            if (unit == null || context == null) {
                throw new NullPointerException();
            }
            if (timeoutContext != null) {
                throw new IllegalStateException("Timeout event already registered by flow.");
            }
            timeoutTime = time;
            timeoutUnit = unit;
            timeoutContext = context;
        }

    }

    private static class TimeoutWaiter extends DelayedCallSignal {

        private static final long serialVersionUID = 1L;
        private final FlowContext context;

        TimeoutWaiter(FlowContext context, long delay, TimeUnit unit) {
            super(delay, unit);
            this.context = context;
        }

        public Object call() throws Exception {
            try {
                return wakeUp(context, null);
            } finally {
                notifyDone();
            }
        }

    }

    private static class FlowContext extends Continuation {

        private final State state;

        FlowContext(State state) {
            this.state = state;
        }

        State getState() {
            return state;
        }

    }

}
