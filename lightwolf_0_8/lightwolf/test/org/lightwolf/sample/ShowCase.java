package org.lightwolf.sample;

import java.util.Random;

import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.synchronization.ParallelArray;
import org.lightwolf.synchronization.ParallelIterator;

public class ShowCase {

    public static void main(String[] args) throws InterruptedException {
        mainForkMerge(args);
        mainReturnAndContinue(args);
        mainParallel(args);
    }

    @FlowMethod
    public static void mainForkMerge(String[] args) throws InterruptedException {

        Random r = new Random(0);
        System.out.println("Single threaded.");
        int branch = Flow.fork(4); // Create 4 threads.
        try {
            System.out.printf("Starting branch %d.\n", branch);
            Thread.sleep(r.nextInt(100)); // Simulates some processing.
            System.out.printf("Done with branch %d.\n", branch);
        } finally {
            Flow.merge();
        }
        System.out.println("Single threaded, again.");

    }

    public static void mainReturnAndContinue(String[] args) throws InterruptedException {

        System.out.println("Calling doSomething().");
        double result = doSomething();
        System.out.printf("doSomething() returned %f.\n", result);
        for (int i = 1; i <= 4; ++i) {
            System.out.printf("main() counter: %d.\n", i);
            Thread.sleep(100);
        }

    }

    @FlowMethod
    private static double doSomething() throws InterruptedException {
        // Return 1.25 and continue processing.
        Flow.returnAndContinue(1.25);
        for (int i = 1; i <= 4; ++i) {
            System.out.printf("doSomething() counter: %d.\n", i);
            Thread.sleep(100);
        }
        return 0; // Return no nobody.
    }

    @FlowMethod
    public static void mainParallel(String[] args) throws InterruptedException {
        // Build an array with mock elements to be processed.
        Random random = new Random(0);
        Element[] data = new Element[8];
        for (int i = 0; i < data.length; ++i) {
            data[i] = new Element(i + 1, random.nextInt(400));
        }
        // Process elements in parallel.
        ParallelArray<Element> array = new ParallelArray<Element>(data);
        for (ParallelIterator<Element> iterator = array.iterator(); iterator.hasNext();) {
            Element elem = iterator.next();
            System.out.printf("Starting %d.\n", elem.number);
            Thread.sleep(elem.cost); // Simulates the of processing this element.
            System.out.printf("Done %d.\n", elem.number);
        }
    }

    static class Element {

        int number;
        int cost;

        Element(int number, int size) {
            this.number = number;
            this.cost = size;
        }

    }

}
