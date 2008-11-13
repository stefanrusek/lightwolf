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
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.NotYetBoundException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;

/**
 * An object that activate flows when a NIO channel operation becomes available.
 * 
 * @author Fernando Colombo
 */
public class IOActivator {

    private final Selector selector;
    private final IdentityHashMap<SelectableChannel, ?> cancelled;

    public IOActivator() throws IOException {
        selector = Selector.open();
        cancelled = new IdentityHashMap<SelectableChannel, Object>();
        new SelectorThread().start();
    }

    /**
     * Accepts multiple incoming connections without holding any thread during
     * the wait. This method returns every time a new incoming connection is
     * established on the informed socket, which means that it can return
     * multiple times, to concurrent threads. The following example illustrates
     * this behavior:
     * 
     * <pre>
     *    &#064;{@link FlowMethod}
     *    void example() {
     *        ServerSocketChannel serverSocket = ServerSocketChannel.open();
     *        serverSocket.socket().bind(new InetSocketAddress(8080));
     *        serverSocket.configureBlocking(false);
     *        IOActivator activator = new IOActivator();
     *        activator.acceptMany(serverSocket);
     *        // The following will run for each incoming connection.
     *        SocketChannel clientSocket = activator.acceptMany(serverSocket);
     *        // Process this connection. May run in parallel with other connections.
     *        clientSocket.close();
     *    }
     * </pre>
     * 
     * The informed socket must be in non-blocking mode, so this class can call
     * {@link SelectableChannel#register(Selector, int, Object) register()}
     * without triggering an exception. Despite this fact, this method never
     * returns <code>null</code> nor it blocks the current thread, as specified
     * in on {@link Flow#suspend(Object)}.
     * <p>
     * This method always returns to a new {@linkplain Flow flow}. That is, it
     * never returns to the invoker's flow.
     * 
     * @param socket The server socket. Must be already bound to an address, and
     *        must be {@linkplain SelectableChannel#configureBlocking(boolean)
     *        configured} to non-blocking mode.
     * @return A SockedChannel that just connected to the specified server
     *         socket. This method may return multiple times.
     * @throws ClosedChannelException If this channel is closed.
     * @throws AsynchronousCloseException If another thread closes this channel
     *         while the accept operation is in progress.
     * @throws ClosedByInterruptException If another thread interrupts the
     *         current thread while the accept operation is in progress, thereby
     *         closing the channel and setting the current thread's interrupt
     *         status.
     * @throws IOException If some other I/O error occurs.
     * @throws IllegalBlockingModeException If this channel is not in
     *         non-blocking mode.
     * @throws NotYetBoundException If this channel's socket has not yet been
     *         bound.
     * @throws SecurityException If a security manager has been installed and it
     *         does not permit access to the remote endpoint of the new
     *         connection.
     * @see ServerSocketChannel#accept()
     */
    @FlowMethod
    public SocketChannel acceptMany(ServerSocketChannel socket) throws IOException {
        Continuation cont = new Continuation();
        boolean resuming = !cont.checkpoint();
        try {
            synchronized(this) {
                selector.wakeup(); // Get out from select(), so this register() won't block.
                socket.register(selector, SelectionKey.OP_ACCEPT, cont);
            }
            if (resuming) {
                SocketChannel ret = socket.accept();
                if (ret != null) {
                    return ret;
                }
            }
        } catch (IOException e) {
            if (!resuming) {
                throw e;
            }
        }
        Flow.suspend();
        throw new AssertionError();
    }

    /**
     * Accepts a single incoming connection without holding any thread during
     * the wait. If the informed channel is ready to perform an
     * {@link ServerSocketChannel#accept()} operation, this method returns
     * immediately. Otherwise the channel is
     * {@linkplain SelectableChannel#register(Selector, int, Object) registered}
     * on this object's {@linkplain Selector selector} and the current flow is
     * {@linkplain Flow#suspend() suspended} until an incoming connection is
     * established.
     * <p>
     * This method always returns to the invoker's {@linkplain Flow flow}.
     * 
     * @param socket The server socket. Must be already bound to an address, and
     *        must be {@linkplain SelectableChannel#configureBlocking(boolean)
     *        configured} to non-blocking mode.
     * @return A SockedChannel that just connected to the specified server
     *         socket. This method may return multiple times.
     * @throws ClosedChannelException If this channel is closed.
     * @throws AsynchronousCloseException If another thread closes this channel
     *         while the accept operation is in progress.
     * @throws ClosedByInterruptException If another thread interrupts the
     *         current thread while the accept operation is in progress, thereby
     *         closing the channel and setting the current thread's interrupt
     *         status.
     * @throws IOException If some other I/O error occurs.
     * @throws IllegalBlockingModeException If this channel is not in
     *         non-blocking mode.
     * @throws NotYetBoundException If this channel's socket has not yet been
     *         bound.
     * @throws SecurityException If a security manager has been installed and it
     *         does not permit access to the remote endpoint of the new
     *         connection.
     * @see ServerSocketChannel#accept()
     */
    @FlowMethod
    public SocketChannel accept(ServerSocketChannel socket) throws IOException {
        wait(socket, SelectionKey.OP_ACCEPT);
        return socket.accept();
    }

