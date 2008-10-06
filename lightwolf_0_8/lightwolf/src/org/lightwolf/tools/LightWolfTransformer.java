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
package org.lightwolf.tools;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.WeakHashMap;

class LightWolfTransformer implements ClassFileTransformer {

    private static WeakHashMap<ClassLoader, LightWolfEnhancer> enhancers = new WeakHashMap<ClassLoader, LightWolfEnhancer>();

    private static LightWolfEnhancer getEnhancer(ClassLoader loader) {
        synchronized(enhancers) {
            LightWolfEnhancer ret = enhancers.get(loader);
            if (ret == null) {
                ret = new LightWolfEnhancer(loader);
                enhancers.put(loader, ret);
            }
            return ret;
        }
    }

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        LightWolfLog.println(className);
        if (true) {
            return null;
        }
        try {
            LightWolfEnhancer enhancer = getEnhancer(loader);
            PublicByteArrayOutputStream pbaos = new PublicByteArrayOutputStream();
            pbaos.write(classfileBuffer);
            if (enhancer.transform(pbaos)) {
                return pbaos.toByteArray();
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

}
