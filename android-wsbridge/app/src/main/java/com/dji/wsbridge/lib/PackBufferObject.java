package com.dji.wsbridge.lib;

import java.util.ArrayList;

public class PackBufferObject {

    private byte[] buffer;
    private volatile boolean isUsing = true;
    private boolean isRepeat;

    private PackBufferObject(int length) {
        int minLength = 100;
        int mLength = length< minLength ? minLength : length;
        buffer = new byte[mLength];//最长上行长度
    }

    public byte[] getBuffer() {
        return buffer;
    }

    public void willRepeat(boolean isRepeat) {
        this.isRepeat = isRepeat;
    }
    public void noUsed() {
        if (!isRepeat)isUsing = false;
    }

    private static ArrayList<PackBufferObject> list = new ArrayList<>();
    static synchronized PackBufferObject getPackBufferObject(int length) {
        for (PackBufferObject object : list) {
            if (!object.isUsing && object.getBuffer().length>=length) {
                object.isUsing = true;
                return object;
            }
        }
        PackBufferObject object = new PackBufferObject(length);
        list.add(object);
        return object;
    }
}