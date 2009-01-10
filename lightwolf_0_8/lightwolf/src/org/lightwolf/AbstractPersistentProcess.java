package org.lightwolf;

import java.io.IOException;
import java.util.Random;

public abstract class AbstractPersistentProcess extends Process {

    public static final int ACTIVE = 1;
    public static final int PASSIVE = 2;
    public static final int CORRUPT = 3;

    private static String[] stateNames = new String[] { "ACTIVE", "PASSIVE", "CORRUPT" };

    private static String stateName(int state) {
        if (state >= 1 || state <= 2) {
            return stateNames[state - 1];
        }
        return "(unknown state: " + state + ")";
    }

    private int state;
    private long passivationTime;

    public AbstractPersistentProcess() {
        state = ACTIVE;
    }

    public AbstractPersistentProcess(ProcessManager manager) {
        super(manager);
        state = ACTIVE;
    }

    public final int getState() {
        return state;
    }

    public final synchronized boolean passivate() throws IOException {
        if (state == PASSIVE) {
            return true;
        }
        if (state != ACTIVE) {
            throw new IllegalStateException("Cannot passivate while process is " + stateName(state));
        }
        if (activeFlows() != 0) {
            return false;
        }
        Flow[] flows = getFlows();
        FlowData[] data = new FlowData[flows.length];
        int lastSuccess = -1;
        try {
            passivationTime = System.currentTimeMillis();
            Random r = new Random(passivationTime);
            for (int i = 0; i < flows.length; ++i) {
                data[i] = flows[i].fetchState(r.nextLong());
                lastSuccess = i;
            }
            storeData(data);
            state = PASSIVE;
            lastSuccess = -1;
        } finally {
            for (int i = 0; i <= lastSuccess; ++i) {
                flows[i].restoreState(data[i]);
            }
        }
        return true;
    }

    public final synchronized void activate() throws IOException, ClassNotFoundException {
        if (state == ACTIVE) {
            return;
        }
        if (state != PASSIVE) {
            throw new IllegalStateException("Cannot activate while process is " + stateName(state));
        }
        Object rawData = loadData();
        if (!(rawData instanceof FlowData[])) {
            throw new IllegalStateException("Bad data: object should be instance of FlowData[].");
        }
        Flow[] flows = getFlows();
        FlowData[] data = (FlowData[]) rawData;
        if (data.length != flows.length) {
            throw new IllegalStateException("Bad data: number of data entries should be " + flows.length + ", not " + data.length + ".");
        }
        Random r = new Random(passivationTime);
        for (int i = 0; i < flows.length; ++i) {
            if (data[i] == null) {
                throw new IllegalStateException("Bad data: flow data entry is null.");
            }
            if (data[i].id != r.nextLong()) {
                throw new IllegalStateException("Bad data: invalid flow data entry id.");
            }
        }
        int lastRestore = -1;
        try {
            for (int i = 0; i < flows.length; ++i) {
                flows[i].restoreState(data[i]);
                lastRestore = i;
            }
            state = ACTIVE;
        } finally {
            if (state != ACTIVE && lastRestore != -1) {
                state = CORRUPT;
            }
        }
    }

    @Override
    final void checkAddRemove() {
        if (state != ACTIVE) {
            throw new IllegalStateException("Cannot add/remove flows while process is " + stateName(state));
        }
    }

    @Override
    synchronized final void notifySuspend(Flow flow) {
        assert state == ACTIVE;
        super.notifySuspend(flow);
    }

    @Override
    synchronized final void notifyResume(Flow flow) {
        try {
            activate();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to resume flow due to I/O error.", e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Unable to resume flow because a class it uses wasn't found.", e);
        }
        super.notifyResume(flow);
    }

    protected abstract void storeData(Object data) throws IOException;

    protected abstract Object loadData() throws IOException, ClassNotFoundException;

}
