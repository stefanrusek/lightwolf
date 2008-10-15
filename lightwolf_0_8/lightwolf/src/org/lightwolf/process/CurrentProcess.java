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
package org.lightwolf.process;

import java.util.concurrent.TimeUnit;

import org.lightwolf.FlowLocal;
import org.lightwolf.FlowMethod;

public class CurrentProcess {

    private static FlowLocal<Process> current = new FlowLocal<Process>();

    public static Process getCurrent() {
        return current.get();
    }

    public static Process safeGetCurrent() {
        Process ret = getCurrent();
        if (ret != null) {
            return ret;
        }
        throw new IllegalStateException("There is no active process.");
    }

    public static Process setCurrent(Process process) {
        return current.set(process);
    }

    @FlowMethod
    public static void enter() {
        Process p = safeGetCurrent();
        p.enter();
        assert getCurrent() == p; // enter() might return in another Java thread. Check FlowThreadLocal.
    }

    public static void exit() {
        safeGetCurrent().exit();
    }

    public static void fork() {
        safeGetCurrent().fork();
    }

    @FlowMethod
    public static void join() {
        Process p = safeGetCurrent();
        p.join();
        assert getCurrent() == p; // join() might return in another Java thread. Check FlowThreadLocal.
    }

    /** Returns true if this is a fork path. Must be called inside a fork block. */
    @FlowMethod
    public static boolean onPath() {
        Process cur = safeGetCurrent();
        boolean onPath = cur.onPath();
        if (onPath) {
            // if onPath() returns true, we are in another FlowThread. Current must be null (checking FlowThreadLocal).
            cur = setCurrent(cur);
            assert cur == null;
        } else {
            // if onPath() returns false, we are in the same FlowThread (and Java Thread).
            assert getCurrent() == cur;
        }
        return onPath;
    }

    /**
     * Sends a message. Allow sending an active communication channel, such as a
     * pipe or BlockingQueue.
     */
    public static void send(Object message, Object... key) {
        throw new AssertionError();
    }

    /** Suspends until a matching message arrives. */
    public static Object receive(Object... key) {
        throw new AssertionError();
    }

    /** Invokes a service. Might return something like a pipe or BlockingQueue. */
    public static Object invoke(String service, Object... args) {
        throw new AssertionError();
    }

    public static void pick(long timeout, TimeUnit timeunit) {
        throw new AssertionError();
    }

    public static void pick() {
        throw new AssertionError();
    }

    /**
     * Saves the current state and returns false. When a matching message
     * arrives, returns true.
     */
    public static boolean onReceive(Object... key) {
        throw new AssertionError();
    }

    /**
     * Returns the matching message of a receive. Must be called only after
     * receive() returned true.
     */
    public static Object getMessage() {
        throw new AssertionError();
    }

    /**
     * Saves the current state and returns false. When a matching service is
     * invoked, returns true.
     */
    public static boolean onService(String service, Object... args) {
        throw new AssertionError();
    }

    /**
     * Saves the current state and returns false When timeout expires, returns
     * true.
     */
    public static boolean onAlarm() {
        throw new AssertionError();
    }

    /**
     * Finishes work with this pick. If there is a suspended state from
     * receive(), service() or onAlarm(), simply passivate. Otherwise an event
     * has happened, and simply returns.
     */
    public static void endPick() {
        throw new AssertionError();
    }

    public static void terminate() {

    }

    /**
     * Finishes work with this thread. If there is a suspended state, the
     * process will remain active waiting for events. Otherwise the process
     * terminates.
     */
    public static void passivate() {

    }

}
