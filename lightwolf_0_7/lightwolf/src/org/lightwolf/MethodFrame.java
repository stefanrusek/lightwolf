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
package org.lightwolf;

import java.io.Serializable;

public final class MethodFrame implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];

    public static final int ACTIVE = 1;
    public static final int INVOKING = 2;
    public static final int RESTORING = 3;
    public static final int DEAD = 4;
    public static final int LEAVING_METHOD = 5;
    public static final int LEAVING_THREAD = 6;

    public static MethodFrame enter(Object owner, String name, String desc) {
        return Flow.enter(owner, name, desc);
    }

    static MethodFrame copy(MethodFrame o, Flow owner) {
        return o == null ? null : o.copy(owner);
    }

    private static Object[] clone(Object[] o) {
        return o == null ? null : o.clone();
    }

    private static int[] clone(int[] o) {
        return o == null ? null : o.clone();
    }

    Flow flow;
    final Object target;
    final String name;
    final String desc;
    int state;
    int resumePoint;
    private int[] vars;
    private int varIndex;
    private Object[] objVars;
    private int objVarIndex;
    private int result;
    private int hiResult;
    private Object objResult;
    MethodFrame prior;
    transient MethodFrame next;

    MethodFrame(Flow flow, Object target, String name, String desc) {
        this.flow = flow;
        this.target = target;
        this.name = name;
        this.desc = desc;
        state = ACTIVE;
    }

    private MethodFrame(MethodFrame prior, Object target, String name, String desc) {
        flow = prior.flow;
        this.target = target;
        this.name = name;
        this.desc = desc;
        this.prior = prior;
        state = ACTIVE;
    }

    private MethodFrame(MethodFrame src, Flow flow, boolean deep) {
        this.flow = flow;
        target = src.target;
        name = src.name;
        desc = src.desc;
        state = src.state;
        resumePoint = src.resumePoint;
        vars = clone(src.vars);
        varIndex = src.varIndex;
        objVars = clone(src.objVars);
        objVarIndex = src.objVarIndex;
        if (deep) {
            prior = copy(src.prior, flow);
        }
    }

    MethodFrame newFrame(Object target, String name, String desc) {
        if (state == INVOKING) {
            return new MethodFrame(this, target, name, desc);
        }
        if (state == RESTORING) {
            assert resumePoint > 0;
            state = INVOKING;
            assert next != null;
            MethodFrame ret = next;
            ret.checkMatch(target, name, desc);
            assert ret.state == RESTORING;
            next = null;
            return ret;
        }
        throw new AssertionError("Invalid state: " + state);
    }

    MethodFrame copy(Flow owner) {
        return new MethodFrame(this, owner, true);
    }

    MethodFrame shallowCopy(Flow owner) {
        return new MethodFrame(this, owner, false);
    }

    public void exit() {
        // Possible states:
        int curState = state;
        state = DEAD;
        assert curState == ACTIVE // The method is returning normally or by exception.
                || curState == LEAVING_METHOD // The method set itself to leave.
                || curState == LEAVING_THREAD // The method set this thread to leave.
        : "State is " + curState;
        MethodFrame prior = this.prior;
        if (prior == null) {
            flow.finish();
            return;
        }
        assert prior.state == INVOKING; // The prior must be invoking.
        assert prior.resumePoint > 0; // The prior's resumePoint must be positive.
        // Before return, we restore the prior's state...
        switch (curState) {
            case LEAVING_THREAD:
                prior.state = LEAVING_THREAD;
                break;
            case ACTIVE:
            case LEAVING_METHOD:
                prior.state = ACTIVE;
                break;
            default:
                throw new AssertionError("State is " + curState);
        }
        prior.resumePoint = 0; // ...and its resume point.
        flow.setCurrentFrame(prior);
        return;
    }

    public void exit(Throwable e) throws Throwable {
        if (state == DEAD) {
            throw e;
        }
        if (state == INVOKING) {
            if (!(e instanceof NullPointerException)) {
                throw e;
            }
            state = ACTIVE;
        } else {
            assert state == ACTIVE;
            assert e != null;
        }
        exit();
    }

    public boolean isLeaving() {
        vars = null;
        objVars = null;
        if (state == LEAVING_THREAD || state == LEAVING_METHOD) {
            exit();
            return true;
        }
        assert state == ACTIVE;
        assert resumePoint == 0;
        return false;
    }

    public void monitorEnter(Object o) {
        System.out.println("-- Ignoring monitor enter! --");
    }

    public void monitorExit(Object o) {
        System.out.println("-- Ignoring monitor exit! --");
    }

    public int getPrimitiveCount() {
        return vars.length;
    }

    public int getObjectCount() {
        return objVars.length;
    }

    public MethodFrame getPrior() {
        return prior;
    }

    public MethodFrame getRoot() {
        MethodFrame ret = this;
        for (;;) {
            MethodFrame prior = ret.getPrior();
            if (prior != null) {
                return ret;
            }
            ret = prior;
        }
    }

    public MethodFrame save(char c) {
        vars[varIndex++] = c;
        return this;
    }

    public MethodFrame save(boolean z) {
        vars[varIndex++] = z ? 1 : 0;
        return this;
    }

    public MethodFrame save(byte b) {
        vars[varIndex++] = b;
        return this;
    }

    public MethodFrame save(short s) {
        vars[varIndex++] = s;
        return this;
    }

    public MethodFrame save(int i) {
        vars[varIndex++] = i;
        return this;
    }

    public MethodFrame save(long l) {
        vars[varIndex++] = (int) (l >> 32);
        vars[varIndex++] = (int) l;
        return this;
    }

    public MethodFrame save(float f) {
        vars[varIndex++] = Float.floatToIntBits(f);
        return this;
    }

    public MethodFrame save(double d) {
        long l = Double.doubleToLongBits(d);
        vars[varIndex++] = (int) (l >> 32);
        vars[varIndex++] = (int) l;
        return this;
    }

    public MethodFrame save(Object o) {
        objVars[objVarIndex++] = o;
        return this;
    }

    public char getChar(int varIndex) {
        return (char) vars[varIndex];
    }

    public boolean getBoolean(int varIndex) {
        return vars[varIndex] == 0 ? false : true;
    }

    public byte getByte(int varIndex) {
        return (byte) vars[varIndex];
    }

    public short getShort(int varIndex) {
        return (short) vars[varIndex];
    }

    public int getInt(int varIndex) {
        return vars[varIndex];
    }

    public long getLong(int varIndex) {
        long ret = vars[varIndex + 1] & 0x0FFFFFFFFL;
        ret |= (long) vars[varIndex] << 32;
        return ret;
    }

    public float getFloat(int varIndex) {
        return Float.intBitsToFloat(vars[varIndex]);
    }

    public double getDouble(int varIndex) {
        long bits = vars[varIndex + 1] & 0x0FFFFFFFFL;
        bits |= (long) vars[varIndex] << 32;
        return Double.longBitsToDouble(bits);
    }

    public Object getObject(int objVarIndex) {
        return objVars[objVarIndex];
    }

    public char restoreChar() {
        checkChar(vars[varIndex - 1]);
        return (char) vars[--varIndex];
    }

    private static void checkChar(int v) {
        assert (char) v == v;
    }

    public boolean restoreBoolean() {
        checkBoolean(vars[varIndex - 1]);
        return vars[--varIndex] == 0 ? false : true;
    }

    private static void checkBoolean(int v) {
        assert v == 0 || v == 1;
    }

    public byte restoreByte() {
        checkByte(vars[varIndex - 1]);
        return (byte) vars[--varIndex];
    }

    private static void checkByte(int v) {
        assert (byte) v == v;
    }

    public short restoreShort() {
        checkShort(vars[varIndex - 1]);
        return (short) vars[--varIndex];
    }

    private void checkShort(int v) {
        assert (short) v == v;
    }

    public int restoreInt() {
        return vars[--varIndex];
    }

    public long restoreLong() {
        long ret = vars[--varIndex] & 0x0FFFFFFFFL;
        ret |= (long) vars[--varIndex] << 32;
        return ret;
    }

    public float restoreFloat() {
        return Float.intBitsToFloat(vars[--varIndex]);
    }

    public double restoreDouble() {
        long bits = vars[--varIndex] & 0x0FFFFFFFFL;
        bits |= (long) vars[--varIndex] << 32;
        return Double.longBitsToDouble(bits);
    }

    public Object restoreObject() {
        return objVars[--objVarIndex];
    }

    void result() {
        leaveMethod();
    }

    void result(char c) {
        leaveMethod();
        result = c;
    }

    void result(boolean z) {
        leaveMethod();
        result = z ? 1 : 0;
    }

    void result(byte b) {
        leaveMethod();
        result = b;
    }

    void result(short s) {
        leaveMethod();
        result = s;
    }

    void result(int i) {
        leaveMethod();
        result = i;
    }

    void result(long l) {
        leaveMethod();
        hiResult = (int) (l >> 32);
        result = (int) l;
    }

    void result(float f) {
        leaveMethod();
        result = Float.floatToIntBits(f);
    }

    void result(double d) {
        leaveMethod();
        long l = Double.doubleToLongBits(d);
        hiResult = (int) (l >> 32);
        result = (int) l;
    }

    void result(Object o) {
        leaveMethod();
        objResult = o;
    }

    public char getResultChar() {
        return (char) result;
    }

    public boolean getResultBoolean() {
        // TODO: result here must be 0 or 1. hiResult must be 0. Check.
        return result == 0 ? false : true;
    }

    public byte getResultByte() {
        return (byte) result;
    }

    public short getResultShort() {
        return (short) result;
    }

    public int getResultInt() {
        return result;
    }

    public long getResultLong() {
        long ret = result & 0x0FFFFFFFFL;
        ret |= (long) hiResult << 32;
        return ret;
    }

    public float getResultFloat() {
        return Float.intBitsToFloat(result);
    }

    public double getResultDouble() {
        long bits = result & 0x0FFFFFFFFL;
        bits |= (long) hiResult << 32;
        return Double.longBitsToDouble(bits);
    }

    public Object getResultObject() {
        return objResult;
    }

    public MethodFrame notifyInvoke(int resumePoint, int varCount, int objVarCount) {
        assert state == ACTIVE || state == RESTORING;
        state = INVOKING;
        this.resumePoint = resumePoint;
        vars = varCount == 0 ? EMPTY_INT_ARRAY : new int[varCount];
        varIndex = 0;
        objVars = objVarCount == 0 ? EMPTY_OBJECT_ARRAY : new Object[objVarCount];
        objVarIndex = 0;
        return this;
    }

    public int resumePoint() {
        return resumePoint;
    }

    public MethodFrame prepare(int varTop, int objVarTop) {
        varIndex = varTop;
        objVarIndex = objVarTop;
        return this;
    }

    boolean isActive() {
        return state == ACTIVE;
    }

    boolean isInvoking() {
        return state == INVOKING;
    }

    boolean isRestoring() {
        return state == RESTORING;
    }

    void leaveThread() {
        assert state == INVOKING;
        assert resumePoint > 0;
        state = LEAVING_THREAD;
    }

    public Flow getFlow() {
        return flow;
    }

    int getState() {
        return state;
    }

    void invoked() {
        assert state == INVOKING || state == RESTORING || state == LEAVING_THREAD;
        assert resumePoint > 0;
        if (state != LEAVING_THREAD) {
            state = ACTIVE;
            resumePoint = 0;
        }
    }

    void checkMatch(Object target, String name, String desc) {
        assert this.target == target : "This target: " + this.target + ", arg target: " + target;
        assert this.name.equals(name) : "This name: " + this.name + ", arg name: " + name;
        assert this.desc.equals(desc) : "This desc: " + this.desc + ", arg desc: " + desc;
    }

    void checkResultType(char resultType) {
        if (desc != null) {
            int len = desc.length();
            if (len > 0 && desc.charAt(len - 1) == resultType) {
                return;
            }
        }
        Class clazz = target instanceof Class ? (Class) target : target.getClass();
        throw new IllegalReturnValueException("Attempt to return " + Types.getName(resultType) + " from method '" + Types.getMethodDescription(clazz, name, desc) + "'.");
    }

    private void leaveMethod() {
        if (state != ACTIVE) {
            throw new IllegalStateException("Current frame must be active to set prior frame to leave method.");
        }
        assert prior.state == INVOKING;
        state = LEAVING_METHOD;
    }

}
