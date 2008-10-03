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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public abstract class DelayedCallSignal extends FlowSignal implements Callable {

    private static final long serialVersionUID = 1L;
    private final long delay;
    private final TimeUnit unit;
    private boolean isCanceled;
    private ScheduledFuture<?> future;
    private boolean isDone;

    public DelayedCallSignal(Object argument, long delay, TimeUnit unit) {
        super(argument);
        this.delay = delay;
        this.unit = unit;
    }

    public long getDelay() {
        return delay;
    }

    public TimeUnit getUnit() {
        return unit;
    }

    public synchronized <V> void setFuture(ScheduledFuture<V> future) {
        if (isCanceled) {
            future.cancel(false);
        } else {
            this.future = future;
        }
    }

    public synchronized void cancel() {
        isCanceled = true;
        if (future != null) {
            future.cancel(false);
        }
    }

    public <V> ScheduledFuture<V> schedule() {
        return getFlow().getManager().schedule(this, delay, unit);
    }

    public synchronized void waitDone() throws InterruptedException {
        while (!isDone) {
            wait();
        }
    }

    protected final synchronized void notifyDone() {
        if (isDone) {
            return;
        }
        isDone = true;
        notifyAll();
    }

}
