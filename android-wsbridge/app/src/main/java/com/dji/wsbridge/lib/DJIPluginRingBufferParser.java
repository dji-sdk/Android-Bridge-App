package com.dji.wsbridge.lib;

import android.util.Log;
import java.io.IOException;
import java.io.OutputStream;

public class DJIPluginRingBufferParser {
    private String TAG = getClass().getSimpleName();

    /** 命令通道 */
    public static final int CHANNEL_CMD = 22345;

    static final byte LOGIC_LINK_HEADER_0X55 =  Utils.getByte(0x55);

    static final byte LOGIC_LINK_HEADER_0XCC =  Utils.getByte(0xCC);

    /**
     * mavice air 2新增的逻辑链路的帧头长度
     */
    private static final int LOGIC_LINK_HEAD_LENGTH = 8;

    private final float factor = 1f / 8f; // 扩容因子
    
    private boolean isGettedHeader = false;
    private final boolean isDebug = true;

    protected byte[] buffer;
    private int byteOffset = 0;

    private String name = "default";

    private OutputStream mOutputStream;

    public DJIPluginRingBufferParser(int bufferSize, OutputStream out) {
        this.buffer = new byte[bufferSize];
        mOutputStream = out;
    }
    
    public void setName(String name) {
        this.name  = name;
    }
    
    public String getName() {
        return this.name;
    }

    public void parse(byte[] buffer, int offset, int count) {
        if (count > this.buffer.length - byteOffset) {
            tryToExpandCapacity(count);
        }
        System.arraycopy(buffer, offset, this.buffer, byteOffset, count);
        byteOffset += count;
        findPack();
    }

    /**
     * @Description : 当容量不足时，尝试扩容
     * @param length 需要扩容的容量值
     */
    private void tryToExpandCapacity(final int length) {
        final int newCapacity = newCapacity(this.buffer.length, length);
        if (isDebug) {
            Log.d(TAG, "Try to expand capacity:" + (newCapacity - this.buffer.length));
        }
        final byte[] newArray = new byte[newCapacity];
        System.arraycopy(this.buffer, 0, newArray, 0, this.buffer.length);
        this.buffer = newArray;
    }

    /**
     * @Description : 计算新的容量值
     * @param originLength 当前的容量值
     * @param length 需要增加的容量值
     * @return
     */
    private int newCapacity(final int originLength, final int length) {
        int size = 0;
        do {
            size += (int) (factor * originLength);
        } while (size <= length);
        return (size + originLength);
    }

    private void resetBuffer() {
        if (isDebug) {
            Log.d(TAG, "_" + name + "byteOffset=" + byteOffset + " expendSize=" + expendSize);
        }
        if (expendSize > 0) {
            byteOffset -= expendSize;
            if (byteOffset > 0) {
                System.arraycopy(buffer, expendSize, buffer, 0, byteOffset);
            } else {
                if (isDebug && byteOffset < 0) {
                    Log.d(TAG, "_" + name + "byteOffset < 0");
                }
                byteOffset = 0;
            }
            expendSize = 0;
        }
    }
    
    private int myHeaderIndex = 0;
    private int expendSize = 0;
    
    private void findPack() {
        
        while (true) {
            resetBuffer();
            
            if (!isGettedHeader) {
                for (int i = 0; i < byteOffset; i++) {
                    
                    if (buffer[i]==LOGIC_LINK_HEADER_0X55) {
                        
                        myHeaderIndex = i;
                        if (myHeaderIndex >= byteOffset) {//不足以找到完整包头 跳出循环继续接收
                            expendSize = myHeaderIndex;
                            break;
                        }

                        isGettedHeader = true;
                        
                        if (isGettedHeader) {
                            expendSize = myHeaderIndex+1;
                            break;
                        }
                    }
                    expendSize++;
                }//for
            }

            //没有包头 清空当前数据
            if (!isGettedHeader) {
                if (isDebug) {
                    Log.d(TAG, "_" + name + "parseRcvBuffer 当前buffer没有包头");
                }
                break;
            }

            if (byteOffset < expendSize + 2) {
                break;
            }

            int v1Length = Utils.getInt(buffer, expendSize, 2) & 0x03FF;
            int v1Version = Utils.getInt(buffer, expendSize, 2) >> 10;
            if (isDebug) {
                byte[] data11 = new byte[2];
                System.arraycopy(buffer, expendSize, data11, 0, 2);
                Log.d(TAG, "_" + name + "parseRcvBuffer V1 data length:"+v1Length + " v1 version:" + v1Version + "  "+ Utils.bytesToHex(data11));
            }

            if (v1Length < 0) {
                isGettedHeader = false;
                continue;
            }

            if (byteOffset < myHeaderIndex + v1Length) {
                break;
            }

            int v1RealDataLength = v1Length + LOGIC_LINK_HEAD_LENGTH;

            PackBufferObject bufferObject = PackBufferObject.getPackBufferObject(v1RealDataLength);
            byte[] box_head = bufferObject.getBuffer();
            int it = 0;

            //逻辑链路固定的header 0x55， 0xcc
            box_head[it] = LOGIC_LINK_HEADER_0X55;it++;
            box_head[it] = LOGIC_LINK_HEADER_0XCC;it++;

            //通道号
            box_head[it] = (byte) (CHANNEL_CMD & 0xff);it++;
            box_head[it] = (byte) ((CHANNEL_CMD & 0xff00) >> 8);it++;

            //v1数据长度
            box_head[it] = (byte) (v1Length & 0xff);it++;
            box_head[it] = (byte) ((v1Length & 0xff00) >> 8);it++;
            box_head[it] = (byte) ((v1Length & 0xff0000) >> 16);it++;
            box_head[it] = (byte) ((v1Length & 0xff000000) >> 24);it++;

            System.arraycopy(buffer, myHeaderIndex, box_head, it, v1Length);
            try {
                mOutputStream.write(box_head, 0, v1RealDataLength);
                mOutputStream.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
            expendSize += v1Length;
            isGettedHeader = false;
        }
        
        resetBuffer();
    }

}
