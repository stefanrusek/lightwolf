package org.lightwolf.tests;

import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;

public class Test {

    @FlowMethod
    public static void main(String[] args) throws Throwable {
        System.out.println("Before fork.");
        int b = Flow.fork(4);
        System.out.printf("We are in the branch %d.\n", b);
        Flow.merge();
        System.out.println("After fork.");
    }
}
// feel free to
