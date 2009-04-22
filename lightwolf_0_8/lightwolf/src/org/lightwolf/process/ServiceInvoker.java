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
