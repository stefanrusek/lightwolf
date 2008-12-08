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

import java.util.HashSet;

/**
 * An integrated unit of work composed by a set of related flows. A process is a
 * manageable unit of work composed by one or more {@linkplain Flow flows}. It
 * can be monitored, paused or interrupted. If certain conditions are met (such
 * as using only primitives and serializable objects), a process can also be
 * serialized and deserialized, which allows development of long running
 * processes in pure Java language.
 * <p>
 * This class contains utilities for communication and synchronization between
 * process flows:
 * <ul>
 * <li>The {@link #wait(Object)}, {@link #waitMany(Object)} and
 * {@link #notifyAll(Object, Object)} methods can be used to wait-for and
 * get/provide information about specific conditions and events.</li>
 * <li>The {@link #send(Object, Object)}, {@link #receive(Object)} and
 * {@link #receiveMany(Object)} methods allow safe delivery of messages.</li>
 * <li>A connection can be established between two flows, providing both
 * synchronous and asynchronous modes of operation.</li>
 * </ul>
 * A process is created by calling its {@linkplain Process#Process()
 * constructor}. Initially the process contains no flow. A flow can join a
 * process by invoking {@link Flow#joinProcess(Process)}. The {@link Process}
 * class can be subclassed.
 * 
 * @see Flow
 * @author Fernando Colombo
 */
public class Process {

    /**
     * Returns the current process. This method returns non-null if the
     * {@linkplain Flow#current() current flow} is running inside a process.
     * Otherwise it returns <code>null</code>. If there is no current flow, this
     * method also returns <code>null</code>.
     * 
     * @return The current process, or <code>null</code> if the current flow is
     *         not running in the context of a process, or if there is no
     *         current flow.
     */
    public static Process current() {
        Flow flow = Flow.current();
        return flow == null ? null : flow.process;
    }

    /**
     * Returns the current process, or throws an exception if there is no
     * current process. This method is like {@link #current()}, except in that
     * it never returns <code>null</code>. In the absence of a current process,
     * it will throw an exception.
     * 
     * @return The current process.
     * @throws IllegalStateException If there is no current process.
     */
    public static Process safeCurrent() {
        Process ret = current();
        if (ret == null) {
            throw new IllegalStateException("No current process.");
        }
        return ret;
    }

    /**
     * Waits for a notification that matches the informed key. The
     * {@linkplain Flow#current() current flow} is
     * {@linkplain Flow#suspend(Object) suspended} until another flow invokes
     * {@link #notifyAll(Object, Object)}.
     * <p>
     * The specified object is used to link <code>wait</code> and
     * <code>notify</code> pairs. For example, the invocation
     * 
     * <pre>
     *     Object result = Process.wait("ABC");
     * </pre>
     * will wait until another flow invokes
     * 
     * <pre>
     *     Process.notifyAll("ABC", "ResultOfABC");
     * </pre>
     * , which will cause the first code to resume and assign
     * <code>"ResultOfABC"</code> to the variable <code>result</code>.
     * <p>
     * If the <code>key</code> argument is not <code>null</code>, it must
     * provide consistent behaviors for {@link Object#equals(Object)} and
     * {@link Object#hashCode()}. While not an absolute requirement, it is
     * strongly recommended to use an immutable object as the <code>key</code>.
     * <p>
     * Optionally, the <code>key</code> argument can be an instance of
     * {@link IMatcher}. In this case, the matcher will not behave as a key, but
     * as a <i>selector</i>. The following example illustrates this behavior:
     * 
     * <pre>
     *     IMatcher matcher = new IMatcher() {
     *         boolean match(Object candidate) {
     *             if (!(candidate instanceof String)) { return false; }
     *             return ((String) candidate).startsWith("ABC");
     *         }
     *     };
     *     Object result = Process.wait(matcher);
     * </pre>
     * The wait in the above example will resume for keys such as
     * <code>"ABCD"</code> or <code>"ABC123"</code>.
     * <p>
     * While waiting, the current flow will be suspended and thus will not
     * consume any thread. If such flow is resumed by means other than
     * {@link #notifyAll(Object, Object)}, the effect is unpredictable and the
     * process manager will be corrupt.
     * 
     * @param key The key to wait for (may be <code>null</code>), or an
     *        {@link IMatcher} instance, as above specified.
     * @return The <code>message</code> argument that was passed to
     *         {@link #notifyAll(Object, Object)}.
     * @throws IllegalStateException If there is no current process.
     * @see #waitMany(Object)
     * @see #notifyAll(Object, Object)
     * @see #send(Object, Object)
     * @see #receive(Object)
     */
    @FlowMethod
    public static Object wait(Object matcher) {
        return safeCurrent().manager.wait(matcher);
    }

