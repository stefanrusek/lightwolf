package org.lightwolf.tests;

import junit.framework.JUnit4TestAdapter;
import junit.framework.Test;
import junit.framework.TestSuite;

public class AllLightWolfTests {

    public static Test suite() {
        TestSuite suite = new TestSuite("Test for soft.test");
        //$JUnit-BEGIN$
        suite.addTest(new JUnit4TestAdapter(TestBasics.class));
        suite.addTest(new JUnit4TestAdapter(TestProcess.class));
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
