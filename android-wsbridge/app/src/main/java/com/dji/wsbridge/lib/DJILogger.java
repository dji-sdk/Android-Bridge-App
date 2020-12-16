package com.dji.wsbridge.lib;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.StringDef;

import com.androidnetworking.AndroidNetworking;
import com.androidnetworking.error.ANError;
import com.androidnetworking.interfaces.OkHttpResponseListener;
import com.dji.wsbridge.BuildConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Response;

//import static com.dji.wsbridge.lib.Utils.recordExceptionToFirebase;

public class DJILogger extends Thread {

    public static final String CONNECTION_GOOD = "good";
    public static final String CONNECTION_BAD = "bad";
    public static final String CONNTECTION_WARNING = "warning";
    public static final int CONNECTION_RC = 1;
    public static final int CONNECTION_BRIDGE = 2;
    private static final String TAG = "RemoteLogger";
    // Add your remote server IP
    private static final String REMOTE_LOGGER_URL = BuildConfig.BASE_URL + "/updateBridgeStatus.php";
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private static DJILogger instance = new DJILogger();
    private String serverURL;
    private String deviceID;
    private boolean isEnabled = false;
    private boolean isNetworkEnabled = true;
    private List<JSONObject> messageQueue;

    private DJILogger() {
        messageQueue = Collections.synchronizedList(new ArrayList<JSONObject>());
        this.start();
    }


    //region Public APIs
    public static void v(String tag, String message) {
        if (instance.isEnabled) {
            if (instance.isNetworkEnabled) {
                instance.sendMessage("verbose", tag + ": " + message);
            }
            Log.e(tag, message);
        }
    }

    public static void i(String tag, String message) {
        if (instance.isEnabled) {
            if (instance.isNetworkEnabled) {
                instance.sendMessage("info", tag + ": " + message);
            }
            Log.e(tag, message);
        }
    }

    public static void d(String tag, String message) {
        if (instance.isEnabled) {
            if (instance.isNetworkEnabled) {
                instance.sendMessage("debug", tag + ": " + message);
            }
            Log.e(tag, message);
        }
    }

    public static void w(String tag, String message) {
        if (instance.isEnabled) {
            if (instance.isNetworkEnabled) {
                instance.sendMessage("warn", tag + ": " + message);
            }
            Log.e(tag, message);
        }
    }

    public static void e(String tag, String message) {
        if (instance.isEnabled) {
            if (instance.isNetworkEnabled) {
                instance.sendMessage("error", tag + ": " + message);
            }
            Log.e(tag, message);
        }
    }

    public static void logConnectionChange(@ConnetionTypeParam int connectionType, @ConnetionValueParam String value) {
        if (instance.isEnabled) {
            if (instance.isNetworkEnabled) {
                instance.sendMessage(String.valueOf(connectionType), value);
            }
            Log.d(TAG, "Connection change for type: " + connectionType + " new value: " + value);
        }
    }


    public static void init() {
        DJILogger.setServerURL(REMOTE_LOGGER_URL);
        final String ip = Utils.getIPAddress(true);
        // Set the last group of IP address to be DeviceID
        String deviceID = "";
        if (!TextUtils.isEmpty(ip)) {
            final String[] split = ip.split("\\.");
            if (split.length > 0) {
                deviceID = split[split.length - 1];
            }
            DJILogger.setDeviceID(deviceID, false);
            DJILogger.setEnabled(true);
        }
    }
    //endregion

    //region Helper Methods
    private static void setServerURL(String serverURL) {
        instance.serverURL = serverURL;
    }

    private static void setDeviceID(String deviceID, boolean forceUpdate) {
        if (TextUtils.isEmpty(instance.deviceID) || forceUpdate) {
            instance.deviceID = deviceID;
        }
    }

    private static void setEnabled(boolean isEnabled) {
        instance.isEnabled = isEnabled;
    }

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
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

    private void sendMessage(String LogLevel, String message) {

        JSONObject object = new JSONObject();
        try {
            object.put("device_id", deviceID);
            object.put("log_level", LogLevel);
            object.put("message", message);
            object.put("time_stamp", System.currentTimeMillis());
        } catch (JSONException e) {
            //recordExceptionToFirebase(e);
            e.printStackTrace();
        }
        messageQueue.add(object);
    }
    //endregion

    @Override
    public void run() {

        while (isNetworkEnabled) {
            if (messageQueue.size() > 0) {
                final JSONObject object = messageQueue.remove(0);
                AndroidNetworking.post(serverURL).addBodyParameter("data", object.toString()).build()
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

    @Retention(RetentionPolicy.CLASS)
    @StringDef({
            CONNECTION_BAD, CONNECTION_GOOD, CONNTECTION_WARNING
    })
    @interface ConnetionValueParam {
    }

    @Retention(RetentionPolicy.CLASS)
    @IntDef({
            CONNECTION_RC, CONNECTION_BRIDGE
    })
    @interface ConnetionTypeParam {
    }


}
