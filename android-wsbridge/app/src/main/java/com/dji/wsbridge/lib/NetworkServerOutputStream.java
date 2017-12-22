/*
 * Copyright (c) 2016, DJI.  All rights reserved.
 */
package com.dji.wsbridge.lib;

import com.dji.wsbridge.lib.connection.WSConnectionManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;

public class NetworkServerOutputStream extends OutputStream {

    private static final String TAG = "BridgeStream";

    private static final int TRANSFER_SIZE_WITHOUT_VIDEO = 5;
    private static final int TRANSFER_SIZE_WITH_VIDEO = 2 * 1024;
    private static final int BUFFER_SIZE = 10 * 1024 * 1024;

    private static final byte[] VIDEO_HEADER = {0x55, (byte) 0xcc, 0x4a, 0x57};
    private static final byte[] VIDEO_EXT_HEADER = {0x55, (byte) 0xcc, 0x4b, 0x57};

    private WSConnectionManager mServer;
    private ByteArrayOutputStream mBuffer;
    private AtomicBoolean isSending = new AtomicBoolean(false);
    //private Observable timer
    Observable<Boolean> observable = Observable.fromCallable(new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
            isSending.set(true);
            // remove limit since memory issue has been fixed, this is no longer needed
            int limit = (mServer.streamFilter == WSConnectionManager.StreamFilter.FILTER_NONE)
                    ? TRANSFER_SIZE_WITH_VIDEO
                    : TRANSFER_SIZE_WITHOUT_VIDEO;
            if (mBuffer.size() > limit) {
                byte[] byteArray = mBuffer.toByteArray();
                mServer.send(byteArray);
                mBuffer.reset();
            }
            isSending.set(false);
            return true;
        }
    });

    public NetworkServerOutputStream(WSConnectionManager server) {
        mServer = server;
        mBuffer = new ByteArrayOutputStream();
    }

    @Override
    public void write(int b) throws IOException {
        if (b != -1) {
            writeAfterFilter(new byte[]{(byte) b}, 0, 1);
        }
    }

    @Override
    public void write(byte[] b) throws IOException {
        writeAfterFilter(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len != -1) {
            writeAfterFilter(b, off, len);
        }
    }

    private void writeAfterFilter(byte[] b, int off, int len) {
        if (shouldFilter(b, off, len)) {
            mBuffer.write(b, off, len);
        }
        sendIfReady();
    }

    private boolean shouldFilter(byte[] b, int off, int len) {
        //off & len not checked.
        //assumes only one frame at a time. Need to check if there are multiple frames per call.
        return ((mServer.streamFilter == WSConnectionManager.StreamFilter.FILTER_VIDEO) && (indexOf(b, VIDEO_HEADER)
                >= 0 || indexOf(b, VIDEO_EXT_HEADER) >= 0)) ? false : true;
    }

    private void sendIfReady() {
        if (!isSending.get()) {
            observable.subscribeOn(Schedulers.trampoline()).subscribe();
        }
    }

    private int indexOf(byte[] data, byte[] pattern) {
        int[] failure = computeFailure(pattern);

        int j = 0;
        if (data.length == 0) return -1;

        for (int i = 0; i < data.length; i++) {
            while (j > 0 && pattern[j] != data[i]) {
                j = failure[j - 1];
            }
            if (pattern[j] == data[i]) {
                j++;
            }
            if (j == pattern.length) {
                return i - pattern.length + 1;
            }
        }
        return -1;
    }

    /**
     * Computes the failure function using a boot-strapping process,
     * where the pattern is matched against itself.
     */
    private int[] computeFailure(byte[] pattern) {
        int[] failure = new int[pattern.length];

        int j = 0;
        for (int i = 1; i < pattern.length; i++) {
            while (j > 0 && pattern[j] != pattern[i]) {
                j = failure[j - 1];
            }
            if (pattern[j] == pattern[i]) {
                j++;
            }
            failure[i] = j;
        }

        return failure;
    }
}


