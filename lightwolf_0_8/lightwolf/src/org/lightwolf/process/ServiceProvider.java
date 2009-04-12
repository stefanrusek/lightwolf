package org.lightwolf.process;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.IRequest;
import org.lightwolf.Task;
import org.lightwolf.TaskManager;

public class ServiceProvider {

    private final Task task;
    private boolean started;
    private String addressPrefix;

    public ServiceProvider() {
        this(TaskManager.getDefault());
    }

    public ServiceProvider(TaskManager taskManager) {
        task = new Task(taskManager);
    }

    public String getAddressPrefix() {
        return addressPrefix;
    }

    public synchronized void setAddressPrefix(String addressPrefix) {
        checkNotStarted();
        this.addressPrefix = addressPrefix;
    }

    @FlowMethod
    public synchronized void start() {
        checkNotStarted();
        Flow.joinTask(task);
        Method[] methods = getClass().getMethods();
        for (Method method : methods) {
            if (method.isAnnotationPresent(Service.class)) {
                startService(method);
            }
        }
        started = true;
    }

    @FlowMethod
    private void startService(Method method) {
        Flow.returnAndContinue();
        try {
            Object message = Task.receiveMany(addressPrefix + method.getName());
            if (message instanceof IRequest) {
                IRequest request = (IRequest) message;
                Object ret;
                ret = Flow.invoke(method, this, request.request());
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

    public synchronized void stop() {
        if (!started) {
            throw new IllegalStateException("Service " + this + " was not started.");
        }
        started = false;
        task.interrupt();
    }

    private void checkNotStarted() {
        if (started) {
            throw new IllegalStateException("Service " + this + " is started.");
        }
    }

}
