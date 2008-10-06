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

import java.util.HashMap;
import java.util.Map;

public class TimeCounter {

    private static HashMap<String, Sum> sums = new HashMap<String, Sum>();

    public static long count(long start, String name) {
        return count(start, name, null);
    }

    public static long count(long start, String name, String instance) {
        long duration = System.currentTimeMillis() - start;
        Sum sum = sums.get(name);
        if (sum == null) {
            sum = new Sum(duration, instance);
            sums.put(name, sum);
        } else {
            sum.add(duration, instance);
        }
        return System.currentTimeMillis();
    }

    public static void dump() {
        for (Map.Entry<String, Sum> entry : sums.entrySet()) {
            Sum s = entry.getValue();
            LightWolfLog.printf("%s: Total: %d, Count: %d, Avg: %f, Max: %d (%s), Min: %d.\n", entry.getKey(), s.getTotal(), s.getCount(), s.getAvg(), s.getMax(), s.getMaxInst(), s.getMin());
        }

    }

    static class Sum {

        private long sum;
        private long min;
        private long max;
        private String maxInst;
        private int count;

        public Sum(long first, String instance) {
            sum = first;
            min = first;
            max = first;
            maxInst = instance;
            count = 1;
        }

        public void add(long duration, String instance) {
            sum += duration;
            if (duration > max) {
                max = duration;
                maxInst = instance;
            }
            if (duration < min) {
                min = duration;
            }
            ++count;
        }

        long getTotal() {
            return sum;
        }

        int getCount() {
            return count;
        }

        double getAvg() {
            return (double) sum / count;
        }

        long getMax() {
            return max;
        }

        String getMaxInst() {
            return maxInst;
        }

        long getMin() {
            return min;
        }

    }

}
