/*
 * Copyright (c) 2016, DJI.  All rights reserved.
 */
package com.dji.wsbridge.lib;

import com.dji.wsbridge.lib.connection.WSConnectionManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class NetworkServerInputStream extends InputStream {
    private WSConnectionManager mServer;

    public NetworkServerInputStream(WSConnectionManager server) {
        mServer = server;
    }

    @Override
    public int read() throws IOException {
        ByteBuffer buffer = mServer.read();
        return buffer.get();
    }

    @Override
    public int read(byte[] b) throws IOException {
        ByteBuffer buffer = mServer.read();
        int len = Math.min(buffer.remaining(), b.length);
        buffer.get(b, 0, len);
        return len;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ByteBuffer buffer = mServer.read();
        len = Math.min(buffer.remaining(), b.length);
        buffer.get(b, off, len);
        return len;
    }
}
