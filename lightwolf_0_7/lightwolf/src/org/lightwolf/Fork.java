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

import java.util.concurrent.TimeUnit;

final class Fork {

    static final int LEAVE = 1;
    static final int MERGED = 2;
    static final int TIMEOUT = 3;

    final Fork previous;
    private final Fork base;
    final int number;
    private boolean isActive;
    private int activeForks;

    Fork(Fork previous, Fork base, int number, int activeForks) {
        this.previous = previous;
        this.base = base;
        this.number = number;
        this.activeForks = activeForks;
        isActive = true;
    }

    int merge(long timeout, TimeUnit unit) throws InterruptedException {
        checkActive();
        if (number > 0) {
            return LEAVE;
        }
        return waitJoin(timeout, unit) ? MERGED : TIMEOUT;
    }

    boolean unfork() {
        checkActive();
        if (number > 0) {
            finished();
            return false;
        }
        isActive = false;
        return true;
    }

    void finished() {
        checkActive();
        isActive = false;
        synchronized(base) {
            assert base.activeForks > 0;
            --base.activeForks;
            base.notify();
        }
    }

    private void checkActive() {
        if (!isActive) {
            throw new IllegalStateException("Fork is inactive.");
        }
    }

    private synchronized boolean waitJoin(long timeout, TimeUnit unit) throws InterruptedException {
        if (activeForks == 0) {
            return true;
        }
        if (unit == null) {
            // Wait forever.
            do {
                wait();
            } while (activeForks != 0);
            isActive = false;
            return true;
        }
        while (timeout > 0) {
            long time = System.currentTimeMillis();
            unit.timedWait(this, timeout);
            if (activeForks == 0) {
                isActive = false;
                return true;
            }
            time = System.currentTimeMillis() - time;
            timeout -= unit.convert(time, TimeUnit.MILLISECONDS);
        }
        return false;
    }

}
