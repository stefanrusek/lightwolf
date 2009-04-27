package org.lightwolf;

import java.util.IdentityHashMap;
import java.util.Map;

public final class Synchronization {

    private static final Synchronization SINGLETON = new Synchronization();

    public static void lock(Object obj) {
        try {
            SINGLETON.doLock(obj);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void unlock(Object obj) {
        SINGLETON.doUnlock(obj);
    }

    public static void sleep() {
        try {
            SINGLETON.doSleep();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void wakeup(Object obj) {
        SINGLETON.doWakeup(obj);
    }

    private final IdentityHashMap<Object, Thread> owners;
    private final IdentityHashMap<Thread, Object> sleeping;
    private final ThreadLocal<Lock> locks;
    private Lock pool;

    private Synchronization() {
        owners = new IdentityHashMap<Object, Thread>();
        sleeping = new IdentityHashMap<Thread, Object>();
        locks = new ThreadLocal<Lock>();
    }

    private synchronized void doLock(Object obj) throws InterruptedException {
        if (obj == null) {
            throw new NullPointerException();
        }
        Thread me = Thread.currentThread();
        for (;;) {
            Thread t = owners.get(obj);
            if (t == null) {
                push(obj);
                owners.put(obj, me);
                return;
            }
            if (t == me) {
                push(obj);
                return;
            }
            wait();
        }
    }

    private synchronized void doUnlock(Object obj) {
        if (obj == null) {
            throw new NullPointerException();
        }
        Lock lock = locks.get();
        if (lock.object != obj) {
            throw new IllegalArgumentException("Informed object is not the last locked.");
        }

        Lock pooled = lock;
        lock = lock.previous;
        pooled.previous = pool;
        pool = pooled;

        locks.set(lock);
        while (lock != null) {
            if (lock.object == obj) {
                return;
            }
            lock = lock.previous;
        }
        owners.remove(obj);
        notifyAll();
    }

    private synchronized void doSleep() throws InterruptedException {
        Thread me = Thread.currentThread();
        Lock myLocks = locks.get();

        // Release all my locks.
        Lock lock = myLocks;
        do {
            owners.put(lock.object, null);
            lock = lock.previous;
        } while (lock != null);
        // Somebody might be waiting for some lock, so I notify.
        notifyAll();

        // Wait until somebody wakes me up.
        Object wanted = myLocks.object;
        sleeping.put(me, wanted);
        do {
            wait();
        } while (sleeping.get(me) == wanted);
        wanted = sleeping.remove(me);
        assert wanted == null;

        // I'm awaken. Now I need to get all my locks back.
        checkMyLocks: for (;;) {
            lock = myLocks;
            do {
                if (owners.get(lock.object) != null) {
                    // Somebody got my lock. Must wait and try again.
                    wait();
                    continue checkMyLocks;
                }
                lock = lock.previous;
            } while (lock != null);
            // Now I can get all my locks.
            break;
        }

        // Get my locks back.
        lock = myLocks;
        do {
            owners.put(lock.object, me);
            lock = lock.previous;
        } while (lock != null);
    }

    private synchronized void doWakeup(Object obj) {
        for (Map.Entry<Thread, Object> entry : sleeping.entrySet()) {
            Object wanted = entry.getValue();
            if (wanted == obj) {
                entry.setValue(null);
            }
        }
        notifyAll();
    }

    private void push(Object obj) {
        Lock last = locks.get();
        Lock lock = pool;
        if (lock == null) {
            lock = new Lock();
        } else {
            pool = pool.previous;
        }
        lock.previous = last;
        lock.object = obj;
        locks.set(lock);
    }

    private static class Lock {

        Lock previous;
        Object object;

    }

}
