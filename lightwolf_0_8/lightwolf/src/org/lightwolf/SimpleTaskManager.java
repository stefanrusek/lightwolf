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

import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.WeakHashMap;
import java.util.Map.Entry;

public final class SimpleTaskManager extends TaskManager {

    private static final long serialVersionUID = 1L;

    private static final Object RECEIVE_SINGLE = new Object();
    private static final Object RECEIVE_MANY = new Object();
    private static final WeakHashMap<Key, SimpleTaskManager> activeManagers = new WeakHashMap<Key, SimpleTaskManager>();

    private static SimpleTaskManager restore(Key key) {
        return activeManagers.get(key);
    }

    private final Key serialKey;
    private final HashMap<Object, LinkedList<Flow>> keyWaiters;
    private final HashMap<IMatcher, LinkedList<Flow>> matcherWaiters;
    private final HashMap<Object, MessageQueue> msgQueues;

    public SimpleTaskManager(String name) {
        serialKey = new Key(name);
        synchronized(activeManagers) {
            activeManagers.put(serialKey, this);
        }
        keyWaiters = new HashMap<Object, LinkedList<Flow>>();
        matcherWaiters = new HashMap<IMatcher, LinkedList<Flow>>();
        msgQueues = new HashMap<Object, MessageQueue>();
    }

    @Override
    protected synchronized void notify(Object destKey, Object message) {
        LinkedList<Flow> list;
        list = keyWaiters.get(destKey);
        if (list != null) {
            dispatch(message, list);
            if (list.isEmpty()) {
                keyWaiters.remove(destKey);
            }
        }
        for (Iterator<Entry<IMatcher, LinkedList<Flow>>> i = matcherWaiters.entrySet().iterator(); i.hasNext();) {
            Entry<IMatcher, LinkedList<Flow>> item = i.next();
            if (item.getKey().match(destKey)) {
                list = item.getValue();
                dispatch(message, list);
                if (list.isEmpty()) {
                    i.remove();
                }
            }
        }
    }

    @Override
    @FlowMethod
    protected synchronized Object wait(Object matcher) {
        Flow flow = Flow.current();
        addWaiter(matcher, flow);
        return Flow.suspend(RECEIVE_SINGLE);
    }

    @Override
    @FlowMethod
    protected synchronized Object waitMany(Object matcher) {
        Flow flow = Flow.current();
        addWaiter(matcher, flow);
        return Flow.suspend(RECEIVE_MANY);
    }

    @Override
    @FlowMethod
    protected synchronized void send(Object address, Object message) {
        MessageQueue queue = msgQueues.get(address);
        if (queue == null) {
            queue = new MessageQueue(address);
            msgQueues.put(address, queue);
        }
        if (!queue.send(Flow.current(), message)) {
            Flow.suspend(message);
        }
    }

    @Override
    @FlowMethod
    protected synchronized void sendThrowing(Object address, Throwable exception) {
        send(address, new ExceptionEnvelope(exception));
    }

    @Override
    @FlowMethod
    protected synchronized Object receive(Object address) {
        MessageQueue queue = getQueue(address);
        try {
            Flow flow = Flow.current();
            queue.bind(flow);
            try {
                if (queue.receive()) {
                    return queue.getMessage();
                }
                return Flow.suspend();
            } finally {
                queue.unbind(flow);
            }
        } finally {
            releaseQueue(queue);
        }
    }

    @Override
    @FlowMethod
    protected synchronized Object receiveMany(Object address) {
        boolean success = false;
        MessageQueue queue = getQueue(address);
        try {
            ReceiveManyContinuation cont = new ReceiveManyContinuation(Task.safeCurrent());
            queue.bind(cont);
            try {
                if (cont.checkpoint()) {
                    while (queue.receive()) {
                        cont.activate(queue.getMessage());
                    }
                    Flow.end();
                }
                success = true;
                return cont.getResult();
            } finally {
                if (!success) {
                    queue.unbind(cont);
                }
            }
        } finally {
            if (!success) {
                releaseQueue(queue);
            }
        }
    }

    @Override
    @FlowMethod
    protected Connection accept(Object matcher) {
        throw new AssertionError("Not implemented.");
    }

    @Override
    @FlowMethod
    protected Connection acceptMany(Object matcher) {
        throw new AssertionError("Not implemented.");
    }

    @Override
    @FlowMethod
    protected Connection connect(Object matcher) {
        throw new AssertionError("Not implemented.");
    }

    @Override
    @FlowMethod
    protected Connection connectMany(Object matcher) {
        throw new AssertionError("Not implemented.");
    }

    @Override
    protected void notifyInterrupt(Task task) {
        LinkedList<ReceiveManyContinuation> continuations = new LinkedList<ReceiveManyContinuation>();
        synchronized(this) {
            for (Entry<Object, MessageQueue> entry : msgQueues.entrySet()) {
                MessageQueue queue = entry.getValue();
                if (queue.continuation.task == task) {
                    continuations.add(queue.continuation);
                }
            }
        }
        for (ReceiveManyContinuation cont : continuations) {
            Flow flow = Flow.newFlow();
            flow.task = task;
            cont.placeOnCheckpoint(flow);
        }
    }

