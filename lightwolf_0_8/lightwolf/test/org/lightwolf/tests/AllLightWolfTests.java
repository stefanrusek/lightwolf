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

import junit.framework.JUnit4TestAdapter;
import junit.framework.Test;
import junit.framework.TestSuite;

public class AllLightWolfTests {

    public static Test suite() {
        TestSuite suite = new TestSuite("Test for soft.test");
        //$JUnit-BEGIN$
        suite.addTest(new JUnit4TestAdapter(TestBasics.class));
        suite.addTest(new JUnit4TestAdapter(TestTasks.class));
        suite.addTest(new JUnit4TestAdapter(TestInterrupt.class));
        suite.addTest(new JUnit4TestAdapter(TestParallel.class));
        suite.addTest(new JUnit4TestAdapter(TestReturnAndContinue.class));
        suite.addTest(new JUnit4TestAdapter(TestSocketIO.class));
        suite.addTest(new JUnit4TestAdapter(TestSerialization.class));
        suite.addTest(new JUnit4TestAdapter(TestLocalFork.class));
        suite.addTest(new JUnit4TestAdapter(TestFlowLock.class));
        suite.addTest(new JUnit4TestAdapter(TestProcessFork.class));
        suite.addTest(new JUnit4TestAdapter(TestCurrentProcessFork.class));
        suite.addTest(new JUnit4TestAdapter(TestEventPicker.class));
        //$JUnit-END$
        return suite;
    }

}
