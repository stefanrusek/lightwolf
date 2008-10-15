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

import java.io.Serializable;

class MethodKey implements Serializable, Comparable<MethodKey> {

    private static final long serialVersionUID = 5617732142047514986L;

    private final String owner;
    private final String name;
    private final String desc;

    public MethodKey(String owner, String name, String desc) {
        this.owner = owner.intern();
        this.name = name.intern();
        this.desc = desc.intern();
    }

    @Override
    public int hashCode() {
        return owner.hashCode() + name.hashCode() + desc.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof MethodKey)) {
            return false;
        }
        MethodKey o = (MethodKey) obj;
        return owner == o.owner && name == o.name && desc == o.desc;
    }

    public int compareTo(MethodKey mk) {
        if (mk == this) {
            return 0;
        }
        int test;
        test = owner.compareTo(mk.owner);
        if (test != 0) {
            return test;
        }
        test = name.compareTo(mk.name);
        if (test != 0) {
            return test;
        }
        return desc.compareTo(mk.desc);
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getDesc() {
        return desc;
    }

    @Override
    public String toString() {
        return owner + ',' + name + ',' + desc;
    }

}
