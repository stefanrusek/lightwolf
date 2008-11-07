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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;

public class IO {

    private final Selector selector;
    private final IdentityHashMap<SelectableChannel, ?> cancelled;

    public IO() throws IOException {
        selector = Selector.open();
        cancelled = new IdentityHashMap<SelectableChannel, Object>();
        new SelectorThread().start();
    }

    @FlowMethod
    public SocketChannel acceptMany(ServerSocketChannel socket) throws IOException {
        socket.configureBlocking(false);
        Object result = Flow.signal(new AcceptManySignal(socket));
        throwUnchecked(result);
        if (result != this) {
            throw new AssertionError("Unexpected result: " + result);
        }
        return socket.accept();
    }

    @FlowMethod
    public int read(SocketChannel channel, ByteBuffer dst) throws IOException {
        Object result = Flow.signal(new GenericIOSignal(channel, SelectionKey.OP_READ));
        throwUnchecked(result);
        return channel.read(dst);
    }

    @FlowMethod
    public int write(SocketChannel channel, ByteBuffer dst) throws IOException {
        Object result = Flow.signal(new GenericIOSignal(channel, SelectionKey.OP_WRITE));
        throwUnchecked(result);
        return channel.write(dst);
    }

    private void throwUnchecked(Object result) throws IOException, Error {
        if (result instanceof IOException) {
            throw (IOException) result;
        }
        if (result instanceof RuntimeException) {
            throw (RuntimeException) result;
        }
        if (result instanceof Error) {
            throw (Error) result;
        }
    }

    public void cancelAccepts(ServerSocketChannel socket) {
        synchronized(cancelled) {
            cancelled.put(socket, null);
        }
        selector.wakeup();
    }

    public void close() throws IOException {
        // This should stop the SelectorThread.
        selector.close();
    }

    private class SelectorThread extends Thread {

        @Override
        public void run() {
            try {
                for (;;) {
                    selector.select(500);
                    Set<SelectionKey> set = selector.selectedKeys();
                    for (Iterator<SelectionKey> i = set.iterator(); i.hasNext();) {
                        SelectionKey sk = i.next();
                        SelectionSignal signal = (SelectionSignal) sk.attachment();
                        signal.runFlow();
                        i.remove();
                    }
                    set = selector.keys();
                    synchronized(cancelled) {
                        for (SelectionKey sk : set) {
                            if (cancelled.containsKey(sk.channel())) {
                                sk.cancel();
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // Nothing better to do... Perhaps we can log in the future.
                e.printStackTrace();
            }

        }

    }

    private abstract class SelectionSignal extends FlowSignal {

        private static final long serialVersionUID = 1L;

        public abstract void runFlow();

    }

    private class GenericIOSignal extends SelectionSignal {

        private static final long serialVersionUID = 1L;
        private final SelectableChannel channel;
        private final int ops;
        private SelectionKey key;

        public GenericIOSignal(SelectableChannel channel, int ops) {
            this.channel = channel;
            this.ops = ops;
        }

        @Override
        public void defaultAction() {
            try {
                key = channel.register(selector, ops, this);
            } catch (Throwable e) {
                getFlow().resume(e);
            }
        }

        @Override
        public void runFlow() {
            key.cancel();
            getFlow().activate(IO.this);
        }

    }

    private class AcceptManySignal extends SelectionSignal {

        private static final long serialVersionUID = 1L;
        private final ServerSocketChannel socket;

        public AcceptManySignal(ServerSocketChannel socket) {
            this.socket = socket;
        }

        @Override
        public void defaultAction() {
            try {
                socket.register(selector, SelectionKey.OP_ACCEPT, this);
            } catch (ClosedChannelException e) {
                // TODO: Shouldn't we throw this exception there on acceptMany?
            }
        }

        @Override
        public void runFlow() {
            getFlow().copy().activate(IO.this);
        }

    }

}
