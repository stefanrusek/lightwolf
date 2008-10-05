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

import java.util.HashSet;

public class Process {

    private final HashSet<Flow> flows;

    public Process() {
        flows = new HashSet<Flow>();
    }

    synchronized void add(Flow flow) {
        if (flow.process != null) {
            if (flow.process == this) {
                assert flows.contains(flow);
                throw new IllegalArgumentException("Flow already belongs to this process.");
            }
            assert !flows.contains(flow);
            throw new IllegalArgumentException("Flow belongs to another process.");
        }
        boolean ret = flows.add(flow);
        flow.process = this;
        assert ret;
    }

    synchronized void remove(Flow flow) {
        if (flow.process != this) {
            assert !flows.contains(flow);
            throw new IllegalArgumentException("Flow does not belong to this process.");
        }
        boolean ret = flows.remove(flow);
        flow.process = null;
        assert ret;
    }

}
