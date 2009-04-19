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
            Object message = Task.receiveMany(addressPrefix + Helper.getSignature(method));
            if (message instanceof IRequest) {
                IRequest request = (IRequest) message;
                Object[] args = (Object[]) request.request();
                Object ret = Flow.invoke(method, this, args);
                request.respond(ret);
            } else {
                Flow.invoke(method, this, message);
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

}
