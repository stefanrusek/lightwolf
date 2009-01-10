package org.lightwolf;

import java.io.Serializable;

public final class FlowData implements Serializable {

    private static final long serialVersionUID = 1L;
    final MethodFrame suspendedFrame;
    final long id;

    FlowData(MethodFrame suspendedFrame, long id) {
        this.suspendedFrame = suspendedFrame;
        this.id = id;
    }

}
