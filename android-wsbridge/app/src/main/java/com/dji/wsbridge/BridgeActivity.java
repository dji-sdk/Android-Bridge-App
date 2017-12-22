package com.dji.wsbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.dji.wsbridge.lib.BridgeApplication;
import com.dji.wsbridge.lib.DJIRemoteLogger;
import com.dji.wsbridge.lib.StreamRunner;
import com.dji.wsbridge.lib.Utils;
import com.dji.wsbridge.lib.connection.USBConnectionManager;
import com.dji.wsbridge.lib.connection.WSConnectionManager;
import com.squareup.otto.Subscribe;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class BridgeActivity extends Activity {

    private static final int WEB_SOCKET_PORT = 9007;

    public static final String TAG = "AndroidBridge";

    private TextView mIPTextView;
    private ImageView mRCIconView;
    private ImageView mWifiIconView;

    private AtomicBoolean isUSBConnected = new AtomicBoolean(false);
    private AtomicBoolean isRCConnected = new AtomicBoolean(false);
    private AtomicBoolean isWiFiConnected = new AtomicBoolean(false);
    public static AtomicBoolean isStarted = new AtomicBoolean(false);
    private AtomicBoolean isStreamRunnerActive = new AtomicBoolean(false);

    private InputStream usbInputStream;
    private OutputStream usbOutputStream;
    private InputStream wsInputStream;
    private OutputStream wsOutputStream;
    private StreamRunner deviceToWSRunner;
    private StreamRunner wsToDeviceRunner;

    //region -------------------------------------- Activity Callbacks and Helpers ---------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Utils.configureLogger();
        setupViews();
        setupWSConnectionManager();
    }

    @Override
    protected void onStart() {
        super.onStart();
        refresh();
        DJIRemoteLogger.v(TAG, "Started");
        isStarted.set(true);
    }

    @Override
    protected void onStop() {
        DJIRemoteLogger.v(TAG, "Stopped");
        isStarted.set(false);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BridgeApplication.getInstance().getBus().register(this);
        USBConnectionManager.getInstance().init();
        DJIRemoteLogger.v(TAG, "Resumed");
    }

    @Override
    protected void onPause() {
        DJIRemoteLogger.v(TAG, "Paused");
        USBConnectionManager.getInstance().destroy();
        stopStreamTransfer();
        BridgeApplication.getInstance().getBus().unregister(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        DJIRemoteLogger.v(TAG, "Destroyed");
        super.onDestroy();
    }

    /**
     * ACTION_USB_ACCESSORY_ATTACHED is an Activity Broadcast.
     * Thus this needs to be here not inside {@link USBConnectionManager}
     */
    @Override
    protected void onNewIntent(Intent intent) {

        switch (intent.getAction()) {
            case UsbManager.ACTION_USB_ACCESSORY_ATTACHED:
                BridgeApplication.getInstance().getBus().post(new USBConnectionManager.USBConnectionEvent(true));
                break;
        }
        super.onNewIntent(intent);
    }

    private void setupViews() {
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // Init all view elements
        setContentView(R.layout.activity_bridge);
        mIPTextView = (TextView) findViewById(R.id.iptextView);
        mRCIconView = (ImageView) findViewById(R.id.imageViewRC);
        mWifiIconView = (ImageView) findViewById(R.id.imageViewWifi);
        TextView mVersionTextView = (TextView) findViewById(R.id.versionText);
        // Show version number
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = pInfo.versionName;
            mVersionTextView.setText(version);
        } catch (PackageManager.NameNotFoundException e) {
            mVersionTextView.setText("N/A");
        }
    }

    private void refresh() {
        refreshViews();
        refreshRunners();
    }
    private void refreshViews() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mIPTextView.setText(Utils.getIPAddress(true));
                if (isUSBConnected.get()) {
                    if (isRCConnected.get()) {
                        mRCIconView.setColorFilter(getResources().getColor(android.R.color.holo_green_light),
                                                   PorterDuff.Mode.MULTIPLY);
                    } else {
                        mRCIconView.setColorFilter(getResources().getColor(android.R.color.holo_purple),
                                                   android.graphics.PorterDuff.Mode.MULTIPLY);
                    }
                } else {
                    if (isRCConnected.get()) {
                        mRCIconView.setColorFilter(getResources().getColor(android.R.color.holo_purple),
                                                   android.graphics.PorterDuff.Mode.MULTIPLY);
                    } else {
                        mRCIconView.setColorFilter(getResources().getColor(android.R.color.holo_red_light),
                                                   PorterDuff.Mode.MULTIPLY);
                    }
                }
                if (isWiFiConnected.get()) {
                    mWifiIconView.setColorFilter(getResources().getColor(android.R.color.holo_green_light),
                                                 PorterDuff.Mode.MULTIPLY);
                } else {
                    mWifiIconView.setColorFilter(getResources().getColor(android.R.color.holo_red_light),
                                                 PorterDuff.Mode.MULTIPLY);
                }
            }
        });
    }

    private void refreshRunners() {
        if (!runnerStateIsIncorrect()) {
            return;
        }
        if (isUSBConnected.get() && isRCConnected.get() && isWiFiConnected.get() && !isStreamRunnerActive.get()) {
            if (setupStreams()) {
                Log.d(TAG, "Starting Runners");
                isStreamRunnerActive.set(true);
                deviceToWSRunner = new StreamRunner(wsInputStream, usbOutputStream, "Bridge to USB");
                wsToDeviceRunner = new StreamRunner(usbInputStream, wsOutputStream, "USB to Bridge");
                deviceToWSRunner.start();
                wsToDeviceRunner.start();
            } else {
                Log.d(TAG, "Stream Transfers NOT started");
                DJIRemoteLogger.e(TAG, "Stream Transfers NOT started");
            }
        } else {
            if (isStreamRunnerActive.get() && (!isUSBConnected.get() || !isRCConnected.get())
                || !isWiFiConnected.get()) {
                Log.d(TAG, "Stopping Runners");
                stopStreamTransfer();
            } else {
                Log.d(TAG, "Nothing is running to stop");
            }
        }
    }

    /**
     * Runner should and should only run when all connections are green
     */
    private boolean runnerStateIsIncorrect() {
        return areAllConnectionsGreen() && !isStreamRunnerActive.get()
            || !areAllConnectionsGreen() && isStreamRunnerActive.get();
    }

    /**
     * Check weather all the connections are connected
     */
    private boolean areAllConnectionsGreen() {
        return isUSBConnected.get() && isRCConnected.get() && isWiFiConnected.get();
    }

    private boolean setupStreams() {

        ArrayList<Object> deviceStreams = USBConnectionManager.getInstance().getStreams();
        if (deviceStreams.size() == 2) {
            usbInputStream = (InputStream) deviceStreams.get(0);
            usbOutputStream = (OutputStream) deviceStreams.get(1);
        } else {
            Log.d(TAG, "USB Streams not available");
            DJIRemoteLogger.e(TAG, "USB Streams not available");
            return false;
        }

        ArrayList<Object> wsStreams = WSConnectionManager.getInstance().getStreams();
        if (wsStreams.size() == 2) {
            wsInputStream = (InputStream) wsStreams.get(0);
            wsOutputStream = (OutputStream) wsStreams.get(1);
        } else {
            Log.d(TAG, "WS Streams not available");
            DJIRemoteLogger.e(TAG, "WS Streams not available");
            return false;
        }

        return true;
    }

    private void stopStreamTransfer() {
        if (wsToDeviceRunner != null && deviceToWSRunner != null) {
            isStreamRunnerActive.set(false);

            wsToDeviceRunner.cleanup();
            wsToDeviceRunner = null;

            deviceToWSRunner.cleanup();
            deviceToWSRunner = null;

            USBConnectionManager.getInstance().closeStreams();
            WSConnectionManager.getInstance().closeStreams();
        }
    }
    //endregion -----------------------------------------------------------------------------------------------------

    //region -------------------------------------- USB Helper Methods ---------------------------------------------
    @Subscribe
    public void onUSBConnectionEvent(USBConnectionManager.USBConnectionEvent event) {
        if (isUSBConnected.compareAndSet(!event.isConnected(), event.isConnected())) {
            refresh();
            showToast("", isUSBConnected.get(), "USB");
        }
    }
    @Subscribe
    public void onRCConnectionEvent(USBConnectionManager.RCConnectionEvent event) {
        if (isRCConnected.compareAndSet(!event.isConnected(), event.isConnected())) {
            refresh();
            showToast("", isRCConnected.get(), "RC");
        } else {
            // In some this event comes after runners have been already started so we need to restart runner to make bridge work
            refreshRunners();
        }
    }
    //endregion -----------------------------------------------------------------------------------------------------

    //region -------------------------------------- WS Server Helper Methods ------------------------------------------
    private void setupWSConnectionManager() {
        try {
            WSConnectionManager.setupInstance(WEB_SOCKET_PORT);
        } catch (UnknownHostException e) {
            //Crashlytics.logException(e);
            //e.printStackTrace();
            //Log.e(TAG,e.getMessage());
        }
    }
    @Subscribe
    public void onWSConnectionEvent(WSConnectionManager.WSConnectionEvent event) {
        boolean shouldRefresh = false;
        if (event.getActiveConnectionCount() > 0) {
            if (isWiFiConnected.compareAndSet(false, true)) {
                shouldRefresh = true;
            }
        } else {
            if (isWiFiConnected.compareAndSet(true, false)) {
                shouldRefresh = true;
            }
        }
        if (shouldRefresh) {
            refresh();
        }
        showToast("Network ", isWiFiConnected.get(), event.getMessage());
    }
    //endregion -----------------------------------------------------------------------------------------------------

    //region Internal Utility Methods
    private void showToast(String connection, boolean isConnected, String message) {

        final String finalMessage =
            isConnected ? connection + "Connected to " + message : connection + "Disconnected from " + message;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), finalMessage, Toast.LENGTH_SHORT).show();
                DJIRemoteLogger.i(TAG, finalMessage);
            }
        });
    }
    //endregion
}