    /**
     * Waits for multiple notifications that matches the informed key. This
     * method is similar to {@link #wait(Object)}, except in that it may return
     * multiple times and to concurrent flows. Every subsequent call to
     * {@link #notifyAll(Object, Object)} with a key that matches the informed
     * key will cause this method to return. Whenever this method returns, it
     * will be on a new flow. It never returns to the invoker's flow.
     * 
     * @param key The key to wait for (may be <code>null</code>), or an
     *        {@link IMatcher} instance, as specified on {@link #wait(Object)}.
     * @return The <code>message</code> argument that was passed to
     *         {@link #notifyAll(Object, Object)}.
     * @throws IllegalStateException If there is no current process.
     * @see #wait(Object)
     * @see #notifyAll(Object, Object)
     */
    @FlowMethod
    public static Object waitMany(Object matcher) {
        return safeCurrent().manager.waitMany(matcher);
    }

    /**
     * Wakes-up all flows waiting for the specified key. This method causes all
     * previous invocations to {@link #wait(Object)} and
     * {@link #waitMany(Object)}, that matches the informed key, to return. The
     * informed message is returned in such invocations. If more than one flow
     * is resumed, they all get the same message instance, so either the message
     * must be immutable, or adequate synchronization must be used. If there is
     * no flow waiting for the specified key, invoking this method has no
     * effect. For examples and more information, see the {@link #wait(Object)}
     * method.
     * 
     * @param key The key that identifies which {@link #wait(Object)} and
     *        {@link #waitMany(Object)} invocations will be resumed.
     * @param message The message to be sent to the resumed flows. It will be
     *        returned from the resumed {@link #wait(Object)} and
     *        {@link #waitMany(Object)} invocations.
     * @throws IllegalStateException If there is no current process.
     * @see #wait(Object)
     * @see #waitMany(Object)
     * @see #send(Object, Object)
     * @see #receive(Object)
     */
    public static void notifyAll(Object key, Object message) {
        safeCurrent().manager.notify(key, message);
    }

    /**
     * Sends a message to the informed address. If another flow is blocked by an
     * invocation to {@link #receive(Object)} or {@link #receiveMany(Object)},
     * this method causes such invocation to return the informed message, and
     * then returns immediately. Otherwise, if there is no invocation of
     * {@link #receive(Object)} nor {@link #receiveMany(Object)} on the informed
     * address, the {@linkplain Flow#current() current flow} is
     * {@linkplain Flow#suspend(Object) suspended} until such invocation is
     * issued.
     * <p>
     * The informed address is not a network address. It is actually an object
     * used to link <code>send</code> and <code>receive</code> invocations. For
     * example, the invocation
     * 
     * <pre>
     *     Process.send("ABC", "MessageForABC");
     * </pre>
     * will send the object "MessageForABC" to the flow that invokes
     * 
     * <pre>
     *     Object result = Process.receive("ABC");
     * </pre>
     * The above <code>receive</code> invocation will assign "MessageForABC" to
     * the <code>result</code> variable.
     * <p>
     * If the <code>address</code> argument is not <code>null</code>, it must
     * provide consistent behaviors for {@link Object#equals(Object)} and
     * {@link Object#hashCode()}. While not an absolute requirement, it is
     * strongly recommended to use an immutable object as the
     * <code>address</code>.
     * <p>
     * While waiting, the current flow will be suspended and thus will not
     * consume any thread. If such flow is resumed by means other than
     * {@link #receive(Object)} or {@link #receiveMany(Object)}, the effect is
     * unpredictable and the process manager will be corrupt.
     * 
     * @param address The address that identifies which {@link #receive(Object)}
     *        or {@link #receiveMany(Object)} invocation will return the
     *        informed message.
     * @param message The message to be returned to the corresponding
     *        {@link #receive(Object)} or {@link #receiveMany(Object)}
     *        invocation.
     * @throws IllegalStateException If there is no current process.
     * @see #receive(Object)
     * @see #receiveMany(Object)
     * @see #wait(Object)
     * @see #notifyAll(Object, Object)
     */
    @FlowMethod
    public static void send(Object address, Object message) {
        safeCurrent().manager.send(address, message);
    }

