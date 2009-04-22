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

import java.lang.reflect.Method;

public class Helper {

    public static String getSignature(Method m) {
        StringBuilder tmp = new StringBuilder();
        tmp.append(m.getName());
        tmp.append('(');
        for (Class<?> clazz : m.getParameterTypes()) {
            append(tmp, clazz);
        }
        tmp.append(')');
        return tmp.toString();
    }

    private static void append(StringBuilder tmp, Class<?> clazz) {
        if (clazz == boolean.class) {
            tmp.append('Z');
        } else if (clazz == byte.class) {
            tmp.append('B');
        } else if (clazz == short.class) {
            tmp.append('S');
        } else if (clazz == int.class) {
            tmp.append('I');
        } else if (clazz == long.class) {
            tmp.append('J');
        } else if (clazz == float.class) {
            tmp.append('F');
        } else if (clazz == double.class) {
            tmp.append('D');
        } else if (clazz == char.class) {
            tmp.append('C');
        } else if (clazz.isArray()) {
            tmp.append('[');
            append(tmp, clazz.getComponentType());
        } else {
            tmp.append('L');
            tmp.append(clazz.getName());
            tmp.append(';');
        }
    }
}
