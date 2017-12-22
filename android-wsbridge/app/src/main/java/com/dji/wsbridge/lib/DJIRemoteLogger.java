package com.dji.wsbridge.lib;

import android.util.Log;
import com.androidnetworking.AndroidNetworking;
import com.androidnetworking.error.ANError;
import com.androidnetworking.interfaces.OkHttpResponseListener;
import com.crashlytics.android.Crashlytics;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;

public class DJIRemoteLogger extends Thread {

    private static DJIRemoteLogger instance = new DJIRemoteLogger();
    public static final String TAG = "RemoteLogger";

    private String serverURL;
    private String deviceID;
    private boolean isEnabled = false;
    private boolean isNetworkEnabled = true;
    private List<JSONObject> messageQueue;

    public DJIRemoteLogger() {
        messageQueue = Collections.synchronizedList(new ArrayList<JSONObject>());
        this.start();
    }

    public static void setServerURL(String serverURL) {
        instance.serverURL = serverURL;
    }
    public static void setDeviceID(String deviceID) {
        instance.deviceID = deviceID;
    }
    public static void setEnabled(boolean isEnabled) {
        instance.isEnabled = isEnabled;
    }
    public static void setNetworkEnabled(boolean enabled) {
        instance.isNetworkEnabled = enabled;
    }

    public static void v(String tag, String message) {
        if (instance.isEnabled) {
            //Crashlytics.log(Log.VERBOSE, tag, message);
            //if (instance.isNetworkEnabled) { instance.sendMessage("verbose", tag + ": " + message); }
            //Log.e(tag,message);
        }
    }

    public static void i(String tag, String message) {
        if (instance.isEnabled) {
            //Crashlytics.log(Log.INFO, tag, message);
            //if (instance.isNetworkEnabled) { instance.sendMessage("info", tag + ": " + message); }
            //Log.e(tag,message);
        }
    }

    public static void d(String tag, String message) {
        if (instance.isEnabled) {
            //Crashlytics.log(Log.DEBUG, tag, message);
            //if (instance.isNetworkEnabled) { instance.sendMessage("debug", tag + ": " + message); }
            //Log.e(tag,message);
        }
    }

    public static void w(String tag, String message) {
        if (instance.isEnabled) {
            //Crashlytics.log(Log.WARN, tag, message);
            //if (instance.isNetworkEnabled) { instance.sendMessage("warn", tag + ": " + message); }
            //Log.e(tag,message);
        }
    }

    public static void e(String tag, String message) {
        if (instance.isEnabled) {
            //Crashlytics.log(Log.ERROR, tag, message);
            //if (instance.isNetworkEnabled) { instance.sendMessage("error", tag + ": " + message); }
            //Log.e(tag,message);
        }
    }

    private void sendMessage(String LogLevel, String message) {

        JSONObject object = new JSONObject();
        try {
            object.put("device_id", deviceID);
            object.put("log_level", LogLevel);
            object.put("message", message);
            object.put("time_stamp", System.currentTimeMillis());
        } catch (JSONException e) {
            Crashlytics.logException(e);

            e.printStackTrace();
        }
        messageQueue.add(object);
    }

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static String MD5_Hash(byte[] bytes) {
        MessageDigest m = null;

        try {
            m = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        m.update(bytes, 0, bytes.length);
        String hash = new BigInteger(1, m.digest()).toString(16).toUpperCase();
        return hash;
    }

    public static String sha1Hash(byte[] bytes) {
        String hash = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(bytes, 0, bytes.length);
            bytes = digest.digest();
            hash = bytesToHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return hash;
    }

    @Override
    public void run() {

        while (isNetworkEnabled) {
            if (messageQueue.size() > 0) {
                final JSONObject object = messageQueue.remove(0);
                AndroidNetworking.put(serverURL)
                                 .addJSONObjectBody(object)
                                 .build()
                                 .getAsOkHttpResponse(new OkHttpResponseListener() {
                                     @Override
                                     public void onResponse(Response response) {
                                         //DO Nothing
                                     }

                                     @Override
                                     public void onError(ANError anError) {
                                         Log.e(TAG, "Error: " + anError);
                                     }
                                 });
            }
        }
    }
}
