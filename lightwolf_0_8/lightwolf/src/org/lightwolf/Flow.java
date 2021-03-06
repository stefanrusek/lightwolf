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

import static org.lightwolf.FlowState.ACTIVE;
import static org.lightwolf.FlowState.ENDED;
import static org.lightwolf.FlowState.INTERRUPTED;
import static org.lightwolf.FlowState.PASSIVE;
import static org.lightwolf.FlowState.SUSPENDED;
import static org.lightwolf.FlowState.SUSPENDING;
import static org.lightwolf.FlowState.TEMP_SUSP;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.lightwolf.synchronization.EventPicker;
import org.lightwolf.synchronization.ThreadFreeLock;
import org.lightwolf.tools.PublicByteArrayInputStream;
import org.lightwolf.tools.PublicByteArrayOutputStream;
import org.lightwolf.tools.SimpleFlowManager;

/**
 * An execution context similar to that of a thread, but with more capabilities.
 * <p>
 * Compared to normal {@link Thread threads}, flows simplifies the
 * implementation of concurrent algorithms and scalable applications. The
 * following list summarizes the capabilities of a flow:
 * <ul>
 * <li>A flow can be {@link #suspend(Object) suspended} for later
 * {@link #resume(Object) resuming}, without consuming a Java thread meanwhile.</li>
 * <li>The flow's execution state can be serialized and restored, possibly on a
 * different machine (as long as certain conditions are met). This makes flows
 * portable and memory-friendly.</li>
 * <li>A flow can wait for a {@linkplain ThreadFreeLock lock} on a resource, or
 * for the {@linkplain IOActivator completion} of an I/O operation, without
 * consuming a Java thread meanwhile, and thus releasing a pooled thread for
 * increased concurrency.</li>
 * <li>Flows can use utilities such as {@link Continuation}, {@link #fork(int)},
 * and {@link #returnAndContinue()}.</li>
 * </ul>
 * <p>
 * There are some important concepts regarding flows.
 * <p>
 * <b>Flow-method:</b> A flow-method is a method marked with the
 * {@link FlowMethod} annotation. Whenever a flow-method is executing, there
 * will be an associated flow instance, which can be obtained by invoking
 * {@link #current()} (such flow is automatically instantiated as described
 * below). If a flow-method invokes itself or another flow-method, both methods
 * use the same flow instance. If a flow-method invokes a normal (non-flow)
 * method, the flow is kept active and can be queried, but some flow utilities
 * will disabled until the normal method returns to the invoker flow-method.
 * <p>
 * <a name="flowcreator"> <b>Flow-creator:</b> Whenever a flow-method is invoked
 * by a normal (non-flow) method, it is called the flow-creator method, because
 * it triggers the creation of a new flow (this is done automatically). The flow
 * ends when the flow-creator completes normally or by exception. If the
 * flow-creator calls itself or another flow-method, no new flow is created, as
 * described above.
 * <p>
 * <a name="flowcontroller"> <b>Flow-controller:</b> The flow-controller is a
 * normal (non-flow) method that invokes a flow-method. The flow-controller
 * receives {@linkplain #signal(FlowSignal) signals} sent by some flow
 * operations, and must handle those signals accordingly. There are standard
 * implementations of flow-controllers, such as {@link #execute(Callable)} and
 * {@link SimpleFlowManager}. If it is known that a flow-method will never send
 * signals, the flow-controller can be any ordinary method calling a
 * flow-method. Many utilities do not send any signal, such as
 * {@link #split(int)} and {@link #returnAndContinue()}.
 * <p>
 * According to the above specification, when a flow-method <i>A</i> invokes a
 * normal method <i>B</i>, and then <i>B</i> invokes a flow-method <i>C</i>, a
 * nested flow is created. Regarding the nested flow, <i>B</i> will be the
 * flow-controller and <i>C</i> the flow-creator. If <i>C</i> invokes a flow
 * utility such as {@link #fork(int)}, the effect is applied only to the nested
 * flow. The outer flow, on which <i>A</i> is running, is not affected by the
 * fork. When the nested flow ends or is suspended, the outer flow becomes
 * active again. The number of nesting levels is limited only by memory. Nested
 * flows are uncommon because usually flow-methods are designed to call other
 * flow-methods, which does not cause the creation of new flow, as mentioned
 * above. Nevertheless, nested flows are allowed as an orthogonality feature.
 * <p>
 * If a flow <i>A</i> belongs to a {@link Task}, then every flow <i>B</i>
 * derived from <i>A</i> will automatically belong to the same task. Derived
 * flows are result of shallow {@linkplain #copy() copies}. Many utilities
 * perform shallow copies, including but not limited to {@link #fork(int)},
 * {@link #returnAndContinue()} and {@link Continuation}.
 * 
 * @see FlowMethod
 * @see Task
 * @author Fernando Colombo
 */
public final class Flow implements Serializable {

    private static final long serialVersionUID = 6770568702848053327L;

    private static final ThreadLocal<Flow> current = new ThreadLocal<Flow>();

    private static final Object INTERRUPT_SIGNAL = new Object();

    /**
     * Creates and returns a new flow. The new flow will be in {@link #ENDED}
     * state, which is suitable to be passed as argument to methods such as
     * {@link Continuation#resumeOnFlow(Flow, Object)}.
     * 
     * @return A newly created flow instance, in {@link #ENDED} state.
     */
    public static Flow newFlow() {
        return new Flow(FlowManager.getNext(), null);
    }

    public static void execute(Runnable runnable) {
        Flow previous = current();
        if (previous != null && !previous.isWorking()) {
            throw new IllegalStateException();
        }
        Flow flow = newFlow(previous);
        try {
            MethodFrame frame = flow.newFrame(runnable, "run", "()V");
            frame.notifyInvoke(Integer.MAX_VALUE, 0, 0);
            flow.state = SUSPENDED;
            flow.currentFrame = null;
            flow.suspendedFrame = frame;
            flow.resume();
        } finally {
            current.set(previous);
        }
    }

    public static Object execute(Callable<?> callable) {
        Flow previous = current();
        if (previous != null && !previous.isWorking()) {
            throw new IllegalStateException();
        }
        Flow flow = newFlow(previous);
        try {
            MethodFrame frame = flow.newFrame(callable, "call", "()Ljava/lang/Object;");
            frame.notifyInvoke(Integer.MAX_VALUE, 0, 0);
            flow.state = SUSPENDED;
            flow.currentFrame = null;
            flow.suspendedFrame = frame;
            return flow.resume();
        } finally {
            current.set(previous);
        }
    }

    public static Flow submit(Runnable runnable) {
        Flow flow = newFlow(null);
        MethodFrame frame = flow.newFrame(runnable, "run", "()V");
        frame.notifyInvoke(Integer.MAX_VALUE, 0, 0);
        flow.state = SUSPENDED;
        flow.currentFrame = null;
        flow.suspendedFrame = frame;
        flow.activate();
        return flow;
    }

    public static Flow submit(Callable<?> callable) {
        Flow flow = newFlow(null);
        MethodFrame frame = flow.newFrame(callable, "call", "()Ljava/lang/Object;");
        frame.notifyInvoke(Integer.MAX_VALUE, 0, 0);
        flow.state = SUSPENDED;
        flow.currentFrame = null;
        flow.suspendedFrame = frame;
        flow.activate();
        return flow;
    }

    public static void acquireThread() {
        Flow.safeCurrent().blockingLevel++;
    }

    public static void releaseThread() {
        Flow flow = Flow.safeCurrent();
        if (flow.blockingLevel == 0) {
            throw new IllegalStateException("Flow does not own thread.");
        }
        flow.blockingLevel--;
    }

