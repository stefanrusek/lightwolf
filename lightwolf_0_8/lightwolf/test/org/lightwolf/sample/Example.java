package org.lightwolf.sample;

import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;

public class Example {

    public static void main(String[] args) {
        new Example().methodA();
    }

    @FlowMethod
    void methodA() {
        System.out.println("Before performFork()");
        int i = performFork();
        System.out.printf("performFork(): %d\n", i);
    }

    @FlowMethod
    int performFork() {
        System.out.println("Before fork(2)");
        int i = Flow.fork(2);
        System.out.printf("Fork result: %d\n", i);
        return i;
    }

}
