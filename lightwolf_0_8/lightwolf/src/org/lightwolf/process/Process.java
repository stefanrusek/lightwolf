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

import org.lightwolf.Flow;
import org.lightwolf.FlowLocal;
import org.lightwolf.FlowMethod;
import org.lightwolf.synchronization.ThreadFreeLock;

public class Process {

    private FlowLocal<ProcessFork> currentFork;
    private final ThreadFreeLock lock;

    public Process() {
        lock = new ThreadFreeLock();
    }

    @FlowMethod
    protected void enter() {
        Flow.log("Before lock.");
        ProcessFork f = getCurrentFork();
        lock.lock();
        assert getCurrentFork() == f; // lock() might return in another Java thread. Check FlowThreadLocal.
        Flow.log("After lock.");
    }

    protected void exit() {
        Flow.log("Before unlock.");
        lock.unlock();
        Flow.log("After unlock.");
    }

    protected boolean isThreadOwner() {
        return lock.isMine();
    }

    protected void fork() {
        checkIsThreadOwner();
        ProcessFork fork = new ProcessFork(this, getCurrentFork());
        setCurrentFork(fork);
    }

    @FlowMethod
    protected void join() {
        checkIsThreadOwner();
        ProcessFork fork = getCurrentFork();
        if (fork == null) {
            throw new IllegalStateException("No active fork.");
        }
        ProcessFork prior = fork.join();
        setCurrentFork(prior);
    }

    @FlowMethod
    protected boolean onPath() {
        ProcessFork fork = getCurrentFork();
        if (fork == null) {
            throw new IllegalStateException("No active fork.");
        }
        // NOTE: processChoice() here might well not return. Check javadoc for info.
        ProcessFork thisFork = fork.processPath();
        if (thisFork == null) {
            // We are in the "main" thread (the thread that called fork).
            // This thread will always return false, and will block and
            // wait for the others when it arrive at the join point.
            return false;
        }
        // We are in a choice. Here we must return true.
        assert getCurrentFork() == null;
        setCurrentFork(thisFork);
        return true;
    }

    private void checkIsThreadOwner() {
        if (!isThreadOwner()) {
            throw new IllegalStateException("Must be thread owner. Missing enter()-exit() block?");
        }
    }

    private ProcessFork getCurrentFork() {
        if (currentFork == null) {
            return null;
        }
        ProcessFork fork = currentFork.get();
        if (fork != null && fork.owner != this) {
            throw new AssertionError();
        }
        return fork;
    }

    private synchronized void setCurrentFork(ProcessFork fork) {
        if (currentFork == null) {
            if (fork == null) {
                return;
            }
            currentFork = new FlowLocal<ProcessFork>();
        }
        currentFork.set(fork);
    }

    private static class ProcessFork {

        private final Process owner;
        private final ProcessFork prior;
        private int actualForks;

        ProcessFork(Process owner, ProcessFork prior) {
            this.owner = owner;
            this.prior = prior;
            actualForks = 0;
        }

        ProcessFork(Process owner) {
            this.owner = owner;
            prior = null;
            actualForks = -1;
        }

        /**
         * If this is the main thread, returns null to such thread, and forks and returns
         * a new {@link ProcessFork} object to the fork.
         * If this is not the main thread, leaves (does not return).
         */
        @FlowMethod
        ProcessFork processPath() {
            if (!isMain()) {
                Flow.log("pp - owner.exit.");
                owner.exit();
                Flow.log("pp - leave.");
                Flow.end();
                throw new AssertionError();
            }
            int fork = Flow.fork(1);
            if (fork == 0) {
                // We are in the main thread.
                // Add this fork to the list, so we can join later.
                ++actualForks;
                // Return null, telling the caller (onPath) to return false.
                return null;
            }
            // We are in a fork thread.
            // Get a thread.
            Flow.log("pp - enter.");
            owner.enter();
            Flow.log("pp - after enter.");
            // Return a new fork, telling the caller (onPath) to bookkeep and return false.
            return new ProcessFork(owner);
        }

        @FlowMethod
        ProcessFork join() {
            if (!isMain()) {
                Flow.log("join - owner.exit.");
                owner.exit();
                Flow.log("join - leave.");
                Flow.end();
                throw new AssertionError();
            }
            Flow.log("join - let-it-go.");
            owner.exit(); // Let the other threads go.
            try {
                for (int i = 0; i < actualForks; ++i) {
                    try {
                        Flow.merge();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                Flow.log("join - all done.");
            } finally {
                Flow.log("join - reacquire.");
                owner.enter();
                Flow.log("join - reacquired.");
            }
            return prior;
        }

        private boolean isMain() {
            return actualForks != -1;
        }

    }

}
