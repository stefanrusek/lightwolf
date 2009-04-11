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

import java.io.Serializable;


public abstract class TaskManager implements Serializable {

    private static final long serialVersionUID = 1L;

    private static TaskManager _default = new SimpleTaskManager("defaultTaskManager");

    public static TaskManager getDefault() {
        return _default;
    }

    public static void setDefault(TaskManager manager) {
        if (manager == null) {
            throw new NullPointerException();
        }
        // TODO: Check permissions.
        _default = manager;
    }

    protected abstract void notify(Object destKey, Object message);

    @FlowMethod
    protected abstract Object wait(Object matcher);

    @FlowMethod
    protected abstract Object waitMany(Object matcher);

    @FlowMethod
    protected abstract void send(Object destKey, Object message);

    @FlowMethod
    protected abstract void sendThrowing(Object destKey, Throwable exception);

    @FlowMethod
    protected abstract Object receive(Object matcher);

    @FlowMethod
    protected abstract Object receiveMany(Object matcher);

    @FlowMethod
    protected abstract Connection accept(Object matcher);

    @FlowMethod
    protected abstract Connection acceptMany(Object matcher);

    @FlowMethod
    protected abstract Connection connect(Object matcher);

    @FlowMethod
    protected abstract Connection connectMany(Object matcher);

    protected abstract void notifyInterrupt(Task task);

}
