package com.dji.wsbridge.lib;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

//import static com.dji.wsbridge.lib.Utils.recordExceptionToFirebase;

public class StreamRunner extends Thread {

    private static final String TAG = StreamRunner.class.getSimpleName();
    public long byteCount = 0;
    private InputStream mInputStream;
    private OutputStream mOutputStream;
    private AtomicBoolean mStop = new AtomicBoolean(false);

    public StreamRunner(InputStream in, OutputStream out, String name) {
        super(name);
        mInputStream = in;
        mOutputStream = out;
    }

    @Override
    public void run() {
        int ret;
        // As explained in:
        //      https://developer.android.com/guide/topics/connectivity/usb/accessory.html
        // "The Android accessory protocol supports packet buffers up to 16384 bytes,
        // so you can choose to always declare your buffer to be of this size for simplicity."
        byte[] buffer = new byte[16384];

        while (!mStop.get()) {
            try {
                if (mOutputStream != null && mInputStream != null) {
                    ret = mInputStream.read(buffer);
                    if (ret < 0) {
                        // Do nothing since it is empty
                        Log.d(TAG, getName() + ": ret is less than 0");
                        break;
                    } else {
                        Log.d(TAG, getName() + ": Runner is running");
                        byteCount += ret;
                        mOutputStream.write(buffer, 0, ret);
                        mOutputStream.flush();
                    }
                }
            } catch (Exception e) {
                //recordExceptionToFirebase(e);
                //e.printStackTrace();
                //Log.e(TAG, e.getMessage());
            }
        }
        Log.d(TAG, getName() + ": Runner is stopped");
    }

    public void cleanup() {
        mStop.set(true);
    }
}
