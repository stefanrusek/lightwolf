package org.lightwolf.tests;

import org.lightwolf.FlowMethod;

public class TransformExample {

    @FlowMethod
    public static void m1() {
        int x = 1;
        Object y = Thread.currentThread();
        x += flow1(x);
        String z = "1234";
        nonFlow(y, z);
        double w = 1234.5;
        z = flow2(z, w);
        nonFlow(z, y);
    }

    @FlowMethod
    public int m2() {
        int x = 1;
        TransformExample y = new TransformExample();
        x += y.flow2(x);
        String z = "1234";
        nonFlow(y, z);
        double w = 1234.5;
        z = flow2(z, w);
        nonFlow(z, y);
        return x + 45;
    }

    @FlowMethod
    public void minimal() {
        flow1(0);
    }

    @FlowMethod
    private static int flow1(int x) {
        return 45 + x;
    }

    @FlowMethod
    private int flow2(int x) {
        return 12 + x;
    }

    @FlowMethod
    private static String flow2(String z, double w) {
        return null;
    }

    private static void nonFlow(Object y, Object y2) {
    // TODO Auto-generated method stub

    }

}