    /**
     * The task to which this flow belongs.
     * 
     * @return An instance of {@link Task}, or <code>null</code> if this flow
     *         does not belong to any task.
     * @see #joinTask(Task)
     * @see #leaveTask()
     */
    @FlowMethod(manual = true)
    public static Task task() {
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            return cur.task;
        } finally {
            frame.invoked();
        }
    }

    /**
     * Adds the {@linkplain Flow#current() current} flow to the informed
     * {@linkplain Task task}. This method sets the {@linkplain #task() current
     * flow's task} to the informed task. This allows usage of {@linkplain Task
     * task utilities} from the current flow.
     * 
     * @param task The task to which the current flow will be added. Must not be
     *        <code>null</code>.
     * @see Task
     * @see #leaveTask()
     * @see #forgetTask()
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws NullPointerException If the informed task is a <code>null</code>
     *         reference.
     */
    @FlowMethod(manual = true)
    public static void joinTask(Task task) {
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            if (task == null) { // Must be called here, inside the try-finally.
                throw new NullPointerException();
            }
            synchronized(cur) {
                synchronized(task) {
                    if (cur.task != null) {
                        if (cur.task == task) {
                            throw new IllegalStateException("Flow already belongs to specified task.");
                        }
                        throw new IllegalStateException("Flow belongs to another task.");
                    }
                    task.add(cur);
                    assert cur.task == task;
                    cur.currentContext = task.readContext();
                }
                cur.notifyAll();
            }
        } finally {
            frame.invoked();
        }
    }

    /**
     * Removes the {@linkplain Flow#current() current flow} from its
     * {@linkplain #task() current task}. This method sets the
     * {@linkplain #task() current flow's task} to <code>null</code>, or throws
     * an exception if the {@linkplain Flow#current() current flow} does not
     * belong to any task.
     * 
     * @see #joinTask(Task)
     * @see #forgetTask()
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod},
     *         or if the current flow does not belong to any task.
     */
    @FlowMethod(manual = true)
    public static void leaveTask() {
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            synchronized(cur) {
                Task task = cur.task;
                if (task == null) {
                    throw new IllegalStateException("Flow does not belong to any task.");
                }
                task.remove(cur);
                assert cur.task == null;
                cur.currentContext = null;
                cur.notifyAll();
            }
        } finally {
            frame.invoked();
        }
    }

    /**
     * Removes the {@linkplain Flow#current() current flow} from its
     * {@linkplain #task() current task}. This method sets the
     * {@linkplain #task() current flow's task} to <code>null</code>, or does
     * nothing if the {@linkplain Flow#current() current flow} does not belong
     * to any task.
     * 
     * @see #joinTask(Task)
     * @see #leaveTask()
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     */
    @FlowMethod(manual = true)
    public static boolean forgetTask() {
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            synchronized(cur) {
                Task task = cur.task;
                if (task == null) {
                    return false;
                }
                task.remove(cur);
                return true;
            }
        } finally {
            frame.invoked();
        }
    }

    public static FlowContext enterContext() {
        return current().doEnterContext();
    }

    public static void leaveContext(FlowContext context) {
        current().doLeaveContext(context);
    }

    public static Object getProperty(String propId) {
        return current().doGetProperty(propId);
    }

    public static Object setProperty(String propId, Object value) {
        return current().doSetProperty(propId, value);
    }

    public static FlowContext readContext() {
        return current().doReadContext();
    }

    public static void writeContext(FlowContext context) {
        current().doWriteContext(context);
    }

    public static void readProperties(Map<String, Object> dest) {
        current().doReadProperties(dest);
    }

    public static void writeProperties(Map<String, Object> properties) {
        current().doWriteProperties(properties);
    }

    /**
     * Waits for a notification that matches the informed key. The
     * {@linkplain current current flow} is {@linkplain suspend suspended} until
     * another flow invokes {@link #notifyAll(Object, Object)}.
     * <p>
     * The specified object is used to link <code>wait</code> and
     * <code>notify</code> pairs. For example, the invocation
     * 
     * <pre>
     *     Object result = Flow.wait("ABC");
     * </pre>
     * will wait until another flow invokes
     * 
     * <pre>
     *     Flow.notifyAll("ABC", "ResultOfABC");
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
     *     Object result = Flow.wait(matcher);
     * </pre>
     * The wait in the above example will resume for keys such as
     * <code>"ABCD"</code> or <code>"ABC123"</code>.
     * <p>
     * While waiting, the current flow will be suspended and thus will not
     * consume any thread. If such flow is resumed by means other than
     * {@link #notifyAll(Object, Object)}, the effect is unpredictable and the
     * task manager will be corrupt.
     * 
     * @param key The key to wait for (may be <code>null</code>), or an
     *        {@link IMatcher} instance, as above specified.
     * @return The <code>message</code> argument that was passed to
     *         {@link #notifyAll(Object, Object)}.
     * @throws IllegalStateException If there is no current task.
     * @see #waitMany(Object)
     * @see #notifyAll(Object, Object)
     * @see #send(Object, Object)
     * @see #receive(Object)
     */
    @FlowMethod
    public static Object wait(Object key) {
        return safeCurrent().doWait(key);
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
     * @throws IllegalStateException If there is no current task.
     * @see #wait(Object)
     * @see #notifyAll(Object, Object)
     */
    @FlowMethod(manual = true)
    public static Object waitMany(Object key) {
        MethodFrame frame = MethodFrame.enter(Flow.class, "waitMany", "(Ljava/lang/Object;)Ljava/lang/Object;");
        Object ret;
        try {
            switch (frame.resumePoint()) {
                case 0:
                    frame.notifyInvoke(1, 0, 0);
                case 1:
                    ret = safeCurrent().doWaitMany(key);
                    break;
                default:
                    throw new AssertionError();
            }
        } catch (Throwable e) {
            throw frame.exitThrowing(e);
        }
        frame.exit();
        return ret;
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
     * @throws IllegalStateException If there is no current task.
     * @see #wait(Object)
     * @see #waitMany(Object)
     * @see #send(Object, Object)
     * @see #receive(Object)
     */
    public static void notifyAll(Object key, Object message) {
        safeCurrent().doNotifyAll(key, message);
    }

    /**
     * Sends a message to the informed address. If another flow is listening on
     * the informed address, this method causes such flow to resume and then
     * returns immediately. Otherwise, the {@linkplain current current flow} is
     * {@linkplain suspend suspended} until some flow starts listening on the
     * informed address.
     * <p>
     * The informed address is not a network address. It is an object used to
     * link the sender and receiver flows. For example, the invocation
     * 
     * <pre>
     *     Flow.send("ABC", "MessageForABC");
     * </pre>
     * will send the object "MessageForABC" to a flow that invokes
     * 
     * <pre>
     *     Object result = Flow.receive("ABC");
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
     * <code>receive</code> method, the effect is unpredictable and the task
     * manager will be corrupt.
     * 
     * @param address The address that identifies the listening flow.
     * @param message The message to be sent.
     * @throws IllegalStateException If there is no current task.
     * @see #receive(Object)
     * @see #serve(Object)
     */
    @FlowMethod
    public static void send(Object address, Object message) {
        safeCurrent().doSend(address, message);
    }

    /**
     * Listens for a single message sent to the informed address. If another
     * flow is blocked while sending a message to the informed address, this
     * method causes such flow to resume and then immediately returns the sent
     * message. Otherwise the {@linkplain current current flow} is
     * {@linkplain suspend suspended} until such invocation is issued.
     * <p>
     * If another flow sends a message using {@link #call(Object, Object)}, this
     * method will return an instance of {@link IRequest} that contains the sent
     * message. Such call will be blocked until invocation of
     * {@link IRequest#respond(Object)}.
     * <p>
     * This method binds the current flow to the informed address. An address
     * can have at most one flow bound to it. When this method returns (that is,
     * when the message is received), the address will be free again.
     * <p>
     * While waiting, the current flow will be suspended and thus will not
     * consume any thread. If such flow is resumed by means other than a
     * <code>send</code> method, the effect is unpredictable and the task
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
     * @throws IllegalStateException If there is no current task.
     * @see #send(Object, Object)
     * @see #serve(Object)
     */
    @FlowMethod
    public static Object receive(Object address) {
        return safeCurrent().doReceive(address);
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
     * current task finishes.
     * 
     * @param address The address on which this flow will be listening.
     * @return The sent message, or an {@link IRequest} if the message was sent
     *         via {@link #call(Object, Object)}.
     * @throws AddressAlreadyInUseException If another flow is listening on this
     *         address.
     * @throws IllegalStateException If there is no current task.
     * @see #receive(Object)
     * @see #serveMany(Object)
     */
    @FlowMethod
    public static Object receiveMany(Object address) {
        return safeCurrent().doReceiveMany(address);
    }

    /**
     * Listens for a single request sent to the informed address. If another
     * flow is blocked while sending a message to the informed address, this
     * method causes such flow to resume and then immediately returns a request
     * with the cited message. Otherwise the {@linkplain current current flow}
     * is {@linkplain suspend suspended} until such invocation is issued.
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
     * <code>send</code> method, the effect is unpredictable and the task
     * manager will be corrupt.
     * <p>
     * For examples and more information, see the {@link #call(Object, Object)}
     * method.
     * 
     * @param address The address on which this flow will be listening.
     * @return An {@link IRequest} containing the sent message.
     * @throws AddressAlreadyInUseException If another flow is listening on this
     *         address.
     * @throws IllegalStateException If there is no current task.
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
     * current task finishes.
     * <p>
     * This method can be used to implement a very simple server.
     * 
     * @param address The address on which this flow will be listening.
     * @return An {@link IRequest} containing the sent message.
     * @throws AddressAlreadyInUseException If another flow is listening on this
     *         address.
     * @throws IllegalStateException If there is no current task.
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
     * {@linkplain current current flow} is {@linkplain suspend suspended}.
     * <p>
     * The following example illustrates the call behavior:
     * 
     * <pre>
     *  public class Example implements Runnable {
     *
     *      &#064;{@link FlowMethod}
     *      public void run() {
     *          // We must join a task.
     *          Flow.joinTask(new Task());
     *          if (Flow.split(1) == 0) {
     *              // Here is the server.
     *              IRequest request = <b>Flow.serve("PeerA")</b>;
     *              System.out.println("Request: " + request.request());
     *              request.response("I'm fine.");
     *          } else {
     *              // Here is the client.
     *              Object response = <b>Flow.call("PeerA", "How are you?")</b>;
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
     * <code>receive</code> method, the effect is unpredictable and the task
     * manager will be corrupt.
     * 
     * @param address The address that identifies the listening flow.
     * @param message The message to be sent.
     * @return The listener's response.
     * @throws IllegalStateException If there is no current task.
     * @see #receive(Object)
     * @see #serve(Object)
     */
    @FlowMethod
    public static Object call(Object address, Object message) {
        return safeCurrent().doCall(address, message);
    }

    @FlowMethod
    public static <T> T call(Object address, Object message, Class<T> resultClass) {
        if (resultClass == null) {
            throw new NullPointerException();
        }
        Object result = call(address, message);
        if (!resultClass.isInstance(result)) {
            if (result == null) {
                throw new RuntimeException("Unexpected result: null.");
            }
            throw new RuntimeException("Unexpected result: (" + result.getClass().getName() + ") " + result + ".");
        }
        return (T) result;
    }

    @FlowMethod
    public static void callVoid(Object address, Object message) {
        Object result = call(address, message);
        if (result != null) {
            throw new RuntimeException("Unexpected result: (" + result.getClass().getName() + ") " + result + ".");
        }
    }

    @FlowMethod
    public static Connection accept(Object matcher) {
        return safeCurrent().doAccept(matcher);
    }

    @FlowMethod
    public static Connection acceptMany(Object matcher) {
        return safeCurrent().doAcceptMany(matcher);
    }

    @FlowMethod
    public static Connection connect(Object matcher) {
        return safeCurrent().doConnect(matcher);
    }

    @FlowMethod
    public static Connection connectMany(Object matcher) {
        return safeCurrent().doConnectMany(matcher);
    }

    public static Flow snapshot() {
        Flow cur = Flow.fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            if (frame.isInvoking()) {
                cur.suspendInPlace();
                try {
                    return cur.copy();
                } finally {
                    cur.restoreInPlace();
                }
            }
            return null;
        } finally {
            frame.invoked();
        }
    }

    /**
     * Initiates concurrent execution on the invoker.
     * <p>
     * This method creates <i>n</i> {@linkplain #copy() shallow copies} of the
     * current flow, then executes each copy concurrently and starting from the
     * invocation point. In other words, this method is invoked once, but
     * returns 1+<i>n</i> times: 1 time for the invoker, and <i>n</i> times for
     * new flows. Notice that {@link #split(int) split(0)} is a no-effect
     * operation.
     * <p>
     * The returned value is an <code>int</code> that identifies the flow to
     * which the method returned. It will be <code>0</code> for the invoker, and
     * a distinct positive integer for new flows, ranging from <code>1</code> to
     * <i>n</i>.
     * <p>
     * The following example illustrates this behavior:
     * 
     * <pre>
     *    void example() {
     *        System.out.println(&quot;Before doFlow()&quot;);
     *        int i = doFlow();
     *        System.out.printf(&quot;doFlow(): %d\n&quot;, i);
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    int doFlow() {
     *        System.out.println(&quot;Before performSplit()&quot;);
     *        int i = performSplit();
     *        System.out.printf(&quot;performSplit(): %d\n&quot;, i);
     *        return i;
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    int performSplit() {
     *        System.out.println(&quot;Before split(2)&quot;);
     *        int i = Flow.split(2);
     *        System.out.printf(&quot;Split result: %d\n&quot;, i);
     *        return i;
     *    }
     * </pre>
     * The above snippet prints the following (under fair scheduling
     * conditions):
     * 
     * <pre>
     *     Before doFlow()
     *     Before performSplit()
     *     Before split(2)
     *     Split result: 0
     *     Split result: 1
     *     Split result: 2
     *     performSplit(): 0
     *     performSplit(): 1
     *     performSplit(): 2
     *     doFlow(): 0
     * </pre>
     * <p>
     * Each of the created flow runs on a possibly different {@linkplain Thread
     * thread} (the actual thread is defined by the {@linkplain #getManager()
     * flow manager}), and will be independent from all other flows, including
     * the invoker. As defined by the {@linkplain #copy() shallow copy}
     * behavior, all flows share heap objects referenced by the invoker stack
     * frames at the time of invocation.
     * <p>
     * If there is an active {@linkplain #fork(int) fork} at the time of
     * invocation, the invoker will remain on such fork, hence methods such as
     * {@link #merge()} and {@link #forgetFork()} are still valid for the
     * invoker. On the other hand, the newly created flows will be outside any
     * fork, which means that invoking {@link #merge()} and
     * {@link #forgetFork()} before an explicit fork on such flows will cause an
     * exception to be thrown.
     * <p>
     * 
     * @param n The number of flows to create. Must be non-negative.
     * @throws IllegalArgumentException If n is negative.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @return The flow identifier. Zero for the invoker, and a distinct integer
     *         ranging from <code>1</code> to <code>n</code> for each new
     *         branch.
     * @see #fork(int)
     */
    @FlowMethod(manual = true)
    public static int split(int n) {
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            if (frame.isInvoking()) {
                cur.suspendInPlace();
                try {
                    cur.manager.fork(cur, n);
                } finally {
                    cur.restoreInPlace();
                }
            }
            Fork fork = cur.currentFork;
            cur.currentFork = fork.previous;
            return fork.number;
        } finally {
            frame.invoked();
        }
    }

    /**
     * Starts a fork on the invoker. A fork is a temporary work executed by
     * concurrent {@linkplain Flow flows}.
     * <p>
     * This method is similar to {@link #split(int)}. In addition to create new
     * flows, it makes the invoker and each of the created flows to run in the
     * context of a fork. The invoker will stay in the fork until it explicitly
     * {@linkplain #merge() merges} or {@linkplain #forgetFork() forgets}. As a
     * convention in this documentation, new flows created by this method are
     * called <i>branches</i>, and the flow that invokes this method is called
     * the <i>fork-creator</i>.
     * <p>
     * If this method is invoked during a previous fork, a nested fork is
     * established. This method changes the behavior of {@link #merge()} and
     * other fork-ending methods, which always applies to the innermost fork.
     * Because of this, it is recommended to use the structured fork/merge
     * style, which use <code>try</code>/<code>finally</code> blocks as in the
     * following example:
     * 
     * <pre>
     *    ... // Single-threaded execution.
     *    int branch = Flow.fork(n);
     *    try {
     *        ... // Concurrent execution by n threads.
     *    } finally {
     *        Flow.{@link #merge()};
     *    }
     *    ... // Single-threaded execution.
     * </pre>
     * <p>
     * <p>
     * The returned value is an <code>int</code> that identifies the branch to
     * which the method returned. It will be <code>0</code> for the invoker, and
     * a distinct positive integer for new branches, ranging from <code>1</code>
     * to <i>n</i>.
     * <p>
     * If parameter <i>n</i> is zero, no new flow will be instantiated and the
     * invoker will continue execution in single-threaded mode. Still, the next
     * fork-ending operation will apply to such fork.
     * <p>
     * 
     * @param n The number of branches to create. Must be non-negative.
     * @throws IllegalArgumentException If n is negative.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @return The branch identifier. Zero for the invoker, and a distinct
     *         integer ranging from <code>1</code> to <code>n</code> for each
     *         new branch.
     * @see #merge(long, TimeUnit)
     * @see #forgetFork()
     */
    @FlowMethod(manual = true)
    public static int fork(int n) {
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            if (frame.isInvoking()) {
                // We are performing a fork.
                cur.suspendInPlace();
                try {
                    cur.manager.fork(cur, n);
                } finally {
                    cur.restoreInPlace();
                }
            }
            return cur.currentFork.number;
        } finally {
            frame.invoked();
        }
    }

    /**
     * Ends a fork by restoring single-threaded execution. This method causes
     * the invoker to exit the current {@linkplain #fork(int) fork}. Depending
     * on who is the invoker, the behavior will be different.
     * <p>
     * <b>If the invoker is the fork-creator:</b> This method blocks until each
     * of the corresponding branches ends (see below), or until this thread is
     * interrupted, whichever comes first. If the merge is successful (all
     * branches have ended), the invoker exits the fork, restoring the next
     * outer fork, if any. Otherwise (the thread is interrupted while at least
     * one branch is active), the invoker will stay in the fork. If all other
     * branches have ended before invocation of this method, it will return
     * immediately without checking the thread's interrupt flag.
     * <p>
     * <b>If the invoker is a branch:</b> This method does not return. It ends
     * the flow immediately, as if {@link #end()} were invoked. Notice that even
     * <code>finally</code> blocks will not execute.
     * <p>
     * This method considers only branches created by the last invocation of
     * {@link #fork(int)}.
     * <p>
     * A branch will be active (that is, it will not end) until one of the
     * following happens:
     * <ul>
     * <li>The {@linkplain Flow flow creator} method completes normally or by
     * exception.</li>
     * <li>The branch calls {@link Flow#end()}.</li>
     * <li>The branch calls a merge method, either {@link #merge()} or
     * {@link #merge(long, TimeUnit)}.</li>
     * <li>The branch forgets the fork by calling {@link #forgetFork()}.</li>
     * <li>The branch ends the fork by calling {@link #endFork()}.</li>
     * </ul>
     * A suspended branch is considered to be active. Hence, this method will
     * block the fork-creator even if all other branches are suspended. If this
     * happens on a real scenario, another thread must resume the branches,
     * which will allow them to end normally, so this method can return to the
     * fork-creator.
     * <p>
     * Whenever this method returns normally, it is guaranteed that the invoker
     * was the fork-creator and no branch will be active.
     * 
     * @throws InterruptedException If the current thread was interrupted while
     *         there was at least one active branch.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @see #fork(int)
     * @see #merge(long, TimeUnit)
     * @see #forgetFork()
     */
    @FlowMethod(manual = true)
    public static void merge() throws InterruptedException {
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            Fork fork = cur.currentFork;
            if (fork != null) {
                switch (fork.merge(0, null)) {
                    case Fork.EXIT:
                        assert fork.previous == null;
                        cur.currentFrame.leaveThread();
                        return;
                    case Fork.MERGED:
                        cur.currentFork = fork.previous;
                        return;
                    default:
                        throw new AssertionError();
                }
            }
            throw new IllegalStateException("No active fork.");
        } finally {
            frame.invoked();
        }
    }

    /**
     * Attempts to end a fork, or give-up after a timeout elapses. This method
     * is the time-constrained version of {@link #merge()}. Depending on who is
     * the invoker, the behavior will be different.
     * <p>
     * <b>If the invoker is the fork-creator:</b> This method blocks until each
     * of the corresponding branches ends (see {@link #merge()}), or until the
     * given timeout expires, or until this thread is interrupted, whichever
     * comes first. If the merge is successful (all branches have ended before
     * the timeout expire), the invoker exits the fork, restoring the next outer
     * fork, if any, and this method returns <code>true</code>. If the timeout
     * expires while at least on branch is active, the invoker will stay in the
     * fork, and this method returns <code>false</code>. Otherwise (the thread
     * is interrupted while at least one branch is active), the invoker will
     * stay in the fork. If all other branches have ended before invocation of
     * this method, it will return immediately without checking the thread's
     * interrupt flag.
     * <p>
     * <b>If the invoker is a branch:</b> This method does not return. It ends
     * the flow immediately, as if {@link #end()} were invoked. Notice that even
     * <code>finally</code> blocks will not execute.
     * <p>
     * Whenever this method returns normally, it is guaranteed that the invoker
     * will be the fork-creator.
     * 
     * @return <code>true</code> if all other branches have ended,
     *         <code>false</code> if there was at least one active branch when
     *         the timeout elapsed.
     * @throws InterruptedException If the current thread was interrupted during
     *         the wait for branches to end.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws NullPointerException If <code>unit</code> is null.
     * @see #fork(int)
     * @see #merge()
     * @see #forgetFork()
     */
    @FlowMethod(manual = true)
    public static boolean merge(long timeout, TimeUnit unit) throws InterruptedException {
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            if (unit == null) {
                throw new NullPointerException();
            }
            Fork fork = cur.currentFork;
            if (fork == null) {
                throw new IllegalStateException("No active fork.");
            }
            switch (fork.merge(timeout, unit)) {
                case Fork.EXIT:
                    assert fork.previous == null;
                    cur.currentFrame.leaveThread();
                    return false;
                case Fork.MERGED:
                    cur.currentFork = fork.previous;
                    return true;
                case Fork.TIMEOUT:
                    return false;
                default:
                    throw new AssertionError();
            }
        } finally {
            frame.invoked();
        }
    }

    /**
     * Forgets the current fork without waiting for any branch. This method is
     * used to exit the current fork without waiting for, nor causing, any
     * branch to end.
     * <p>
     * If the invoker is the fork-creator, this method exits the fork and
     * restores the next outer fork, if any, and then immediately returns. If
     * the invoker is a branch, the flow will continue to run on the same
     * thread, but "detached" from the fork, as if created by
     * {@link #split(int)}. Therefore the fork-creator will consider this branch
     * as ended, which means that it might unblock an ongoing call to
     * {@link #merge()} in the fork-creator.
     * <p>
     * This method does not cause the current flow to end, even if the invoker
     * is a branch. If you need this, use {@link #endFork()} instead.
     * 
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @see #fork(int)
     * @see #merge(long, TimeUnit)
     * @see #endFork()
     */
    @FlowMethod(manual = true)
    public static void forgetFork() {
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            Fork fork = cur.currentFork;
            if (fork == null) {
                throw new IllegalStateException("No active fork.");
            }
            if (fork.unfork()) {
                cur.currentFork = fork.previous;
            } else {
                assert fork.previous == null;
            }
        } finally {
            frame.invoked();
        }
    }

    /**
     * Ends the current fork without waiting for any branch. This method is used
     * to exit the current fork without waiting for any branch to end.
     * <p>
     * If the invoker is the fork-creator, this method exits the fork and
     * restores the next outer fork, if any, and then immediately returns. If
     * the invoker is a branch, it will end the flow immediately, as if
     * {@link #end()} were invoked. This means that it might unblock an ongoing
     * call to {@link #merge()} in the fork-creator.
     * 
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @see #fork(int)
     * @see #merge(long, TimeUnit)
     * @see #forgetFork()
     */
    @FlowMethod(manual = true)
    public static void endFork() {
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            Fork fork = cur.currentFork;
            if (fork == null) {
                throw new IllegalStateException("No active fork.");
            }
            if (fork.unfork()) {
                cur.currentFork = fork.previous;
            } else {
                assert fork.previous == null;
                frame.leaveThread();
            }
        } finally {
            frame.invoked();
        }
    }

    /**
     * Ends the current flow. This method does not return. It causes execution
     * to continue after the <a href="#flowcreator">flow-creator</a>. The
     * following sample illustrates this behavior:
     * 
     * <pre>
     *    void example() {
     *        System.out.println(&quot;Before doFlow()&quot;);
     *        int i = doFlow();
     *        System.out.printf(&quot;doFlow(): %d\n&quot;, i);
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    int doFlow() {
     *        System.out.println(&quot;Before end()&quot;);
     *        Flow.end();
     *        System.out.println(&quot;After end()&quot;);
     *        return 5;
     *    }
     * 
     * </pre>
     * 
     * The above snippet prints the following:
     * 
     * <pre>
     *     Before doFlow()
     *     Before end()
     *     doFlow(): 0
     * </pre>
     * <p>
     * This method causes the flow-creator to return a "zero" value
     * corresponding to its return type:
     * <ul>
     * <li><b>Reference-type:</b> returns <code>null</code>.</li>
     * <li><b>Numeric-type:</b> returns <code>0</code>.</li>
     * <li><b>boolean:</b> returns <code>false</code>.</li>
     * <li><b>char:</b> returns <code>(char) 0</code>.</li>
     * </ul>
     * <p>
     * If the current flow was created by an utility such as {@link #split(int)}
     * or {@link #returnAndContinue()}, this method never returns and simply
     * releases the current thread.
     * 
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     */
    @FlowMethod(manual = true)
    public static void end() {
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        frame.leaveThread();
    }

    /**
     * Performs <code>return</code> while continuing asynchronously.
     * <p>
     * This method is used to perform a local {@linkplain #split(int) split}
     * operation. In the current flow, it works as if <code>return</code> were
     * executed. In a newly created flow, the invoker is resumed from the point
     * of invocation. The new flow starts from the invoker method, and not from
     * the <a href="#flowcreator">flow-creator</a> as it would be if
     * {@link #split(int)} were used. The following example illustrates this
     * behavior:
     * 
     * <pre>
     *    &#064;{@link FlowMethod}
     *    void example() {
     *        System.out.println(&quot;Before doFlow()&quot;);
     *        doFlow();
     *        System.out.println(&quot;After doFlow()&quot;);
     *        Thread.sleep(50); // Schedule the new flow's thread. 
     *        System.out.println(&quot;Done&quot;);
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    void doFlow() {
     *        System.out.println(&quot;Before returnAndContinue()&quot;);
     *        Flow.returnAndContinue();
     *        System.out.println(&quot;After returnAndContinue()&quot;);
     *    }
     * 
     * 
     * </pre>
     * <p>
     * The above example prints the following:
     * 
     * <pre>
     *     Before doFlow()
     *     Before returnAndContinue()
     *     After doFlow()
     *     After returnAndContinue()
     *     Done
     * </pre>
     * <p>
     * Notice that the new flow ends when the method <code>doFlow()</code> ends.
     * Calling this method more than once in the same method works, but is
     * redundant because in the second and subsequent calls, the work doesn't
     * need to be done in a new flow.
     * <p>
     * This method must be called only by a <code>void</code> method. There is
     * one version of this method for each possible return value.
     * 
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>void</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue() {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_VOID);
        if (frame != null) {
            frame.result();
        }
    }

    /**
     * Performs <code>return</code> <i>boolean-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>boolean</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>boolean</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(boolean v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_BOOLEAN);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>char-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>char</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>char</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(char v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_CHAR);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>byte-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>byte</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>byte</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(byte v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_BYTE);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>short-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>short</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>short</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(short v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_SHORT);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Causes a method to return an <code>int</code> while continuing
     * asynchronously.
     * <p>
     * This method is used to perform a local {@linkplain #split(int) split}
     * operation. In the current flow, it works as if <code>return v</code> were
     * executed. In a newly created flow, the invoker is resumed from the point
     * of invocation. The new flow starts from the invoker method, and not from
     * the <a href="#flowcreator">flow-creator</a> as it would be if
     * {@link #split(int)} were used. The following example illustrates this
     * behavior:
     * <p>
     * 
     * <pre>
     *    &#064;{@link FlowMethod}
     *    void example() {
     *        System.out.println(&quot;Before doFlow()&quot;);
     *        int i = doFlow();
     *        System.out.printf(&quot;doFlow(): %d\n&quot;, i);
     *        Thread.sleep(50); // Schedule the new flow's thread. 
     *        System.out.println(&quot;Done&quot;);
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    int doFlow() {
     *        System.out.println(&quot;Before returnAndContinue()&quot;);
     *        Flow.returnAndContinue(123);
     *        System.out.println(&quot;After returnAndContinue()&quot;);
     *        return 456;
     *    }
     * </pre>
     * The above example prints the following:
     * 
     * <pre>
     *     Before doFlow()
     *     Before returnAndContinue()
     *     doFlow(): 123
     *     After returnAndContinue()
     *     Done
     * </pre>
     * <p>
     * Notice that the new flow ends when the method <code>doFlow()</code> ends.
     * Hence the value of the actual <code>return</code> statement (
     * <code>456</code> in the example) is discarded. Calling this method more
     * than once in the same method works, but is redundant because in the
     * second and subsequent calls, the work doesn't need to be done in a new
     * flow.
     * <p>
     * This method must be called only by an <code>int</code> method. There is
     * one version of this method for each possible return value.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not an
     *         <code>int</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(int v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_INT);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>long-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>long</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>long</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(long v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_LONG);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>float-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>float</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>float</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(float v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_FLOAT);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>double-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for a <code>double</code> method.
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a
     *         <code>double</code> method.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(double v) {
        MethodFrame frame = forkAndReturn(Types.TYPE_CODE_DOUBLE);
        if (frame != null) {
            frame.result(v);
        }
    }

    /**
     * Performs <code>return</code> <i>reference-value</i> while continuing
     * asynchronously. This method is similar to {@link #returnAndContinue(int)}
     * , except for that it must be invoked for an reference method.
     * <p>
     * 
     * @param v The value to return.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws IllegalReturnValueException If the invoker is not a reference
     *         method.
     * @throws ClassCastException If the returned object is not assignable to
     *         the invoker's return type.
     */
    @FlowMethod(manual = true)
    public static void returnAndContinue(Object v) {
        Flow cur = fromInvoker();
        MethodFrame returning;
        MethodFrame frame = cur.currentFrame;
        try {
            // Use ';' because an object descriptor is "L<package>/<class>;" .
            frame.checkResultType(';');
            if (v != null) {
                Class<?> clazz;
                try {
                    clazz = frame.getMethodReturnType();
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
                clazz.cast(v);
            }
            if (frame.isInvoking()) {
                // We are performing a fork.
                Flow copy;
                cur.suspendInPlace();
                try {
                    copy = cur.frameCopy();
                } finally {
                    cur.restoreInPlace();
                }
                copy.activate();
                returning = frame;
            } else {
                returning = null;
            }
        } finally {
            frame.invoked();
        }
        if (returning != null) {
            returning.result(v);
        }
    }

    private static MethodFrame forkAndReturn(char type) {
        Flow cur = fromInvoker();
        MethodFrame frame = cur.currentFrame;
        try {
            frame.checkResultType(type);
            if (frame.isInvoking()) {
                // We are performing a fork.
                Flow copy;
                cur.suspendInPlace();
                try {
                    copy = cur.frameCopy();
                } finally {
                    cur.restoreInPlace();
                }
                copy.activate();
                return frame;
            }
            // We are restoring from a fork.
            return null;
        } finally {
            frame.invoked();
        }
    }

    @FlowMethod(manual = true)
    public static Object invoke(Method method, Object owner, Object... args) throws IllegalAccessException, InvocationTargetException {
        return method.invoke(owner, args);
    }

    @FlowMethod
    public synchronized Object synchronizedCall(CallableFlow callable) {
        return callable.call();
    }

    /**
     * Equivalent to {@link #suspend(Object) suspend(null)}.
     */
    @FlowMethod
    public static Object suspend() {
        return signal(new SuspendSignal(null));
    }

    /**
     * Suspends the current flow, allowing the current thread to be released.
     * This method simply sends a {@link SuspendSignal} to the <a
     * href="#flowcontroller">flow-controller</a>, which by
     * {@linkplain FlowSignal#defaultAction() default} does
     * {@linkplain SuspendSignal#defaultAction() nothing}. This can be used to
     * release the current thread to other tasks.
     * <p>
     * The informed argument is simply passed to the
     * {@linkplain SuspendSignal#SuspendSignal(Object) constructor} of
     * {@link SuspendSignal}, and can be later obtained, usually in the <a
     * href="#flowcontroller">flow-controller</a>, by invoking
     * {@link SuspendSignal#getResult()}. The informed argument is usually used
     * to tell the reason of suspension. That is, its usually a merely
     * informative value, used for debugging and tracing purposes.
     * <p>
     * When a flow chooses to suspend itself, it usually must provide means to
     * be {@linkplain #resume(Object) resumed} when some event occurs, and not
     * rely on the flow-controller for this task. For example, if a flow chooses
     * to suspend itself while waiting for user input, it should store
     * {@linkplain Flow#current() itself} on the user's session storage
     * <i>before</i> suspending, so that when user input happens, the event
     * handler can invoke {@link #resume(Object)} on the suspended flow.
     * <p>
     * This method returns the value passed to {@link #resume(Object)}, and
     * might throw an exception if {@link #resumeThrowing(Throwable)} were
     * instead used. This method can return multiple times and/or in different
     * flows, at the discretion of whoever uses the suspended flow.
     * <p>
     * This method behaves exactly as the expression
     * <code>signal(new SuspendSignal(argument))</code>.
     * 
     * @param argument The argument to be associated with {@link SuspendSignal}.
     * @return The object passed to {@link #resume(Object)}.
     * @see #signal(FlowSignal)
     * @see #suspend()
     * @see SuspendSignal
     * @see #resume()
     * @see #resume(Object)
     */
    @FlowMethod
    public static Object suspend(Object argument) {
        return signal(new SuspendSignal(argument));
    }

    /**
     * Suspends the flow and sends a signal to the flow-controller.
     * <p>
     * This method first suspends the current flow at the point of invocation.
     * Then it sends the specified signal to the <a
     * href="#flowcontroller">flow-controller</a>, as if the signal were thrown
     * by the <a href="#flowcreator">flow-creator</a> (see below). After this,
     * the next actions are fully determined by the flow-controller, which
     * typically uses the signal to decide.
     * <p>
     * This method returns only when the flow-controller invokes a
     * <code>resume</code> method (see below) on this flow or on a copy. Notice
     * that this method may complete on a different flow and it may also
     * complete more than once, at the discretion of the flow-controller.
     * <p>
     * Although the signal is an exception, this method doesn't throw the signal
     * on the current flow. The signal is thrown by the subsystem after the flow
     * have been suspended. To the flow-controller, the signal appears to have
     * been thrown by the flow-creator. This mechanism is illustrated in the
     * following snippet:
     * 
     * <pre>
     *    void example() { // This is the flow-controller.
     *        try {
     *            doFlow();
     *            System.out.println(&quot;doFlow() returned&quot;);
     *        } catch (FlowSignal signal) {
     *            System.out.println(&quot;Caught by the flow-controller&quot;);
     *            System.out.println(&quot;Calling Flow.resume()&quot;);
     *            signal.getFlow().{@link #resume()};
     *            System.out.println(&quot;Flow ended&quot;);
     *        }
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    void doFlow() { // This is the flow-creator.
     *        try {
     *            doSignal();
     *            System.out.println(&quot;doSignal() returned&quot;);
     *        } catch (FlowSignal signal) {
     *            System.out.println(&quot;Caught by the flow-creator&quot;);
     *        }
     *    }
     * 
     *    &#064;{@link FlowMethod}
     *    void doSignal() { // This is an ordinary flow method.
     *        try {
     *            System.out.println(&quot;Sending signal&quot;);
     *            FlowSignal signal = new MyFlowSignal();
     *            Flow.signal(signal);
     *            System.out.println(&quot;Returned from signal (resumed)&quot;);
     *        } catch (FlowSignal signal) {
     *            System.out.println(&quot;Caught by doSignal()&quot;);
     *        }
     *    }
     * 
     * 
     * </pre>
     * The above snippet prints the following:
     * 
     * <pre>
     *     Sending signal
     *     Caught by the flow-controller
     *     Calling Flow.resume()
     *     Returned from signal (resumed)
     *     doSignal() returned
     *     Flow ended
     * </pre>
     * The flow-controller can, among other actions, create a
     * {@linkplain #copy() shallow copy} of this flow, serialize this flow and
     * deserialize in another machine instance, ignore the signal and never
     * {@linkplain #resume() resume}, or wait for some specific condition before
     * resuming. To provide satisfactory usability, the flow-controller must
     * proceed as specified by the received {@linkplain FlowSignal signal}
     * object. There are some well-known signal classes such as
     * {@link DelayedCallSignal} and {@link SuspendSignal}, but developers are
     * free to create new signals, as long as they provide support for them in
     * the flow-controller.
     * <p>
     * The completion of this method depends on the chosen <code>resume</code>
     * method:
     * <ul>
     * <li>If the flow is resumed with {@link #resume()}, this method returns
     * <code>null</code>.</li>
     * <li>If the flow is resumed with {@link #resume(Object)}, this method
     * returns the object passed to the <code>resume</code> method.</li>
     * <li>If the flow is resumed with {@link #resumeThrowing(Throwable)}, this
     * method throws {@link ResumeException} whose
     * {@linkplain Throwable#getCause() cause} is the exception passed to the
     * <code>resumeThrowing</code> method.</li>
     * </ul>
     * <p>
     * This method is used to implement utilities such as
     * {@link #suspend(Object)} and {@link ThreadFreeLock}. It is the lowest
     * level API for implementing features such as releasing a pooled thread
     * before completion and serializing thread state for long running
     * processes.
     * 
     * @param signal The signal to be sent to the flow-controller.
     * @return The object passed to {@link #resume(Object)} method.
     * @throws IllegalStateException If the invoker is not a {@link FlowMethod}.
     * @throws NullPointerException If <code>signal</code> is <code>null</code>.
     * @throws ResumeException If the flow is resuming through
     *         {@link #resumeThrowing(Throwable)}.
     */
    @FlowMethod(manual = true)
    public static Object signal(FlowSignal signal) {
        Flow cur = fromInvoker();
        synchronized(cur) {
            MethodFrame frame = cur.currentFrame;
            try {
                if (frame.isInvoking()) {
                    // We are suspending.
                    cur.currentContext = cur.getCheckpointContext();
                    // log("Signaling, signal=" + signal);
                    if (signal == null) {
                        throw new NullPointerException();
                    }
                    if (cur.blockingLevel > 0) {
                        cur.state = FlowState.BLOCKED;
                        cur.result = signal;
                        signal.flow = cur;
                        if (cur.task != null) {
                            cur.task.notifySuspend(cur);
                        }
                        cur.notifyAll();
                        while (cur.state == FlowState.BLOCKED) {
                            try {
                                cur.wait();
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        return cur.restore();
                    }
                    cur.state = SUSPENDING;
                    cur.suspendedFrame = frame.copy(cur);
                    // The result will carry the signal, so it can be thrown on finish().
                    cur.result = signal;
                    signal.flow = cur;
                    frame.leaveThread();
                    return null;
                }
                // We are restoring from suspended state.
                return cur.restore();
            } finally {
                frame.invoked();
            }
        }
    }

    /**
     * Returns the current flow. This method returns non-null if the current
     * thread is executing a {@linkplain FlowMethod flow method}. Otherwise it
     * returns <code>null</code>. The current flow will always be
     * {@link #ACTIVE} .
     * 
     * @return The current flow, or <code>null</code> no {@linkplain FlowMethod
     *         flow method} is running on the current thread.
     */
    public static Flow current() {
        return current.get();
    }

    private static Flow fromInvoker() {
        Flow ret = current();
        if (ret != null) {
            MethodFrame frame = ret.currentFrame;
            if (frame != null && (frame.isInvoking() || frame.isRestoring())) {
                return ret;
            }
        }
        throw new IllegalStateException("Illegal operation for a non-flow method.");
    }

    /**
     * Returns the current flow, or throws an exception if there is no current
     * flow. This method is like {@link #current()}, except in that it never
     * returns <code>null</code>. In the absence of a current flow, it will
     * throw an exception.
     * 
     * @return The current flow.
     * @throws IllegalStateException If there is no current flow.
     */
    public static Flow safeCurrent() {
        Flow ret = current.get();
        if (ret == null) {
            throw new IllegalStateException("No current flow.");
        }
        return ret;
    }

    public static void log(String msg) {
        synchronized(System.out) {
            System.out.print('[');
            System.out.print(Thread.currentThread().getName());
            System.out.print(',');
            System.out.print(Flow.current());
            System.out.print(',');
            StackTraceElement[] st = new Exception().getStackTrace();
            System.out.print(st[1].toString());
            System.out.print("] ");
            System.out.println(msg);
        }
    }

    static void clearThread() {
        current.set(null);
    }

    @FlowMethod(manual = true)
    static MethodFrame invokerFrame() {
        return fromInvoker().currentFrame;
    }

    static MethodFrame enter(Object owner, String name, String desc) {
        Flow cur = current();
        if (cur == null || cur.isWorking()) {
            cur = newFlow(cur);
            current.set(cur);
        }
        return cur.newFrame(owner, name, desc);
    }

    static Object getLocal(FlowLocal<?> local) {
        return safeCurrent().doGetLocal(local);
    }

    static Object setLocal(FlowLocal<?> local, Object value) {
        return safeCurrent().doSetLocal(local, value);
    }

    static Object removeLocal(FlowLocal<?> local) {
        return safeCurrent().doRemoveLocal(local);
    }

    private static void setCurrent(Flow flow) {
        if (flow == null) {
            throw new NullPointerException();
        }
        assert flow.previous == null;
        flow.previous = current.get();
        current.set(flow);
    }

    private static Flow newFlow(Flow previous) {
        Flow ret = new Flow(FlowManager.getNext(), previous);
        ret.state = ACTIVE;
        return ret;
    }

    private transient Flow previous;
    private final FlowManager manager;
    private FlowState state;
    private int blockingLevel;
    private MethodFrame currentFrame;
    private MethodFrame suspendedFrame;
    transient Task task;
    private FlowContext currentContext;
    transient Fork currentFork;
    private Object result;
    private WeakHashMap<FlowLocal<?>, Object> locals;

    private Flow(FlowManager manager, Flow previous) {
        this.manager = manager;
        this.previous = previous;
        state = ENDED;
    }

    public FlowManager getManager() {
        return manager;
    }

    public Flow getPrevious() {
        return previous;
    }

    /**
     * This flow's state, which will be {@link #ACTIVE}, {@link #SUSPENDED},
     * {@link #PASSIVE} or {@link #ENDED}.
     * 
     * @see #waitSuspended()
     * @see #waitNotRunning()
     */
    public FlowState getState() {
        return state;
    }

    public boolean isActive() {
        return state == ACTIVE || state == TEMP_SUSP || state == INTERRUPTED;
    }

    public boolean isSuspended() {
        return state == SUSPENDED || state == PASSIVE;
    }

    public boolean isEnded() {
        return state == ENDED;
    }

    /**
     * Equivalent to {@link #resume(Object) resume(null)}.
     */
    public Object resume() {
        return resume(null);
    }

    /**
     * Resumes this flow throwing an exception. This method causes the last
     * invocation of {@link #signal(FlowSignal)} inside this flow to throw a
     * {@link ResumeException} whose {@linkplain Throwable#getCause() cause} is
     * the informed exception.
     * <p>
     * In all other aspects, this method behaves as {@link #resume(Object)}.
     * That is, the invoker will block until the flow completes, will be the new
     * <a href="#flowcontroller">flow-controller</a>, and might need to handle
     * {@linkplain #signal(FlowSignal) signals}.
     * 
     * @param exception The exception that will be the cause of the
     *        {@link ResumeException} to be throwed.
     * @return The value returned by the <a
     *         href="#flowcreator">flow-creator</a>, or <code>null</code> if the
     *         flow-creator is a <code>void</code> method.
     * @throws NullPointerException If <code>exception</code> is null.
     * @throws IllegalStateException If this flow is not suspended.
     * @see #signal(FlowSignal)
     * @see #resume(Object)
     * @see #activateThrowing(Throwable)
     */
    public Object resumeThrowing(Throwable exception) {
        if (exception == null) {
            throw new NullPointerException();
        }
        return resume(new ExceptionEnvelope(exception));
    }

    /**
     * Resumes this flow. This method causes the last invocation of
     * {@link #signal(FlowSignal)} inside this flow to return. The flow resumes
     * execution directly on the current thread, and therefore this method only
     * returns when the flow finishes. This means that the invoker of this
     * method will be the new <a href="#flowcontroller">flow-controller</a>, and
     * might need to handle {@linkplain #signal(FlowSignal) signals}. To resume
     * this flow on another thread, use {@link #activate(Object)}.
     * <p>
     * The informed parameter is simply returned to the flow as a normal result
     * of {@link #signal(FlowSignal)}.
     * <p>
     * The completion of this method is defined as follows:
     * <ul>
     * <li>If the flow finishes normally, this method returns the value returned
     * by the <a href="#flowcreator">flow-creator</a>.</li>
     * <li>If the flow throws an exception, this method throws a
     * {@link FlowException} having the exception as its
     * {@linkplain Throwable#getCause() cause}.
     * <li>If the flow sends a {@linkplain #signal(FlowSignal) signal}, the flow
     * is first suspended, then the signal is thrown as an exception.
     * </ul>
     * In other words, the invoker of {@link #resume(Object)} will behave as an
     * ordinary <a href="#flowcontroller">flow-controller</a>.
     * 
     * @param signalResult The value that {@link #signal(FlowSignal)} must
     *        return to its invoker.
     * @return The value returned by the <a
     *         href="#flowcreator">flow-creator</a>, or <code>null</code> if the
     *         flow-creator is a <code>void</code> method.
     * @throws IllegalStateException If this flow is not suspended.
     * @see #signal(FlowSignal)
     * @see #resume()
     * @see #resumeThrowing(Throwable)
     * @see #activate()
     * @see #activate(Object)
     */
    public Object resume(Object signalResult) {
        synchronized(this) {
            try {
                while (state == SUSPENDING) {
                    wait();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (state != SUSPENDED && state != PASSIVE) {
                throw new IllegalStateException("Cannot resume if the flow is " + state + '.');
            }
            if (task != null) {
                task.notifyResume(this);
            }
            // We redo the check because the task might have changed the state or might forget to
            // set this PASSIVE flow to SUSPENDED.
            if (state != SUSPENDED) {
                throw new IllegalStateException("Cannot resume if the flow is " + state + '.');
            }
            state = ACTIVE;
        }
        String debugMethodName = null;
        boolean success = false;
        try {
            restoreFrame();
            setCurrent(this);
            result = signalResult;
            Class<?> clazz = suspendedFrame.getTargetClass();

            assert (debugMethodName = clazz.getName() + "#" + suspendedFrame.getMethodName()) != "";
            // log("Resuming " + debugMethodName + ", result = " + signalResult);

            try {
                Class<?>[] argClasses = suspendedFrame.getMethodParameterTypes();
                Object[] argValues = new Object[argClasses.length];

                /* TODO:
                 *   For now, invokes the root FlowMethod method; in the future, the enhancer
                 *   should add a synthetic static method to the root class. This synthetic
                 *   method will invoke the root FlowMethod method passing primitive argument
                 *   values obtained directly through the MethodFrame. This will save
                 *   reflection and boxing costs, and will remove visibility problems.
                 */
                NoSuchMethodException fe = null;
                for (;;) {
                    try {
                        Method m = clazz.getDeclaredMethod(suspendedFrame.getMethodName(), argClasses);
                        m.setAccessible(true);
                        Object owner = getRootValues(m, argValues);
                        result = m.invoke(owner, argValues);
                        success = true;
                        return result;
                    } catch (NoSuchMethodException e) {
                        if (fe == null) {
                            fe = e;
                        }
                        clazz = clazz.getSuperclass();
                        if (clazz == null) {
                            throw fe;
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                success = true;
                if (e.getCause() instanceof FlowSignal) {
                    throw (FlowSignal) e.getCause();
                }
                throw new FlowException(e.getCause());
            }
        } finally {
            if (!success) {
                log("state == " + state + ", method=" + debugMethodName);
            }
        }
    }

    /**
     * Equivalent to {@link #activate(Object) activate(null)}.
     */
    public Future<?> activate() {
        return activate(null);
    }

    /**
     * Schedules this flow to {@linkplain #resume(Object) resume} in another
     * thread, and throws an exception upon resuming. This method is identical
     * to {@link #activate(Object)}, except in that resuming is done with
     * {@link #resumeThrowing(Throwable)} instead of {@link #resume(Object)}.
     * 
     * @see #resumeThrowing(Throwable)
     * @see #activate()
     * @see #activate(Object)
     */
    public Future<?> activateThrowing(Throwable exception) {
        if (exception == null) {
            throw new NullPointerException();
        }
        return activate(new ExceptionEnvelope(exception));
    }

    /**
     * Schedules this flow to {@linkplain #resume(Object) resume} in another
     * thread. The method invokes the flow's manager
     * {@link FlowManager#submit(Flow, Object)} method, which will assign resume
     * execution to some thread. The thread's run is a simple <a
     * href="#flowcontroller">flow-controller</a>. It can be roughly defined by
     * this pseudo-code:
     * 
     * <pre>
     *     public void run() {
     *         try {
     *             flow.resume(signalResult);
     *         } catch (FlowSignal signal) {
     *             signal.defaultAction(); // Always call the default action.
     *         } catch (Throwable e) {
     *             log(e); // Logs the exception.
     *         }
     *     }
     * </pre>
     * 
     * The returned {@link Future} can be use to query execution status. Notice
     * that if such <code>Future</code> is {@linkplain Future#isDone() done}, it
     * does not necessarily means that the flow finished.
     * {@link Future#isDone()} will return <code>true</code> even when the flow
     * was just suspended.
     * 
     * @param signalResult The argument to be passed to {@link #resume(Object)}
     *        method. This argument will be returned to the flow by the
     *        {@link #signal(FlowSignal)} that causes suspension.
     * @return A {@link Future} that can be used to query execution status.
     * @see #resume(Object)
     * @see #activate()
     * @see #activateThrowing(Throwable)
     */
    public synchronized Future<?> activate(Object signalResult) {
        if (state == FlowState.BLOCKED) {
            state = FlowState.ACTIVE;
            notifyAll();
            return null;
        }
        return manager.submit(this, signalResult);
    }

    public synchronized void interrupt() {
        if (state == INTERRUPTED) {
            return;
        }
        if (state == ACTIVE) {
            state = INTERRUPTED;
            return;
        }
        if (state == PASSIVE) {
            throw new IllegalStateException("Cannot interrupt while flow is " + state + ".");
        }
        try {
            waitNotRunning();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (state == ENDED) {
            return;
        }
        activate(INTERRUPT_SIGNAL);
    }

    /**
     * Performs a shallow copy on this flow. A shallow copy is a simple copy of
     * stack frames, with all local and stack variables, starting from the <a
     * href="#flowcreator">flow-creator</a> method, and until the current
     * position in the flow's execution. The value of all variables are copied
     * by simple assignment, including references. Because no heap object is
     * ever cloned or serialized by this method, this flow and the copy will
     * share heap objects referenced by the copied stack variables.
     * <p>
     * If this flow is suspended, the returned copy can be
     * {@linkplain #resume() resumed} and it will run just after the suspend
     * invocation, as if it were suspended by itself. This process does not
     * affect the current flow, which was only the source in a copy operation.
     * Many copies can be created from a single flow, which allows
     * implementation of utilities such as {@link #split(int)} and
     * {@link EventPicker}.
     * <p>
     * The target flow must be either suspended or ended, and hence this method
     * cannot be used to copy a running flow. To get the state of a running
     * flow, use {@link Continuation}.
     * <p>
     * 
     * @return A flow whose execution context is identical to this flow, sharing
     *         all heap objects referenced by stack frames.
     * @throws IllegalStateException If this flow is not suspended nor ended.
     */
    public synchronized Flow copy() {
        checkSteady();
        Flow ret = new Flow(manager, null);
        ret.state = state == TEMP_SUSP ? SUSPENDED : state;
        ret.suspendedFrame = suspendedFrame.copy(ret);
        ret.result = result;
        if (task != null) {
            task.add(ret);
            assert ret.task == task;
        }
        if (currentContext != null) {
            ret.currentContext = currentContext.copy();
        }
        return ret;
    }

    private synchronized Flow frameCopy() {
        checkSteady();
        Flow ret = new Flow(manager, null);
        ret.state = state == TEMP_SUSP ? SUSPENDED : state;
        ret.suspendedFrame = suspendedFrame.shallowCopy(ret);
        ret.result = result;
        if (task != null) {
            task.add(ret);
            assert ret.task == task;
        }
        if (currentContext != null) {
            ret.currentContext = currentContext.copy();
        }
        return ret;
    }

    private void checkSteady() {
        switch (state) {
            case SUSPENDED:
            case TEMP_SUSP:
                assert suspendedFrame != null;
                break;
            case ENDED:
                assert suspendedFrame == null;
                break;
            default:
                throw new IllegalStateException("Cannot copy flow if state is " + state + ".");
        }
        assert currentFrame == null;
    }

    public Flow streamedCopy() {
        try {
            PublicByteArrayOutputStream baos = new PublicByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(this);
            oos.close();
            PublicByteArrayInputStream bais = new PublicByteArrayInputStream(baos.getBuffer(), 0, baos.size());
            ObjectInputStream ois = new ObjectInputStream(bais);
            Object ret = ois.readObject();
            ois.close();
            return (Flow) ret;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized Object join() throws InterruptedException {
        while (state != ENDED) {
            wait();
        }
        return result;
    }

    public synchronized FlowSignal waitSuspended() throws InterruptedException {
        while (state != SUSPENDED && state != PASSIVE) {
            wait();
            continue;
        }
        return (FlowSignal) result;
    }

    public synchronized Object waitNotRunning() throws InterruptedException {
        while (state != SUSPENDED && state != PASSIVE && state != ENDED) {
            wait();
            continue;
        }
        return result;
    }

    /**
     * The current flow result. The actual value depends on the flow state.
     * <ul>
     * <li>If the flow has ended normally, the result is the value returned by
     * the <a href="#flowcreator">flow-creator</a>. In the case where
     * flow-creator is a <code>void</code> method, the result will be
     * <code>null</code>.</li>
     * <li>If the flow has ended by an exception, the result is the thrown
     * exception.</li>
     * <li>If the flow is suspended or passive, the result is the sent
     * {@linkplain FlowSignal signal}.</li>
     * <li>If the flow is active, the result is <code>null</code>.</li>
     * </ul>
     * 
     * @see #getState()
     */
    public synchronized Object getResult() {
        try {
            while (state == SUSPENDING) {
                wait();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (state == ACTIVE) {
            return null;
        }
        return result;
    }

    @FlowMethod
    private synchronized Object doWait(Object key) {
        checkNotInterrupted();
        return taskManager().wait(key);
    }

    @FlowMethod
    private synchronized Object doWaitMany(Object key) {
        checkNotInterrupted();
        return taskManager().waitMany(key);
    }

    private synchronized void doNotifyAll(Object key, Object message) {
        taskManager().notify(key, message);
    }

    @FlowMethod
    private synchronized void doSend(Object address, Object message) {
        checkNotInterrupted();
        taskManager().send(address, message);
    }

    @FlowMethod
    private synchronized Object doReceive(Object address) {
        checkNotInterrupted();
        return taskManager().receive(address);
    }

    @FlowMethod
    private synchronized Object doReceiveMany(Object address) {
        checkNotInterrupted();
        return taskManager().receiveMany(address);
    }

    @FlowMethod
    private synchronized Object doCall(Object address, Object message) {
        checkNotInterrupted();
        TaskManager man = taskManager();
        TwoWayRequest req = new TwoWayRequest(man, message);
        man.send(address, req);
        return man.receive(req);
    }

    @FlowMethod
    private synchronized Connection doAccept(Object matcher) {
        checkNotInterrupted();
        return taskManager().accept(matcher);
    }

    @FlowMethod
    private synchronized Connection doAcceptMany(Object matcher) {
        checkNotInterrupted();
        return taskManager().acceptMany(matcher);
    }

    @FlowMethod
    private synchronized Connection doConnect(Object matcher) {
        checkNotInterrupted();
        return taskManager().connect(matcher);
    }

    @FlowMethod
    private synchronized Connection doConnectMany(Object matcher) {
        checkNotInterrupted();
        return taskManager().connectMany(matcher);
    }

    private void checkNotInterrupted() {
        if (task == null) {
            throw new IllegalStateException("Current flow is not on a task.");
        }
        task.checkNotInterrupted();
    }

    private TaskManager taskManager() {
        if (task == null) {
            throw new IllegalStateException("Current flow is not on a task.");
        }
        return task.manager;
    }

    synchronized FlowData fetchState(long id) {
        assert task != null;
        assert state == SUSPENDED;
        FlowData ret = new FlowData(suspendedFrame, id);
        suspendedFrame = null;
        state = PASSIVE;
        notifyAll();
        return ret;
    }

    synchronized void restoreState(FlowData flowState) {
        assert task != null;
        assert state == PASSIVE;
        suspendedFrame = flowState.suspendedFrame;
        state = SUSPENDED;
        notifyAll();
    }

    FlowContext getCheckpointContext() {
        FlowContext ret;
        switch (state) {
            case INTERRUPTED:
                throw new FlowInterruptedException();
            case ACTIVE:
                ret = currentContext;
                currentContext = null;
                return ret;
            default:
                throw new AssertionError();
        }
    }

    void setCheckpointContext(FlowContext context) {
        assert state == SUSPENDED;
        currentContext = context;
    }

    synchronized Object restore() {
        assert state == ACTIVE;
        //log("Restored from signal.");
        // assert signal.flow == cur : signal.flow;
        Object ret = result;
        result = null;
        if (ret == INTERRUPT_SIGNAL) {
            state = INTERRUPTED;
            throw new FlowInterruptedException();
        }
        if (ret instanceof ExceptionEnvelope) {
            Throwable exception = ((ExceptionEnvelope) ret).exception;
            throw new ResumeException(exception);
        }
        return ret;
    }

    synchronized void finish() {
        // log("finishing...");
        assert current.get() == this;
        current.set(previous);
        previous = null;
        switch (state) {
            case ACTIVE:
            case INTERRUPTED:
                assert suspendedFrame == null;
                Fork fork = currentFork;
                while (fork != null) {
                    if (fork.number > 0) {
                        assert fork.previous == null;
                        fork.finished();
                        break;
                    }
                    fork = fork.previous;
                }
                Task thisTask = task;
                if (thisTask != null) {
                    thisTask.remove(this);
                    // We still reference the task, for information issues and to
                    // go back to task if this finished flow is resumed.
                    task = thisTask;
                }
                state = ENDED;
                currentFrame = null;
                notifyAll();
                // log("normally");
                return;
            case SUSPENDING:
                assert suspendedFrame != null;
                FlowSignal signal = (FlowSignal) result;
                currentFrame = null;
                state = SUSPENDED;
                if (task != null) {
                    task.notifySuspend(this);
                }
                notifyAll();
                // log("throwing signal");
                throw signal;
            default:
                throw new AssertionError("state shouldn't be " + state);
        }
    }

    MethodFrame newFrame(Object owner, String name, String desc) {
        assert state == ACTIVE || state == INTERRUPTED : state;
        if (currentFrame == null) {
            if (suspendedFrame != null) {
                suspendedFrame.checkMatch(owner, name, desc);
                currentFrame = suspendedFrame;
                suspendedFrame = null;
            } else {
                currentFrame = new MethodFrame(this, owner, name, desc);
            }
        } else {
            assert suspendedFrame == null;
            currentFrame = currentFrame.newFrame(owner, name, desc);
        }
        return currentFrame;
    }

    void setCurrentFrame(MethodFrame frame) {
        currentFrame = frame;
    }

    Fork currentFork() {
        return currentFork;
    }

    void setCurrentFork(Fork fork) {
        currentFork = fork;
    }

    synchronized void setSuspendedFrame(MethodFrame frame) {
        try {
            while (state == SUSPENDING) {
                wait();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        switch (state) {
            case ENDED:
                suspendedFrame = frame;
                state = SUSPENDED;
                if (task != null) {
                    Task thisTask = task;
                    task = null;
                    thisTask.add(this, true);
                }
                break;
            case SUSPENDED:
                suspendedFrame = frame;
                break;
            default:
                throw new IllegalStateException("Cannot resume if flow is " + state + '.');
        }
        notifyAll();
    }

    private synchronized void suspendInPlace() {
        if (state == INTERRUPTED) {
            throw new FlowInterruptedException();
        }
        assert state == ACTIVE;
        assert currentFrame != null;
        assert suspendedFrame == null;
        state = TEMP_SUSP;
        suspendedFrame = currentFrame;
        currentFrame = null;
    }

    private synchronized void restoreInPlace() {
        assert state == TEMP_SUSP;
        assert currentFrame == null;
        assert suspendedFrame != null;
        state = ACTIVE;
        currentFrame = suspendedFrame;
        suspendedFrame = null;
    }

    private FlowContext doEnterContext() {
        currentContext = new FlowContext(currentContext);
        return currentContext;
    }

    private void doLeaveContext(FlowContext context) {
        FlowContext thisContext = currentContext;
        while (thisContext != context && thisContext != null) {
            thisContext = thisContext.parent;
        }
        if (thisContext == null) {
            throw new IllegalArgumentException("Illegal context: " + context);
        }
        currentContext = thisContext.parent;
    }

    private Object doGetProperty(String propId) {
        if (propId == null) {
            throw new NullPointerException();
        }
        return currentContext == null ? null : currentContext.getProperty(propId);
    }

    private Object doSetProperty(String propId, Object value) {
        if (propId == null) {
            throw new NullPointerException();
        }
        if (currentContext == null) {
            currentContext = new FlowContext();
        }
        return currentContext.setProperty(propId, value);
    }

    private FlowContext doReadContext() {
        return currentContext == null ? new FlowContext() : currentContext.copy();
    }

    private void doWriteContext(FlowContext context) {
        if (currentContext == null) {
            currentContext = context.copy();
        } else {
            currentContext.writeProperties(context);
        }
    }

    private void doReadProperties(Map<String, Object> dest) {
        if (currentContext != null) {
            currentContext.readProperties(dest);
        }
    }

    private void doWriteProperties(Map<String, Object> properties) {
        if (currentContext == null) {
            currentContext = new FlowContext();
        }
        currentContext.writeProperties(properties);
    }

    private void restoreFrame() {
        MethodFrame frame = suspendedFrame;
        assert frame.state == FrameState.INVOKING;
        assert frame.resumePoint > 0;
        MethodFrame next = null;
        for (;;) {
            frame.flow = this;
            frame.state = FrameState.RESTORING;
            frame.next = next;
            if (frame.prior == null) {
                break;
            }
            next = frame;
            frame = frame.prior;
            assert frame.state == FrameState.INVOKING;
            assert frame.resumePoint > 0;
        }
        suspendedFrame = frame;
        currentFrame = null;
    }

    private boolean isWorking() {
        return currentFrame != null && currentFrame.isActive();
    }

    private Object getRootValues(Method m, Object[] argValues) {
        Class<?>[] argClasses = m.getParameterTypes();
        int pv = 0;
        int ov = 0;
        for (int i = 0; i < argClasses.length; ++i) {
            Class<?> argClass = argClasses[i];
            if (argClass == boolean.class) {
                argValues[i] = suspendedFrame.getBoolean(pv++);
            } else if (argClass == char.class) {
                argValues[i] = suspendedFrame.getChar(pv++);
            } else if (argClass == byte.class) {
                argValues[i] = suspendedFrame.getByte(pv++);
            } else if (argClass == short.class) {
                argValues[i] = suspendedFrame.getShort(pv++);
            } else if (argClass == int.class) {
                argValues[i] = suspendedFrame.getInt(pv++);
            } else if (argClass == long.class) {
                argValues[i] = suspendedFrame.getLong(pv);
                pv += 2;
            } else if (argClass == float.class) {
                argValues[i] = suspendedFrame.getFloat(pv++);
            } else if (argClass == double.class) {
                argValues[i] = suspendedFrame.getDouble(pv);
                pv += 2;
            } else {
                argValues[i] = suspendedFrame.getObject(ov++);
            }
        }
        Object owner;
        if (Modifier.isStatic(m.getModifiers())) {
            owner = null;
        } else {
            owner = suspendedFrame.target;
        }
        return owner;
    }

    private Object doGetLocal(FlowLocal<?> local) {
        WeakHashMap<FlowLocal<?>, Object> map = getLocals();
        Object ret = map.get(local);
        if (ret != null) {
            return ret;
        }
        if (!map.containsKey(local)) {
            ret = local.initialValue();
            map.put(local, ret);
        }
        return ret;
    }

    private Object doSetLocal(FlowLocal<?> local, Object value) {
        return getLocals().put(local, value);
    }

    private Object doRemoveLocal(FlowLocal<?> local) {
        if (locals != null) {
            return null;
        }
        return locals.remove(local);
    }

    private WeakHashMap<FlowLocal<?>, Object> getLocals() {
        if (locals == null) {
            locals = new WeakHashMap<FlowLocal<?>, Object>();
        }
        return locals;
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

        public void respond(Object response) {
            throw new IllegalStateException("This request does not need a response.");
        }

        public void respondThrowing(Throwable exception) {
            throw new IllegalStateException("This request does not need a response.");
        }
    }

    private static class TwoWayRequest implements IRequest, Serializable {

        private static final long serialVersionUID = 1L;
        private final TaskManager manager;
        private final Object request;
        private boolean responseSent;

        TwoWayRequest(TaskManager manager, Object request) {
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
        public synchronized void respond(Object response) {
            if (responseSent) {
                throw new IllegalStateException("The response was already sent.");
            }
            responseSent = true;
            manager.send(this, response);
        }

        @FlowMethod
        public synchronized void respondThrowing(Throwable exception) {
            if (responseSent) {
                throw new IllegalStateException("The response was already sent.");
            }
            responseSent = true;
            manager.sendThrowing(this, exception);
        }
    }

    private static final class ExceptionEnvelope {

        Throwable exception;

        public ExceptionEnvelope(Throwable exception) {
            this.exception = exception;
        }

    }

    // TODO: Add wrappers for java.lang.Thread??? Consider static and instance methods.

}
