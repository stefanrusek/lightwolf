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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.lightwolf.synchronization.ThreadFreeLock;
import org.lightwolf.tools.LightWolfAntTask;

/**
 * Makes a method execute in the context of a {@link Flow}. The method will be
 * a {@link Flow flow-method}, which enables functionality of flow-specific
 * utilities such as {@link Flow#fork(int)}, {@link Flow#suspend()},
 * {@link ThreadFreeLock} and others.
 * <p>
 * Flow-methods keep track of all local and temporary variables, and invocation
 * point of other flow-methods. These are reasonably costly operations. Hence
 * it's not recommended to use this annotation in CPU-intense routines, unless
 * one can concentrate intense CPU usage into normal methods invoked by the
 * desired flow-methods.
 * <p>
 * <b>NOTE TO NEW USERS:</b> To work as expected, a flow-method must have its
 * bytecode enhanced. This can be done by the Light Wolf Eclipse Plug-in. For
 * more information, please check <a
 * href="http://lightwolf-j.blogspot.com">http://lightwolf-j.blogspot.com</a>.
 * 
 * @see Flow
 * @author Fernando Colombo
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface FlowMethod {

    boolean manual() default true;

}
