package com.dji.wsbridge.lib;

import android.util.Log;
import com.dji.wsbridge.lib.connection.USBConnectionManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class BridgeToUSBRunner extends Thread {

    private static final String TAG = StreamRunner.class.getSimpleName();
    public long byteCount = 0;
    // 从web-socket 读
    private InputStream mInputStream;
    // 往usb中写
    private OutputStream mOutputStream;
    private AtomicBoolean mStop = new AtomicBoolean(false);
    private DJIPluginRingBufferParser parser;

    public BridgeToUSBRunner(InputStream in, OutputStream out, String name) {
        super(name);
        mInputStream = in;
        mOutputStream = out;
        parser = new DJIPluginRingBufferParser(100 * 1024, mOutputStream);
    }

    @Override
    public void run() {
        int ret;
        USBConnectionManager.UsbModel usbModel = USBConnectionManager.getInstance().getUSBModel();
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
                        if (usbModel != USBConnectionManager.UsbModel.LOGIC_LINK) {
                            byteCount += ret;
                            mOutputStream.write(buffer, 0, ret);
                        } else {
//                            parser.parse(buffer, 0, ret);


                            int v1RealDataLength = ret + 8;
                            byte[] box_head = new byte[v1RealDataLength];
                            int it = 0;

                            box_head[it] = LOGIC_LINK_HEADER_0X55;it++;
                            box_head[it] = LOGIC_LINK_HEADER_0XCC;it++;

                            box_head[it] = (byte) (CHANNEL_CMD & 0xff);it++;
                            box_head[it] = (byte) ((CHANNEL_CMD & 0xff00) >> 8);it++;

                            box_head[it] = (byte) (ret & 0xff);it++;
                            box_head[it] = (byte) ((ret & 0xff00) >> 8);it++;
                            box_head[it] = (byte) ((ret & 0xff0000) >> 16);it++;
                            box_head[it] = (byte) ((ret & 0xff000000) >> 24);it++;

                            System.arraycopy(buffer, 0, box_head, 8, ret);
                            byteCount += ret;
                            try {
                                mOutputStream.write(box_head, 0, v1RealDataLength);
//                                mOutputStream.flush();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }

                        }
                        mOutputStream.flush();
                    }
                }
            } catch (Exception e) {
                //Crashlytics.logException(e);
                //e.printStackTrace();
                //Log.e(TAG, e.getMessage());
            }
        }
        Log.d(TAG, getName() + ": Runner is stopped");
    }

    public void cleanup() {
        mStop.set(true);
    }

    public static final int CHANNEL_CMD = 22345;

    static final byte LOGIC_LINK_HEADER_0X55 =  Utils.getByte(0x55);

    static final byte LOGIC_LINK_HEADER_0XCC =  Utils.getByte(0xCC);
}
