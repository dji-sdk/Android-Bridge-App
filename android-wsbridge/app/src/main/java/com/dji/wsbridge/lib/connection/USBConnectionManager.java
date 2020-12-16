package com.dji.wsbridge.lib.connection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import com.dji.wsbridge.BridgeActivity;
import com.dji.wsbridge.lib.BridgeApplication;
import com.dji.wsbridge.lib.DJILogger;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

//import static com.dji.wsbridge.lib.Utils.recordExceptionToFirebase;

public class USBConnectionManager implements ConnectionManager {

    private static USBConnectionManager sInstance = new USBConnectionManager();
    public static USBConnectionManager getInstance() {
        return sInstance;
    }

    private static final String TAG = "USB";

    private UsbManager mUsbManager;
    private UsbAccessory mAccessory;
    private ParcelFileDescriptor mFileDescriptor;

    private InputStream mInStream;
    private OutputStream mOutStream;

    private UsbModel currentModel = UsbModel.UNKNOWN;

    public enum UsbModel {
        /**
         * 231之前的整机
         */
        AG("AG410"),

        WM160("WM160"),

        /**
         * 新增的逻辑链路
         */
        LOGIC_LINK("com.dji.logiclink"),

        UNKNOWN("Unknown");

        private String value;

        UsbModel(String value) {
            this.value = value;
        }

        public static UsbModel find(String modelName) {
            UsbModel result = UNKNOWN;
            if (TextUtils.isEmpty(modelName)) {
                return result;
            }

            for (int i = 0; i < values().length; i++) {
                if (values()[i].value.equals(modelName)) {
                    result = values()[i];
                    break;
                }
            }
            return result;
        }

        /**
         * Retrieves the display name of an enum constant.
         *
         * @return string The display name of an enum
         */
        public String getModel() {
            return this.value;
        }

    }


    private BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equalsIgnoreCase(UsbManager.ACTION_USB_ACCESSORY_DETACHED)) { //Check if change in USB state
                BridgeApplication.getInstance().getBus().post(new USBConnectionEvent(false));
                Log.d(TAG, "ACTION_USB_ACCESSORY_DETACHED");
                return;
            }
            if (action.equalsIgnoreCase("android.hardware.usb.action.USB_STATE")) { //Check if change in USB state
                if (intent.getExtras().getBoolean("connected")) {
                    Log.d(TAG, "USB_STATE CONNECTED");
                    BridgeApplication.getInstance().getBus().post(new USBConnectionEvent(true));
                } else {
                    Log.d(TAG, "USB_STATE DISCONNECTED");
                    BridgeApplication.getInstance().getBus().post(new USBConnectionEvent(false));
                }
            }
        }
    };

    /**
     * ACTION_USB_ACCESSORY_DETACHED is a Service broadcast so we can receive it here.
     * // It doesn't have to be in an Activity like {@link BridgeActivity#onNewIntent}
     */
    public void init() {
        // Broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        filter.addAction("android.hardware.usb.action.USB_STATE");
        BridgeApplication.getInstance().registerReceiver(mUsbReceiver, filter);

        Observable.timer(2, TimeUnit.SECONDS)
                  .observeOn(Schedulers.computation())
                  .repeat()
                  .subscribe(new Observer<Long>() {
                      @Override
                      public void onSubscribe(@NonNull Disposable d) {

                      }
                      @Override
                      public void onNext(@NonNull Long aLong) {
                          checkForDJIAccessory();
                      }
                      @Override
                      public void onError(@NonNull Throwable e) {

                      }
                      @Override
                      public void onComplete() {

                      }
                  });
    }

    public void destroy() {
        BridgeApplication.getInstance().unregisterReceiver(mUsbReceiver);
    }

    public void checkForDJIAccessory() {
        mUsbManager = (UsbManager) BridgeApplication.getInstance().getSystemService(Context.USB_SERVICE);
        UsbAccessory[] accessoryList = mUsbManager.getAccessoryList();
        if (accessoryList != null
            && accessoryList.length > 0
            && !TextUtils.isEmpty(accessoryList[0].getManufacturer())
            && accessoryList[0].getManufacturer().equals("DJI")) {
            mAccessory = accessoryList[0];
            String model = mAccessory.getModel();
            currentModel = UsbModel.find(model);
            BridgeApplication.getInstance().getBus().post(new RCConnectionEvent(true));
            //Check permission
            if (mUsbManager.hasPermission(mAccessory)) {
                Log.d(TAG, "RC CONNECTED");
            } else {
                Log.d(TAG, "NO Permission to USB Accessory");
                DJILogger.e(TAG, "NO Permission to USB Accessory");
                //mUsbManager.requestPermission(mAccessory, null);
            }
        } else {
            BridgeApplication.getInstance().getBus().post(new RCConnectionEvent(false));
            Log.d(TAG, "RC DISCONNECTED");
        }
    }

    @Override
    public ArrayList<Object> getStreams() {
        try {
            if (mAccessory != null && mUsbManager.hasPermission(mAccessory)) {
                mFileDescriptor = mUsbManager.openAccessory(mAccessory);
                if (mFileDescriptor != null) {
                    FileDescriptor fd = mFileDescriptor.getFileDescriptor();
                    if (fd.valid()) {
                        mInStream = new FileInputStream(fd);
                        mOutStream = new FileOutputStream(fd);
                    } else {
                        DJILogger.e(TAG, "Invalid File Descriptor");
                    }
                } else {
                    DJILogger.e(TAG, "Cannot Open Accessory");
                }
            } else {
                DJILogger.e(TAG, "Accessory NOT available");
            }
        } catch (Exception e) {

        }

        ArrayList<Object> array = new ArrayList<>();
        if (mInStream != null && mOutStream != null) {
            array.add(mInStream);
            array.add(mOutStream);
        }
        return array;
    }

    @Override
    public void closeStreams() {
        try {
            if (mOutStream != null) {
                mOutStream.close();
                mOutStream = null;
            }
            if (mInStream != null) {
                mInStream.close();
                mInStream = null;
            }
            if (mFileDescriptor != null) {
                mFileDescriptor.close();
                mFileDescriptor = null;
            }
        } catch (IOException e) {
            //recordExceptionToFirebase(e);
            e.printStackTrace();
        }
    }

    public UsbModel getUSBModel() {
        return currentModel;
    }

    /**
     * Event to send changes in USB Connection
     */
    public static final class USBConnectionEvent {
        final boolean isConnected;
        public USBConnectionEvent(boolean isConnected) {
            this.isConnected = isConnected;
        }
        public boolean isConnected() {
            return isConnected;
        }
    }

    /**
     * Event to send changes in DJI RC Connection
     */
    public static final class RCConnectionEvent {
        final boolean isConnected;
        public RCConnectionEvent(boolean isConnected) {
            this.isConnected = isConnected;
        }
        public boolean isConnected() {
            return isConnected;
        }
    }
}