    private void addWaiter(Object matcher, Flow flow) {
        LinkedList<Flow> list;
        if (matcher instanceof IMatcher) {
            list = getMatcherList((IMatcher) matcher);
        } else {
            list = getKeyList(matcher);
        }
        list.add(flow);
    }

    private void dispatch(Object message, LinkedList<Flow> list) {
        for (Iterator<Flow> i = list.iterator(); i.hasNext();) {
            Flow flow = i.next();
            // SimpleFlowManager.printOrigin();
            Object result = ((FlowSignal) flow.getResult()).getResult();
            if (result == RECEIVE_MANY) {
                flow = flow.copy();
            } else if (result == RECEIVE_SINGLE) {
                i.remove();
            } else {
                throw new AssertionError("Unexpected result: " + result);
            }
            flow.activate(message);
        }
    }

    private MessageQueue getQueue(Object address) {
        MessageQueue queue = msgQueues.get(address);
        if (queue == null) {
            queue = new MessageQueue(address);
            msgQueues.put(address, queue);
        }
        return queue;
    }

    private void releaseQueue(MessageQueue queue) {
        if (!queue.hasSenders()) {
            msgQueues.remove(queue.address);
        }
    }

    private LinkedList<Flow> getKeyList(Object key) {
        LinkedList<Flow> ret = keyWaiters.get(key);
        if (ret == null) {
            ret = new LinkedList<Flow>();
            keyWaiters.put(key, ret);
        }
        return ret;
    }

    private LinkedList<Flow> getMatcherList(IMatcher matcher) {
        LinkedList<Flow> ret = matcherWaiters.get(matcher);
        if (ret == null) {
            ret = new LinkedList<Flow>();
            matcherWaiters.put(matcher, ret);
        }
        return ret;
    }

    private Object writeReplace() {
        return serialKey;
    }

    private static final class Key implements Serializable {

        private static final long serialVersionUID = 1L;
        private final String id;

        Key(String id) {
            this.id = id.intern();
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Key)) {
                return false;
            }
            return ((Key) obj).id.equals(id);
        }

        private Object readResolve() throws ObjectStreamException {
            SimpleTaskManager ret = restore(this);
            if (ret == null) {
                throw new InvalidObjectException("Coult not find " + SimpleTaskManager.class.getName() + " instance named '" + id + "'.");
            }
            return ret;
        }

    }

    private static final class MessageQueue implements Serializable {

        private static final long serialVersionUID = 1L;
        private final Object address;
        private final LinkedList<Flow> senders;
        private Flow receiver;
        private ReceiveManyContinuation continuation;
        private Flow freeSender;

        MessageQueue(Object address) {
            this.address = address;
            senders = new LinkedList<Flow>();
        }

        void bind(Flow recvFlow) {
            if (recvFlow == null) {
                throw new NullPointerException();
            }
            if (receiver != null || continuation != null) {
                throw new AddressAlreadyInUseException(address);
            }
            receiver = recvFlow;
        }

        void unbind(Flow recvFlow) {
            if (recvFlow == null) {
                throw new NullPointerException();
            }
            if (receiver != recvFlow) {
                throw new IllegalStateException("Flow is not bound.");
            }
            assert continuation == null;
            receiver = null;
        }

        void bind(ReceiveManyContinuation newCont) {
            if (newCont == null) {
                throw new NullPointerException();
            }
            if (receiver != null || continuation != null) {
                throw new AddressAlreadyInUseException(address);
            }
            continuation = newCont;
        }

        void unbind(ReceiveManyContinuation cont) {
            if (cont == null) {
                throw new NullPointerException();
            }
            if (continuation != cont) {
                throw new IllegalStateException("Flow is not bound.");
            }
            assert receiver == null;
            continuation = null;
        }

        boolean send(Flow sender, Object msg) {
            if (receiver != null) {
                assert continuation == null;
                assert senders.isEmpty();
                if (!(msg instanceof ExceptionEnvelope)) {
                    receiver.activate(msg);
                } else {
                    ExceptionEnvelope envelope = (ExceptionEnvelope) msg;
                    receiver.activateThrowing(envelope.exception);
                }
                return true;
            }
            if (continuation != null) {
                assert receiver == null;
                Flow flow = Flow.newFlow();
                flow.task = continuation.task;
                continuation.placeOnCheckpoint(flow);
                if (!(msg instanceof ExceptionEnvelope)) {
                    flow.activate(msg);
                } else {
                    ExceptionEnvelope envelope = (ExceptionEnvelope) msg;
                    flow.activateThrowing(envelope.exception);
                }
                return true;
            }
            senders.add(sender);
            return false;
        }

        boolean receive() {
            assert freeSender == null;
            freeSender = senders.poll();
            return freeSender != null;
        }

        Object getMessage() {
            Flow curSender = freeSender;
            assert curSender != null;
            freeSender = null;
            Object ret = ((FlowSignal) curSender.getResult()).getResult();
            curSender.activate();
            return ret;
        }

        boolean hasSenders() {
            return !senders.isEmpty();
        }

    }

    private static final class ReceiveManyContinuation extends Continuation {

        private final Task task;

        ReceiveManyContinuation(Task task) {
            this.task = task;
        }

    }

    private static final class ExceptionEnvelope {

        Throwable exception;

        public ExceptionEnvelope(Throwable exception) {
            this.exception = exception;
        }

    }

}
