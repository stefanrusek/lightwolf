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
