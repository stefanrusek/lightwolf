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
package org.lightwolf.tests;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.junit.Assert;
import org.junit.Test;
import org.lightwolf.Flow;
import org.lightwolf.FlowMethod;
import org.lightwolf.FlowSignal;
import org.lightwolf.IO;

public class TestSocketIO {

    @Test
    public void simplest() throws IOException, InterruptedException {
        if (true) {
            Assert.fail();
        }
        try {
            doTest();
        } catch (FlowSignal s) {
            s.defaultAction();
        }
        synchronized(this) {
            wait();
        }
    }

    int flags;

    @FlowMethod
    private void doTest() throws IOException, InterruptedException {

        IO socketIO = new IO();
        int branch = Flow.fork(4);
        if (branch == 0) {
            server(socketIO);
        } else {
            Thread.sleep(100); // Wait for the server to bind.
            client(branch);
        }

    }

    @FlowMethod
    private void server(IO socketIO) throws IOException {

        ServerSocketChannel channel = ServerSocketChannel.open();
        channel.socket().bind(new InetSocketAddress(8080));

        SocketChannel socket = socketIO.acceptMany(channel);
        if (socket == null) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocate(4);
        while (buffer.remaining() > 0) {
            socket.read(buffer);
        }
        synchronized(this) {
            flags |= 1 << buffer.getInt(0);
            if (flags == 30) {
                notify();
            }
        }
    }

    private void client(int branch) throws IOException {
        SocketChannel socket = SocketChannel.open(new InetSocketAddress("localhost", 8080));
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(branch);
        buffer.flip();
        socket.write(buffer);
        socket.close();
    }

}
