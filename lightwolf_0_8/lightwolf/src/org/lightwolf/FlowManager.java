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

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.lightwolf.tools.SimpleFlowManager;

public abstract class FlowManager {

    private static FlowManager _default = new SimpleFlowManager("defaultFlowManager");
    private static final ThreadLocal<FlowManager> next = new ThreadLocal<FlowManager>();

    public static FlowManager getDefault() {
        return _default;
    }

    public static void setDefault(FlowManager manager) {
        if (manager == null) {
            throw new NullPointerException();
        }
        // TODO: Check permissions.
        _default = manager;
    }

    public static FlowManager getNext() {
        FlowManager ret = next.get();
        if (ret == null) {
            return _default;
        }
        return ret;
    }

    public static FlowManager setNext(FlowManager manager) {
        // TODO: Check permissions.
        FlowManager ret = next.get();
        next.set(manager);
        return ret;
    }

    protected static void clearThread() {
        next.set(null);
        Flow.clearThread();
    }

    protected static void prepareFork(Flow flow, int n) {
        Fork base = new Fork(flow.currentFork, null, 0, n);
        flow.currentFork = base;
    }

    protected static Flow createBranch(Flow flow, int number) {
        Fork base = flow.currentFork;
        if (base == null || base.number != 0) {
            throw new IllegalArgumentException("Flow has no prepared fork.");
        }
        Flow branch = flow.copy();
        branch.setCurrentFork(new Fork(null, base, number, 0));
        return branch;
    }

    protected abstract void fork(Flow requester, int n);

    protected abstract void doStreamedFork(Flow requester, int n);

    protected abstract ScheduledFuture<?> schedule(Callable<?> callable, long delay, TimeUnit unit);

    protected abstract <V> Future<V> submit(Flow requester, Object message);

}