    /**
     * Reads a sequence of bytes from the channel without holding any thread
     * during the wait. If the informed channel is ready to perform a
     * {@linkplain ReadableByteChannel#read(ByteBuffer) read} operation, this
     * method returns immediately. Otherwise the channel is
     * {@linkplain SelectableChannel#register(Selector, int, Object) registered}
     * on this object's {@linkplain Selector selector} and the current flow is
     * {@linkplain Flow#suspend() suspended} until the read operation becomes
     * available.
     * <p>
     * This method always returns to the invoker's {@linkplain Flow flow}.
     * 
     * @param channel The channel on which the read operation is to be
     *        performed.
     * @param dst The buffer into which bytes are to be transferred
     * @return The number of bytes read, possibly zero, or <tt>-1</tt> if the
     *         channel has reached end-of-stream
     * @throws ClassCastException If the channel is not a
     *         {@link SelectableChannel}.
     * @throws NonReadableChannelException If this channel was not opened for
     *         reading
     * @throws ClosedChannelException If this channel is closed
     * @throws AsynchronousCloseException If another thread closes this channel
     *         while the read operation is in progress
     * @throws ClosedByInterruptException If another thread interrupts the
     *         current thread while the read operation is in progress, thereby
     *         closing the channel and setting the current thread's interrupt
     *         status
     * @throws IOException If some other I/O error occurs
     * @see ReadableByteChannel#read(ByteBuffer)
     */
    @FlowMethod
    public int read(ReadableByteChannel channel, ByteBuffer dst) throws IOException {
        wait((SelectableChannel) channel, SelectionKey.OP_READ);
        return channel.read(dst);
    }

    /**
     * Writes a sequence of bytes from the channel without holding any thread
     * during the wait. If the informed channel is ready to perform a
     * {@linkplain WritableByteChannel#write(ByteBuffer) write} operation, this
     * method returns immediately. Otherwise the channel is
     * {@linkplain SelectableChannel#register(Selector, int, Object) registered}
     * on this object's {@linkplain Selector selector} and the current flow is
     * {@linkplain Flow#suspend() suspended} until the read operation becomes
     * available.
     * <p>
     * This method always returns to the invoker's {@linkplain Flow flow}.
     * 
     * @param channel The channel on which the write operation is to be
     *        performed.
     * @param src The buffer from which bytes are to be retrieved
     * @return The number of bytes written, possibly zero
     * @throws ClassCastException If the channel is not a
     *         {@link SelectableChannel}.
     * @throws NonWritableChannelException If this channel was not opened for
     *         writing
     * @throws ClosedChannelException If this channel is closed
     * @throws AsynchronousCloseException If another thread closes this channel
     *         while the write operation is in progress
     * @throws ClosedByInterruptException If another thread interrupts the
     *         current thread while the write operation is in progress, thereby
     *         closing the channel and setting the current thread's interrupt
     *         status
     * @throws IOException If some other I/O error occurs
     * @see WritableByteChannel#write(ByteBuffer)
     */
    @FlowMethod
    public int write(WritableByteChannel channel, ByteBuffer dst) throws IOException {
        wait((SelectableChannel) channel, SelectionKey.OP_WRITE);
        return channel.write(dst);
    }

    @FlowMethod
    public synchronized int wait(SelectableChannel channel, int ops) throws IOException {
        SameFlowContinuation cont = new SameFlowContinuation();
        if (!cont.checkpoint()) {
            return cont.readyOps;
        }
        selector.wakeup(); // Get out from select(), so this register() won't block.
        SelectionKey key = channel.register(selector, ops, cont);
        int readyOps = key.readyOps();
        if ((readyOps & ops) != 0) {
            key.cancel();
            return readyOps;
        }
        Flow.suspend();
        throw new AssertionError();
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

        SelectorThread() {
            super(IOActivator.this.toString());
        }

        @Override
        public void run() {
            try {
                ArrayList<Continuation> continuations = new ArrayList<Continuation>();
                for (;;) {
                    synchronized(IOActivator.this) {
                        // Cheap cyclic barrier.
                    }
                    selector.select();
                    synchronized(IOActivator.this) {
                        // Cheap cyclic barrier.
                    }
                    Set<SelectionKey> set = selector.selectedKeys();
                    for (Iterator<SelectionKey> i = set.iterator(); i.hasNext();) {
                        SelectionKey sk = i.next();
                        i.remove();
                        Continuation cont = (Continuation) sk.attachment();
                        if (cont instanceof SameFlowContinuation) {
                            ((SameFlowContinuation) cont).readyOps = sk.readyOps();
                        }
                        continuations.add(cont);
                        sk.cancel();
                    }
                    set = selector.keys();
                    synchronized(cancelled) {
                        for (SelectionKey sk : set) {
                            if (cancelled.containsKey(sk.channel())) {
                                sk.cancel();
                            }
                        }
                    }
                    selector.selectNow(); // Removes any canceled key.
                    for (Continuation cont : continuations) {
                        cont.activate();
                    }
                    continuations.clear();
                }
            } catch (IOException e) {
                // Nothing better to do... Perhaps we can log in the future.
                e.printStackTrace();
            }

        }

    }

    private static class SameFlowContinuation extends Continuation {

        private final Flow flow;
        int readyOps;

        SameFlowContinuation() {
            flow = Flow.current();
        }

        @Override
        public void activate() {
            placeOnCheckpointAndForget(flow);
            flow.activate();
        }

    }

}