    /**
     * Receives a message sent to the informed address. If another flow is
     * blocked by an invocation to {@link #send(Object, Object)}, this method
     * causes such invocation to return, and then returns immediately the sent
     * message. Otherwise, if there is no invocation of
     * {@link #send(Object, Object)} on the informed address, the
     * {@linkplain Flow#current() current flow} is
     * {@linkplain Flow#suspend(Object) suspended} until such invocation is
     * issued.
     * <p>
     * This method binds the current flow to the informed address. An address
     * can have at most one flow bound to it. When this method returns (that is,
     * when the message is received), the address will be free again, and either
     * this flow or another will be able to call {@link #receive(Object)} or
     * {@link #receiveMany(Object)}.
     * <p>
     * While waiting, the current flow will be suspended and thus will not
     * consume any thread. If such flow is resumed by means other than
     * {@link #send(Object, Object)}, the effect is unpredictable and the
     * process manager will be corrupt.
     * <p>
     * For examples and more information, see the {@link #send(Object, Object)}
     * method.
     * 
     * @param address The address that identifies from which
     *        {@link #send(Object, Object)} invocation the message will be
     *        received.
     * @return The message sent by the corresponding
     *         {@link #send(Object, Object)} method.
     * @throws AddressAlreadyInUseException If another flow invoked this method
     *         or {@link #receiveMany(Object)} on the informed address.
     * @throws IllegalStateException If there is no current process.
     */
    @FlowMethod
    public static Object receive(Object address) {
        return safeCurrent().manager.receive(address);
    }

    /**
     * Receives multiple messages sent to the informed address. This method is
     * similar to {@link #receive(Object)}, except in that it may return
     * multiple times and to concurrent flows. Every subsequent call to
     * {@link #send(Object, Object)} with the informed address will cause this
     * method to return. Whenever this method returns, it will be on a new flow.
     * It never returns to the invoker's flow.
     * <p>
     * The informed address will be bound to the invoker's flow until the
     * current process finishes. Hence this method can be used to implement a
     * simple event handler or even a server. For examples and more information,
     * see the {@link #send(Object, Object)} method.
     * 
     * @param address The address that identifies from which
     *        {@link #send(Object, Object)} invocation the message will be
     *        received.
     * @return The message sent by the corresponding
     *         {@link #send(Object, Object)} method.
     * @throws AddressAlreadyInUseException If another flow invoked this method
     *         or {@link #receiveMany(Object)} on the informed address.
     * @throws IllegalStateException If there is no current process.
     */
    @FlowMethod
    public static Object receiveMany(Object address) {
        return safeCurrent().manager.receiveMany(address);
    }

    @FlowMethod
    public static Connection accept(Object matcher) {
        return safeCurrent().manager.accept(matcher);
    }

    @FlowMethod
    public static Connection acceptMany(Object matcher) {
        return safeCurrent().manager.acceptMany(matcher);
    }

    @FlowMethod
    public static Connection connect(Object matcher) {
        return safeCurrent().manager.connect(matcher);
    }

    @FlowMethod
    public static Connection connectMany(Object matcher) {
        return safeCurrent().manager.connectMany(matcher);
    }

    private final ProcessManager manager;
    private final HashSet<Flow> flows;

    /**
     * Creates a new process. The new process will belong to the
     * {@linkplain ProcessManager#getDefault() default} process manager. The
     * process will contain no flow. To add a flow, it is necessary to call
     * {@link Flow#joinProcess(Process)} specifying this process.
     */
    public Process() {
        this(ProcessManager.getDefault());
    }

    public Process(ProcessManager manager) {
        this.manager = manager;
        flows = new HashSet<Flow>();
    }

    synchronized void add(Flow flow) {
        if (flow.process != null) {
            if (flow.process == this) {
                assert flows.contains(flow);
                throw new IllegalArgumentException("Flow already belongs to this process.");
            }
            assert !flows.contains(flow);
            throw new IllegalArgumentException("Flow belongs to another process.");
        }
        boolean ret = flows.add(flow);
        flow.process = this;
        assert ret;
    }

    synchronized void remove(Flow flow) {
        if (flow.process != this) {
            assert !flows.contains(flow);
            throw new IllegalArgumentException("Flow does not belong to this process.");
        }
        boolean ret = flows.remove(flow);
        flow.process = null;
        assert ret;
    }

}
