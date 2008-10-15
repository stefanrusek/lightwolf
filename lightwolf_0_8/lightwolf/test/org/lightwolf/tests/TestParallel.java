package org.lightwolf.tests;

import java.util.ArrayList;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;
import org.lightwolf.FlowMethod;
import org.lightwolf.synchronization.ParallelArray;
import org.lightwolf.synchronization.ParallelIterator;

public class TestParallel {

    @Test
    @FlowMethod
    public void simplest() throws InterruptedException {

        Integer[] data = new Integer[50];
        for (int i = 0; i < data.length; ++i) {
            data[i] = i;
        }
        Random random = new Random(0);
        ArrayList<Integer> out = new ArrayList<Integer>(data.length);
        Thread current = Thread.currentThread();

        ParallelArray<Integer> array = new ParallelArray<Integer>(data);
        for (ParallelIterator<Integer> iterator = array.iterator(); iterator.hasNext();) {
            Thread.sleep(random.nextInt(100));
            int elem = iterator.next();
            synchronized(out) {
                out.add(elem);
            }
        }

        Assert.assertTrue(Thread.currentThread() == current);

        for (int i = 1; i < out.size(); ++i) {
            if (out.get(i - 1) > out.get(i)) {
                return;
            }
        }

        Assert.fail();

    }

}
