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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.Callable;

import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.IRequest;
import org.lightwolf.Task;
import org.lightwolf.TaskManager;

public class ServiceProvider {

    private final Task task;
    private ServiceState state;
    private String addressPrefix;
    private final HashMap<String, Method> methods;

    public ServiceProvider() {
        this(TaskManager.getDefault());
    }

    public ServiceProvider(TaskManager taskManager) {
        task = new Task(taskManager);
        state = ServiceState.CREATED;
        addressPrefix = "";
        methods = new HashMap<String, Method>();
        for (Method method : getClass().getMethods()) {
            if (!method.isAnnotationPresent(Service.class)) {
                continue;
            }
            String name = method.getName();
            if (methods.containsKey(name)) {
                throw new RuntimeException("Duplicate service-method name: " + name);
            }
            methods.put(name, method);
        }
    }

    public String getAddressPrefix() {
        return addressPrefix;
    }

    public synchronized void setAddressPrefix(String addressPrefix) {
        this.addressPrefix = addressPrefix;
    }

    @FlowMethod
    public synchronized void start() {
        if (state != ServiceState.CREATED) {
            throw new IllegalStateException("Cannot start service provider when it is " + state + ".");
        }
        Flow.joinTask(task);
        for (Method method : methods.values()) {
            startService(method);
        }
        state = ServiceState.STARTED;
    }

    public synchronized void stop() {
        if (state == ServiceState.STOPPED) {
            return;
        }
        if (state != ServiceState.STARTED) {
            throw new IllegalStateException("Cannot stop service provider when it is " + state + ".");
        }
        state = ServiceState.STOPPED;
        task.interrupt();
    }

    public Flow submit(String methodName, final Object... args) {
        final Method method = methods.get(methodName);
        if (method == null) {
            throw new RuntimeException("Could not find service-method with name: " + methodName);
        }
        Callable<?> callable = new Callable<?>() {

            @FlowMethod
            public Object call() throws Exception {
                try {
                    Flow.joinTask(new Task());
                    return Flow.invoke(method, this, args);
                } catch (Throwable e) {
                    return e;
                }
            }

        };
        return Flow.submit(callable);
    }

    @FlowMethod
    private void startService(Method method) {
        Flow.returnAndContinue();
        try {
            String address = addressPrefix + Helper.getSignature(method);
            // Flow.log("Starting " + address);
            Object message = Flow.receiveMany(address);
            if (message instanceof IRequest) {
                // Flow.log("Received request on " + address);
                IRequest request = (IRequest) message;
                Object[] args = (Object[]) request.request();
                Object ret = Flow.invoke(method, this, args);
                request.respond(ret);
            } else {
                // Flow.log("Received message on " + address);
                Flow.invoke(method, this, message);
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

}
