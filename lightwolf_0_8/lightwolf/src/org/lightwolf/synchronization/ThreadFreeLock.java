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

import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

import org.lightwolf.DelayedCallSignal;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.SuspendSignal;

public class ThreadFreeLock {

    private Flow owner;
    private int reentrancy;
    private LinkedList<IWaiter> waiters;

    @FlowMethod
    public synchronized void lock() {
        Flow flow = checkFlow();
        if (internalLock(flow)) {
            return;
        }
        ForeverWaiter waiter = new ForeverWaiter(this);
        assert waiter.getFlow() == null;
        addWaiter(waiter);
        Flow.signal(waiter);
        assert checkFlow() == flow;
        assert waiter.getFlow() == flow;
        assert !waiters.contains(waiter);
        assert owner == flow;
    }

    @FlowMethod
    public synchronized boolean tryLock() {
        Flow current = checkFlow();
        return internalLock(current);
    }

    @FlowMethod
    public synchronized boolean tryLock(long time, TimeUnit unit) {
        if (unit == null) {
            throw new NullPointerException();
        }
        Flow flow = checkFlow();
        if (internalLock(flow)) {
            return true;
        }
        if (time <= 0) {
            return false;
        }
        TimeoutWaiter waiter = new TimeoutWaiter(this, time, unit);
        addWaiter(waiter);
        Flow.signal(waiter);
        assert checkFlow() == flow;
        assert waiter.getFlow() == flow;
        assert !waiters.contains(waiter);
        if (waiter.expired()) {
            return false;
        }
        assert owner == flow;
        return true;
    }

    public synchronized void unlock() {
        if (!isMine()) {
            throw new IllegalStateException("Lock not owned by current flow thread.");
        }
        if (reentrancy > 0) {
            --reentrancy;
            return;
        }
        if (waiters == null) {
            owner = null;
            return;
        }
        IWaiter waiter = waiters.poll();
        if (waiter == null) {
            owner = null;
            return;
        }
        waiter.cancel();
        Flow next = waiter.getFlow();
        try {
            // The next might have just issued signal, and might not be suspended yet.
            next.waitSuspended();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        assert next.isSuspended();
        owner = next;
        owner.activate();
    }

    public synchronized boolean isMine() {
        return isOwner(Flow.current());
    }

    public synchronized boolean isOwner(Flow thread) {
        // TODO: This method only works if the thread is the last in chain.
        Flow current = thread;
        while (current != null) {
            if (owner == current) {
                return true;
            }
            current = current.getPrevious();
        }
        return false;
    }

    public synchronized boolean isFree() {
        return owner == null;
    }

    synchronized void notifyExpired(TimeoutWaiter waiter) {
        if (waiters.remove(waiter)) {
            waiter.getFlow().activate();
        }
    }

    private boolean internalLock(Flow current) {
        if (owner == null) {
            assert waiters == null || waiters.isEmpty();
            assert reentrancy == 0;
            owner = current;
            return true;
        }
        if (isOwner(current)) {
            ++reentrancy;
            return true;
        }
        return false;
    }

    private void addWaiter(IWaiter locked) {
        if (waiters == null) {
            waiters = new LinkedList<IWaiter>();
        } else {
            assert !waiters.contains(locked);
        }
        waiters.add(locked);
    }

    private Flow checkFlow() {
        Flow current = Flow.current();
        if (current == null) {
            throw new IllegalStateException("No current flow thread.");
        }
        if (!current.isActive()) {
            throw new IllegalStateException("Current flow thread is not active.");
        }
        return current;
    }

    private interface IWaiter {

        Flow getFlow();

        void cancel();

    }

    private class ForeverWaiter extends SuspendSignal implements IWaiter {

        private static final long serialVersionUID = 1L;

        public ForeverWaiter(Object argument) {
            super(argument);
        }

        public void cancel() {
        // We are not holding anything. So, nothing to do.
        }

    }

    private static class TimeoutWaiter extends DelayedCallSignal implements IWaiter {

        private static final long serialVersionUID = 1L;
        private boolean expired;
        private final ThreadFreeLock lock;

        TimeoutWaiter(ThreadFreeLock lock, long delay, TimeUnit unit) {
            super(delay, unit);
            this.lock = lock;
        }

        public Object call() throws Exception {
            try {
                expired = true;
                lock.notifyExpired(this);
                return null;
            } finally {
                notifyDone();
            }
        }

        public boolean expired() {
            return expired;
        }

    }

}
