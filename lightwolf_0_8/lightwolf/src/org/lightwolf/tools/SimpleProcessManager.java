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
package org.lightwolf.tools;

import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.WeakHashMap;
import java.util.Map.Entry;

import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.IMatcher;
import org.lightwolf.ProcessManager;

public class SimpleProcessManager extends ProcessManager {

    private static final WeakHashMap<Key, SimpleProcessManager> activeManagers = new WeakHashMap<Key, SimpleProcessManager>();

    private static SimpleProcessManager restore(Key key) {
        return activeManagers.get(key);
    }

    private final HashMap<Object, LinkedList<Flow>> keyWaiters;
    private final HashMap<IMatcher, LinkedList<Flow>> matcherWaiters;
    private final Key key;

    public SimpleProcessManager(String name) {
        keyWaiters = new HashMap<Object, LinkedList<Flow>>();
        matcherWaiters = new HashMap<IMatcher, LinkedList<Flow>>();
        key = new Key(name);
        synchronized(activeManagers) {
            activeManagers.put(key, this);
        }
    }

    @Override
    @FlowMethod
    protected Object receive(Object matcher) {
        return Flow.signal(new WaitForMessage(this, matcher));
    }

    @Override
    protected synchronized void send(Object destKey, Object message) {
        LinkedList<Flow> list;
        list = keyWaiters.remove(destKey);
        if (list != null) {
            dispatch(message, list);
        }
        for (Iterator<Entry<IMatcher, LinkedList<Flow>>> i = matcherWaiters.entrySet().iterator(); i.hasNext();) {
            Entry<IMatcher, LinkedList<Flow>> item = i.next();
            if (item.getKey().match(destKey)) {
                dispatch(message, item.getValue());
                i.remove();
            }
        }
    }

    private void dispatch(Object message, LinkedList<Flow> list) {
        for (;;) {
            Flow flow = list.poll();
            if (flow == null) {
                return;
            }
            flow.activate(message);
        }
    }

    synchronized void submit(WaitForMessage signal) {
        Object matcher = signal.getMatcher();
        Flow flow = signal.getFlow();
        LinkedList<Flow> list;
        if (matcher instanceof IMatcher) {
            list = getMatcherList((IMatcher) matcher);
        } else {
            list = getKeyList(matcher);
        }
        list.add(flow);
    }

    private LinkedList<Flow> getKeyList(Object addrKey) {
        LinkedList<Flow> ret = keyWaiters.get(addrKey);
        if (ret == null) {
            ret = new LinkedList<Flow>();
            keyWaiters.put(addrKey, ret);
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
            SimpleProcessManager ret = restore(this);
            if (ret == null) {
                throw new InvalidObjectException("Coult not find " + SimpleProcessManager.class.getName() + " instance named '" + id + "'.");
            }
            return ret;
        }

    }

}
