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
import java.util.HashSet;

/**
 * An unit of work composed by a set of related flows. A process is a manageable
 * unit of work composed by one or more {@linkplain Flow flows}. It can be
 * monitored, paused or interrupted. If certain conditions are met (such as
 * using only primitives and serializable objects), a process can also be
 * serialized and deserialized, which allows development of long running
 * processes in pure Java language.
 * <p>
 * This class contains utilities for communication and synchronization between
 * process flows:
 * <ul>
 * <li>The {@link #wait(Object) wait} and {@link #notifyAll(Object, Object)
 * notify} methods can be used to wait-for and get/provide information about
 * specific conditions and events.</li>
 * <li>The {@link #send(Object, Object) send} and {@link #receive(Object)
 * receive} methods allow safe delivery of one-way messages.</li>
 * <li>The {@link #call(Object, Object) call} and {@link #serve(Object) serve}
 * methods provide a simple request-response mechanism.</li>
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
     * current process. This method is similar to {@link #current()}, except in
     * that it never returns <code>null</code>. In the absence of a current
     * process, it will throw an exception.
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
    public static Object wait(Object key) {
        return safeCurrent().manager.wait(key);
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
    public static Object waitMany(Object key) {
        return safeCurrent().manager.waitMany(key);
    }

    /**
     * Wakes-up all flows awaiting for the specified key. This method causes all
     * previous invocations to {@link #wait(Object)} and
     * {@link #waitMany(Object)}, that matches the informed key, to return. The
     * informed message is returned in such invocations. If more than one flow
     * is resumed, they all get the same message instance, so either the message
     * must be immutable, or adequate synchronization must be used. If there is
     * no flow awaiting for the specified key, invoking this method has no
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
     * Sends a message to the informed address. If another flow is listening on
     * the informed address, this method causes such flow to resume and then
     * returns immediately. Otherwise, the {@linkplain Flow#current() current
     * flow} is {@linkplain Flow#suspend(Object) suspended} until some flow
     * starts listening on the informed address.
     * <p>
     * The informed address is not a network address. It is an object used to
     * link the sender and receiver flows. For example, the invocation
     * 
     * <pre>
     *     Process.send("ABC", "MessageForABC");
     * </pre>
     * will send the object "MessageForABC" to a flow that invokes
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
     * consume any thread. If such flow is resumed by means other than a
     * <code>receive</code> method, the effect is unpredictable and the process
     * manager will be corrupt.
     * 
     * @param address The address that identifies the listening flow.
     * @param message The message to be sent.
     * @throws IllegalStateException If there is no current process.
     * @see #receive(Object)
     * @see #serve(Object)
     */
    @FlowMethod
    public static void send(Object address, Object message) {
        safeCurrent().manager.send(address, message);
    }

    /**
     * Listens for a single message sent to the informed address. If another
     * flow is blocked while sending a message to the informed address, this
     * method causes such flow to resume and then immediately returns the sent
     * message. Otherwise the {@linkplain Flow#current() current flow} is
     * {@linkplain Flow#suspend(Object) suspended} until such invocation is
     * issued.
     * <p>
     * If another flow sends a message using {@link #call(Object, Object)}, this
     * method will return an instance of {@link IRequest} that contains the sent
     * message. Such call will be blocked until invocation of
     * {@link IRequest#response(Object)}.
     * <p>
     * This method binds the current flow to the informed address. An address
     * can have at most one flow bound to it. When this method returns (that is,
     * when the message is received), the address will be free again.
     * <p>
     * While waiting, the current flow will be suspended and thus will not
     * consume any thread. If such flow is resumed by means other than a
     * <code>send</code> method, the effect is unpredictable and the process
     * manager will be corrupt.
     * <p>
     * For examples and more information, see the {@link #send(Object, Object)}
     * method.
     * 
     * @param address The address on which this flow will be listening.
     * @return The sent message, or an {@link IRequest} if the message was sent
     *         via {@link #call(Object, Object)}.
     * @throws AddressAlreadyInUseException If another flow is listening on this
     *         address.
     * @throws IllegalStateException If there is no current process.
     * @see #send(Object, Object)
     * @see #serve(Object)
     */
    @FlowMethod
    public static Object receive(Object address) {
        return safeCurrent().manager.receive(address);
    }

    /**
     * Listens for multiple messages sent to the informed address. This method
     * is similar to {@link #receive(Object)}, except in that it may return
     * multiple times and to concurrent flows. For example, every subsequent
     * call to a {@link #send(Object, Object) send} method with the informed
     * address will cause this method to return. Whenever this method returns,
     * it will be on a new flow. It never returns to the invoker's flow.
     * <p>
     * The informed address will be bound to the invoker's flow until the
     * current process finishes.
     * 
     * @param address The address on which this flow will be listening.
     * @return The sent message, or an {@link IRequest} if the message was sent
     *         via {@link #call(Object, Object)}.
     * @throws AddressAlreadyInUseException If another flow is listening on this
     *         address.
     * @throws IllegalStateException If there is no current process.
     * @see #receive(Object)
     * @see #serveMany(Object)
     */
    @FlowMethod
    public static Object receiveMany(Object address) {
        return safeCurrent().manager.receiveMany(address);
    }

    /**
     * Listens for a single request sent to the informed address. If another
     * flow is blocked while sending a message to the informed address, this
     * method causes such flow to resume and then immediately returns a request
     * with the cited message. Otherwise the {@linkplain Flow#current() current
     * flow} is {@linkplain Flow#suspend(Object) suspended} until such
     * invocation is issued.
     * <p>
     * If another flow sends a message using {@link #send(Object, Object)}, this
     * method will cause such invocation to return, and then it will return an
     * instance of {@link IRequest} that contains the sent message and requires
     * no response.
     * <p>
     * This method binds the current flow to the informed address. An address
     * can have at most one flow bound to it. When this method returns (that is,
     * when the message is received), the address will be free again.
     * <p>
     * While waiting, the current flow will be suspended and thus will not
     * consume any thread. If such flow is resumed by means other than a
     * <code>send</code> method, the effect is unpredictable and the process
     * manager will be corrupt.
     * <p>
     * For examples and more information, see the {@link #call(Object, Object)}
     * method.
     * 
     * @param address The address on which this flow will be listening.
     * @return An {@link IRequest} containing the sent message.
     * @throws AddressAlreadyInUseException If another flow is listening on this
     *         address.
     * @throws IllegalStateException If there is no current process.
     * @see IRequest
     * @see #call(Object, Object)
     * @see #serveMany(Object)
     */
    @FlowMethod
    public static IRequest serve(Object address) {
        Object ret = receive(address);
        if (ret instanceof TwoWayRequest) {
            return (IRequest) ret;
        }
        return new OneWayRequest(ret);
    }

    /**
     * Listens for multiple messages sent to the informed address. This method
     * is similar to {@link #serve(Object)}, except in that it may return
     * multiple times and to concurrent flows. For example, every subsequent
     * invocation to {@link #call(Object, Object)} with the informed address
     * will cause this method to return. Whenever this method returns, it will
     * be on a new flow. It never returns to the invoker's flow.
     * <p>
     * The informed address will be bound to the invoker's flow until the
     * current process finishes.
     * <p>
     * This method can be used to implement a very simple server.
     * 
     * @param address The address on which this flow will be listening.
     * @return An {@link IRequest} containing the sent message.
     * @throws AddressAlreadyInUseException If another flow is listening on this
     *         address.
     * @throws IllegalStateException If there is no current process.
     * @see #serve(Object)
     */
    @FlowMethod
    public static IRequest serveMany(Object address) {
        Object ret = receiveMany(address);
        if (ret instanceof TwoWayRequest) {
            return (IRequest) ret;
        }
        return new OneWayRequest(ret);
    }

    /**
     * Sends a request to the informed address and waits for a response. This
     * method is similar to {@link #send(Object, Object)}, except in that it
     * waits for a response from the listening flow. While waiting, the
     * {@linkplain Flow#current() current flow} is
     * {@linkplain Flow#suspend(Object) suspended}.
     * <p>
     * The following example illustrates the call behavior:
     * 
     * <pre>
     *  public class Example implements Runnable {
     *
     *      &#064;{@link FlowMethod}
     *      public void run() {
     *          // We must join a process.
     *          Flow.joinProcess(new Process());
     *          if (Flow.split(1) == 0) {
     *              // Here is the server.
     *              IRequest request = <b>Process.serve("PeerA")</b>;
     *              System.out.println("Request: " + request.request());
     *              request.response("I'm fine.");
     *          } else {
     *              // Here is the client.
     *              Object response = <b>Process.call("PeerA", "How are you?")</b>;
     *              System.out.println("Response: " + response);
     *          }
     *      }
     *
     *      public static void main(String[] args) throws InterruptedException {
     *          Flow.submit(new Example());
     *          Thread.sleep(1000); // Wait for all flows to finish.
     *      }
     *  }
     * </pre>
     * The above class prints the following:
     * 
     * <pre>
     *  Request: How are you?
     *  Response: I'm fine.
     * </pre>
     * If the <code>address</code> argument is not <code>null</code>, it must
     * provide consistent behaviors for {@link Object#equals(Object)} and
     * {@link Object#hashCode()}. While not an absolute requirement, it is
     * strongly recommended to use an immutable object as the
     * <code>address</code>.
     * <p>
     * While waiting, the current flow will be suspended and thus will not
     * consume any thread. If such flow is resumed by means other than a
     * <code>receive</code> method, the effect is unpredictable and the process
     * manager will be corrupt.
     * 
     * @param address The address that identifies the listening flow.
     * @param message The message to be sent.
     * @return The listener's response.
     * @throws IllegalStateException If there is no current process.
     * @see #receive(Object)
     * @see #serve(Object)
     */
    @FlowMethod
    public static Object call(Object address, Object message) {
        ProcessManager man = safeCurrent().manager;
        TwoWayRequest req = new TwoWayRequest(man, message);
        man.send(address, req);
        return man.receive(req);
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
    private int activeFlows;
    private int suspendedFlows;

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
        if (manager == null) {
            throw new NullPointerException();
        }
        this.manager = manager;
        flows = new HashSet<Flow>();
    }

    public final int activeFlows() {
        return activeFlows;
    }

    public final int suspendedFlows() {
        return suspendedFlows;
    }

    public final synchronized Flow[] getFlows() {
        Flow[] ret = new Flow[flows.size()];
        return flows.toArray(ret);
    }

    synchronized final void add(Flow flow) {
        if (flow.process != null) {
            if (flow.process == this) {
                assert flows.contains(flow);
                throw new IllegalArgumentException("Flow already belongs to this process.");
            }
            assert !flows.contains(flow);
            throw new IllegalArgumentException("Flow belongs to another process.");
        }
        checkAddRemove();
        boolean added = flows.add(flow);
        assert added;
        flow.process = this;
        if (flow.isActive()) {
            ++activeFlows;
        } else {
            assert flow.isSuspended();
            ++suspendedFlows;
        }
    }

    synchronized final void remove(Flow flow) {
        if (flow.process != this) {
            assert !flows.contains(flow);
            throw new IllegalArgumentException("Flow does not belong to this process.");
        }
        checkAddRemove();
        boolean removed = flows.remove(flow);
        assert removed;
        flow.process = null;
        if (flow.isActive()) {
            --activeFlows;
        } else {
            assert flow.isSuspended();
            --suspendedFlows;
        }
    }

    synchronized void notifySuspend(Flow flow) {
        assert flows.contains(flow);
        --activeFlows;
        ++suspendedFlows;
    }

    synchronized void notifyResume(Flow flow) {
        assert flows.contains(flow);
        --suspendedFlows;
        ++activeFlows;
    }

    void checkAddRemove() {
    // Provided for override.
    }

    private static class OneWayRequest implements IRequest, Serializable {

        private static final long serialVersionUID = 1L;
        private final Object request;

        OneWayRequest(Object request) {
            this.request = request;
        }

        public boolean needResponse() {
            return false;
        }

        public Object request() {
            return request;
        }

        public void response(Object response) {
            throw new IllegalStateException("This request does not need a response.");
        }
    }

    private static class TwoWayRequest implements IRequest, Serializable {

        private static final long serialVersionUID = 1L;
        private final ProcessManager manager;
        private final Object request;
        private boolean responseSent;

        TwoWayRequest(ProcessManager manager, Object request) {
            this.manager = manager;
            this.request = request;
        }

        public boolean needResponse() {
            return !responseSent;
        }

        public Object request() {
            return request;
        }

        @FlowMethod
        public synchronized void response(Object response) {
            if (responseSent) {
                throw new IllegalStateException("The response was already sent.");
            }
            responseSent = true;
            manager.send(this, response);
        }
    }

}
