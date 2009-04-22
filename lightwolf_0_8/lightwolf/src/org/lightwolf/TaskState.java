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

public enum TaskState {
    /**
     * A constant indicating that the task is active. An active task have all
     * its data on memory. It can run flows and allow new flows to
     * {@linkplain Flow#joinTask(Task) join} it.
     * 
     * @see Task#getState()
     * @see Task#activate()
     */
    ACTIVE,

    /**
     * A constant indicating that the task is passive. A passive task have its
     * data on external storage. The actual storage is defined by the
     * {@link Task} subclass. It can't run flows nor allow new flows to
     * {@linkplain Flow#joinTask(Task) join} it.
     * 
     * @see Task#getState()
     * @see Task#passivate()
     */
    PASSIVE,

    /**
     * A constant indicating that the task has been interrupted. An interrupted
     * task contains only interrupted flows and doesn't allow new flows to
     * {@linkplain Flow#joinTask(Task) join} it.
     * 
     * @see Task#getState()
     */
    INTERRUPTED

}
