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

/**
 * A signal indicating that the flow was suspended. This signal is sent by
 * {@link Flow#suspend(Object)} method.
 * 
 * @author Fernando Colombo
 */
public class SuspendSignal extends FlowSignal {

    private static final long serialVersionUID = 1L;
    private final Object argument;

    /**
     * Initializes the signal with the given argument. The method
     * {@link Flow#suspend(Object)} simply forwards its argument to this
     * constructor. While {@linkplain FlowSignal handling} this signal, the
     * argument can be obtained using {@link #getResult()}.
     * 
     * @param argument The argument to be associated with this signal.
     */
    public SuspendSignal(Object argument) {
        this.argument = argument;
    }

    /**
     * Returns the argument associated with this signal. Usually this is the
     * argument sent to {@link Flow#suspend(Object)}.
     * 
     * @see #SuspendSignal(Object)
     */
    @Override
    public Object getResult() {
        return argument;
    }

    /**
     * Does nothing. This method does not register anywhere the suspended flow
     * (that is, the flow that sent this signal). Usually a flow registers
     * itself for later {@linkplain Flow#resume(Object) resuming}, <i>before</i>
     * the call to {@link Flow#suspend(Object)}, or before sending this signal.
     */
    @Override
    public void defaultAction() {
    // Do nothing.
    }

}
