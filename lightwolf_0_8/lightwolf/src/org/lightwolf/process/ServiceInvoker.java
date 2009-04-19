package org.lightwolf.process;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;

import org.lightwolf.FlowMethod;
import org.lightwolf.Task;

public class ServiceInvoker implements InvocationHandler {

    public static <T> T getInstance(Class<T>... clazz) {
        return (T) Proxy.newProxyInstance(ServiceInvoker.class.getClassLoader(), clazz, new ServiceInvoker());
    }

    private String prefix;
    private HashMap<Method, String> addressCache;

    public ServiceInvoker() {
        prefix = "";
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        if (prefix == null) {
            throw new NullPointerException();
        }
        this.prefix = prefix;
    }

    @FlowMethod
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        return Task.call(getAddress(method), args);
    }

    private synchronized String getAddress(Method method) {
        if (addressCache == null) {
            addressCache = new HashMap<Method, String>();
        }
        String ret = addressCache.get(method);
        if (ret == null) {
            ret = prefix + Helper.getSignature(method);
            addressCache.put(method, ret);
        }
        return ret;
    }

}
