package org.lightwolf;

public enum TaskState {
    /**
     * A constant indicating that the task is active. An active task have all
     * its data on memory. It can run flows and allow new flows to
     * {@linkplain Flow#joinTask(Task) join} it.
     * 
     * @see Task#getState()
     * @see Task#activate()
     */
    ACTIVE,

    /**
     * A constant indicating that the task is passive. A passive task have its
     * data on external storage. The actual storage is defined by the
     * {@link Task} subclass. It can't run flows nor allow new flows to
     * {@linkplain Flow#joinTask(Task) join} it.
     * 
     * @see Task#getState()
     * @see Task#passivate()
     */
    PASSIVE,

    /**
     * A constant indicating that the task has been interrupted. An interrupted
     * task contains only interrupted flows and doesn't allow new flows to
     * {@linkplain Flow#joinTask(Task) join} it.
     * 
     * @see Task#getState()
     */
    INTERRUPTED

}
