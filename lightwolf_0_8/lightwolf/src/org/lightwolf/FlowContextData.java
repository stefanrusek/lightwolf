/**
 * 
 */
package org.lightwolf;

import java.util.HashMap;

final class FlowContextData extends HashMap<String, Object> {

    private static final long serialVersionUID = 1L;
    int shareCount; // Initialized with zero.

    @Override
    public Object clone() {
        FlowContextData ret = (FlowContextData) super.clone();
        ret.shareCount = 0;
        return ret;
    }

}
