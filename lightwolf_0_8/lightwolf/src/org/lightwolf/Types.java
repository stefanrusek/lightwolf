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

import java.lang.reflect.Array;

class Types {

    public static final char TYPE_CODE_VOID = 'V';
    public static final char TYPE_CODE_BOOLEAN = 'Z';
    public static final char TYPE_CODE_CHAR = 'C';
    public static final char TYPE_CODE_BYTE = 'B';
    public static final char TYPE_CODE_SHORT = 'S';
    public static final char TYPE_CODE_INT = 'I';
    public static final char TYPE_CODE_LONG = 'J';
    public static final char TYPE_CODE_FLOAT = 'F';
    public static final char TYPE_CODE_DOUBLE = 'D';

    static String getName(char typeChar) {
        switch (typeChar) {
            case 'V':
                return "void";
            case 'Z':
                return "boolean";
            case 'C':
                return "char";
            case 'B':
                return "byte";
            case 'S':
                return "short";
            case 'I':
                return "int";
            case 'J':
                return "long";
            case 'F':
                return "float";
            case 'D':
                return "double";
            default:
                throw new AssertionError("Unknown character type: " + typeChar + " (" + (int) typeChar + ").");
        }
    }

    static String getMethodDescription(Class owner, String name, String desc) {
        StringBuilder temp = new StringBuilder();
        Type retType = Type.getReturnType(desc);
        temp.append(retType.getClassName());
        temp.append(' ');
        temp.append(owner.getSimpleName());
        temp.append('.');
        temp.append(name);
        temp.append('(');
        Type[] args = Type.getArgumentTypes(desc);
        for (int i = 0; i < args.length; ++i) {
            if (i > 0) {
                temp.append(',');
            }
            temp.append(args[i].getClassName());
        }
        temp.append(')');
        return temp.toString();
    }

    static Class[] typeToClass(Type[] t) throws ClassNotFoundException {
        Class[] ret = new Class[t.length];
        for (int i = 0; i < ret.length; ++i) {
            ret[i] = typeToClass(t[i]);
        }
        return ret;
    }

    static Class typeToClass(Type type) throws ClassNotFoundException {
        switch (type.getSort()) {
            case Type.VOID:
                return void.class;
            case Type.BOOLEAN: {
                return boolean.class;
            }
            case Type.CHAR: {
                return char.class;
            }
            case Type.BYTE: {
                return byte.class;
            }
            case Type.SHORT: {
                return short.class;
            }
            case Type.INT: {
                return int.class;
            }
            case Type.LONG: {
                return long.class;
            }
            case Type.FLOAT: {
                return float.class;
            }
            case Type.DOUBLE: {
                return double.class;
            }
            case Type.ARRAY: {
                int[] dims = new int[type.getDimensions()];
                Class elemType = typeToClass(type.getElementType());
                return Array.newInstance(elemType, dims).getClass();
            }
            case Type.OBJECT: {
                return Class.forName(type.getClassName());
            }
            default:
                throw new AssertionError("Unknown type: " + type);
        }
    }

}
