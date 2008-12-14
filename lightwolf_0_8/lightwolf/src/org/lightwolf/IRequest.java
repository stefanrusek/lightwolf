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

/**
 * An object that contains a request sent to a flow. Instances of this interface
 * can be use to send a {@linkplain #response(Object) response} for a given
 * {@linkplain #request() request}.
 * 
 * @see Process#serve(Object)
 * @author Fernando Colombo
 */
public interface IRequest {

    /**
     * Tells whether the sender requires or not a response. Whenever this method
     * returns <code>true</code>, the sender is likely to be blocked, waiting
     * for a response.
     */
    boolean needResponse();

    /**
     * The object that represents the request. This is the object passed with
     * the <code>message</code> argument of methods such as
     * {@link Process#send(Object, Object)} or
     * {@link Process#call(Object, Object)}.
     */
    Object request();

    /**
     * Sends the response, resuming the flow that is waiting for it. This method
     * can only be called when {@link #needResponse()} is <code>true</code>,
     * otherwise an exception is thrown. Once called, this method causes further
     * invocations to {@link #needResponse()} to return <code>false</code>.
     * 
     * @param response The response to be sent to the flow that issued the
     *        request. This object will be the returned value of the ongoing
     *        {@link Process#call(Object, Object)}.
     * @throws IllegalStateException If there is no flow waiting for the
     *         response.
     */
    @FlowMethod
    void response(Object response);

}
