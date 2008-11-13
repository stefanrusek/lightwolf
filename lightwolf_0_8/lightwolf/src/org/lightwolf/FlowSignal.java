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
 * The root of all signal classes. A signal is sent to the <a
 * href="Flow.html#flowcontroller">flow-controller</a> by the
 * {@link Flow#signal(FlowSignal)} method. Because the signal is an exception,
 * it must be caught by the flow-controller in a <code>catch</code> block. The
 * following snippet is an example of signal handling:
 * 
 * <pre>
 *     void startFlow() { // This is the <a href="Flow.html#flowcontroller">flow-controller</a>. 
 *         try {
 *             runFlow(); // Runs the flow, which might send a signal.
 *             System.out.println("The flow finished normally.");
 *         } catch (FlowSignal signal) {
 *             // A signal was sent.
 *             signal.defaultAction(); // Calls the signal's default action.
 *             System.out.println("The flow is waiting for something.");
 *         }
 *     }
 *
 *     &#064;{@link FlowMethod}
 *     void runFlow() { // This is the <a href="Flow.html#flowcreator">flow-creator</a>.
 *         ...
 *     }
 * </pre>
 * 
 * A well-behaved signal handler should call the signal's
 * {@link #defaultAction()} method, like the above example. For more information
 * about sending and handling signals, please check the
 * {@link Flow#signal(FlowSignal)} method.
 * 
 * @author Fernando Colombo
 */
public abstract class FlowSignal extends RuntimeException {

    private static final long serialVersionUID = 1L;
    Flow flow;

    public FlowSignal() {
        super("Unhandled signal.");
    }

    /**
     * The flow that have sent this signal, or <code>null</code> if this signal
     * was just instantiated and not sent yet. While the signal is being
     * handled, it is guaranteed that the flow will be in suspended state.
     */
    public final Flow getFlow() {
        return flow;
    }

    public Object getResult() {
        return null;
    }

    /**
     * The signal's default action. The behavior of this method varies and is
     * defined by the actual signal class. Usually this method registers the
     * flow somewhere, so it can be {@linkplain Flow#resume(Object) resumed}
     * when a certain event happens. A well-behaved <a
     * href="Flow.html#flowcontroller">flow-controller</a> should always call
     * this method while handling a signal.
     * <p>
     * NOTE TO IMPLEMENTORS: Document well what the implementation of your
     * method does, specially on the method's JavaDoc. You can call
     * {@link #getFlow()} to obtain the flow that have sent the signal. Inside
     * this method, it is guaranteed that the flow will be in suspended state.
     */
    public abstract void defaultAction();

}
