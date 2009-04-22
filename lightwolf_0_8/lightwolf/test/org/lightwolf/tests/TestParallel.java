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
