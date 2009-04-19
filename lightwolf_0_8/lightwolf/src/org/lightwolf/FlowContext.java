package org.lightwolf;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public final class FlowContext implements Serializable {

    private static final long serialVersionUID = 1L;

    final FlowContext parent;
    private FlowContextData data;

    public FlowContext() {
        this(null, new FlowContextData());
    }

    public FlowContext(FlowContext parent) {
        this(parent, new FlowContextData());
    }

    public FlowContext(FlowContextData data) {
        this(null, data);
    }

    public FlowContext(FlowContext parent, FlowContextData data) {
        this.parent = parent;
        synchronized(data) {
            this.data = data;
            data.shareCount++;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        synchronized(data) {
            data.shareCount--;
        }
    }

    public void readProperties(Map<String, Object> dest) {
        // Put on dest all self entries.
        for (Map.Entry<String, Object> me : data.entrySet()) {
            String key = me.getKey();
            Object value = me.getValue();
            // If value == null, the property is unset (but set on some ancestor). Action is don't put.
            // If dest already contains key, we respect the contract and don't put.
            if (value != null && !dest.containsKey(key)) {
                dest.put(key, value);
            }
        }
        // Put on dest all ancestor entries.
        FlowContext ancestor = parent;
        while (ancestor != null) {
            for (Map.Entry<String, Object> me : ancestor.data.entrySet()) {
                String key = me.getKey();
                Object value = me.getValue();
                // If value == null, the property is unset (but set on other ancestor). Action is don't put.
                // If dest already contains key, we respect the contract and don't put.
                // If value != null but getProperty(key) == null, some descendant defines as null. Action is don't put.  
                if (value != null && !dest.containsKey(value) && getProperty(key) == value) {
                    dest.put(key, value);
                }
            }
            ancestor = ancestor.parent;
        }
    }

    public void writeProperties(Map<String, Object> source) {
        // First we read our own properties.
        TreeMap<String, Object> tmp = new TreeMap<String, Object>();
        readProperties(tmp);
        // Now, for each entry in the source map...
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object myValue = tmp.remove(key); // Get my value and remove from tmp.
            if (myValue != value) {
                // source value differs from my value, so we must set.
                setProperty(key, value);
            }
        }
        // Now tmp contains only properties that are not on source. We must remove them all.
        for (Map.Entry<String, Object> entry : tmp.entrySet()) {
            setProperty(entry.getKey(), null);
        }
    }

    public void writeProperties(FlowContext source) {
        // Naive for CPU, but good for memory.
        HashMap<String, Object> tempMap = new HashMap<String, Object>();
        source.readProperties(tempMap);
        writeProperties(tempMap);
    }

    public Object getProperty(String propId) {
        if (propId == null) {
            throw new NullPointerException();
        }
        Object ret = data.get(propId);
        if (ret != null || parent == null || data.containsKey(propId)) {
            return ret;
        }
        return parent.getProperty(propId);
    }

    public Object setProperty(String propId, Object value) {
        if (propId == null) {
            throw new NullPointerException();
        }
        Object ret = getProperty(propId);
        if (ret == value) {
            return ret;
        }
        synchronized(data) {
            if (data.shareCount > 1) {
                --data.shareCount;
                data = (FlowContextData) data.clone();
                data.shareCount = 1;
            }
        }
        assert data.shareCount == 1;
        if (value == null) {
            if (parent == null || parent.getProperty(propId) == null) {
                return data.remove(propId.intern());
            }
        }
        return data.put(propId.intern(), value);
    }

    public FlowContext copy() {
        FlowContext newParent = null;
        if (parent != null) {
            newParent = parent.copy();
        }
        return new FlowContext(newParent, data);
    }

}
