package com.dji.wsbridge;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.dji.wsbridge.lib.BridgeApplication;
import com.dji.wsbridge.lib.BridgeToUSBRunner;
import com.dji.wsbridge.lib.BridgeUpdateService;
import com.dji.wsbridge.lib.DJILogger;
import com.dji.wsbridge.lib.StreamRunner;
import com.dji.wsbridge.lib.Utils;
import com.dji.wsbridge.lib.connection.USBConnectionManager;
import com.dji.wsbridge.lib.connection.WSConnectionManager;
import com.squareup.otto.Subscribe;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Consumer;

import static com.dji.wsbridge.lib.Utils.isInternalVersion;
//import static com.dji.wsbridge.lib.Utils.logToFirebase;
//import static com.dji.wsbridge.lib.Utils.recordExceptionToFirebase;


public class BridgeActivity extends Activity {

    public static final String TAG = "AndroidBridge";
    private static final int WEB_SOCKET_PORT = 9007;
    private static final Observable HEART_BEAT = Observable.timer(2, TimeUnit.SECONDS).repeat().observeOn(AndroidSchedulers.mainThread());
    public static AtomicBoolean isStarted = new AtomicBoolean(false);
    BroadcastReceiver updateAvailableReceiver;
    IntentFilter updateAvailableFilter;
    SharedPreferences sharedPreferences;
    Context ctx;
    private Intent bridgeServiceIntent;
    private TextView mIPTextView;
    private ImageView mRCIconView;
    private ImageView mWifiIconView;
    private final AtomicBoolean isUSBConnected = new AtomicBoolean(false);
    private final AtomicBoolean isRCConnected = new AtomicBoolean(false);
    private final AtomicBoolean isWiFiConnected = new AtomicBoolean(false);
    private final AtomicBoolean isWSTrafficSlow = new AtomicBoolean(false);
    private final AtomicBoolean isStreamRunnerActive = new AtomicBoolean(false);
    private InputStream usbInputStream;
    private OutputStream usbOutputStream;
    private InputStream wsInputStream;
    private OutputStream wsOutputStream;
    private BridgeToUSBRunner deviceToWSRunner;
    private StreamRunner wsToDeviceRunner;
    private ImageButton btnSettings, btnInstallUpdate;
    private BridgeUpdateService bridgeUpdateService;

    //region -------------------------------------- Activity Callbacks and Helpers ---------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DJILogger.init();
        setupViews();
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(BridgeActivity.this);
        setupWSConnectionManager();
        startHeartBeat();
        setupUpdateService();
    }

    private void setupUpdateService() {
        if (isInternalVersion()) {
            updateAvailableReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    btnInstallUpdate.setVisibility(View.VISIBLE);
                    Animation mAnimation = new AlphaAnimation(1, 0);
                    mAnimation.setDuration(200);
                    mAnimation.setInterpolator(new LinearInterpolator());
                    mAnimation.setRepeatCount(Animation.INFINITE);
                    mAnimation.setRepeatMode(Animation.REVERSE);

                    btnInstallUpdate.startAnimation(mAnimation);
                }
            };
            updateAvailableFilter = new IntentFilter(getResources().getString(R.string.intent_filter_update_available));

            bridgeUpdateService = new BridgeUpdateService();
            bridgeServiceIntent = new Intent(this, bridgeUpdateService.getClass());
            if (!isMyServiceRunning(bridgeUpdateService.getClass())) {
                startService(bridgeServiceIntent);
            }
        }
    }


    @Override
    protected void onStart() {
        super.onStart();
        refresh();
        DJILogger.v(TAG, "Started");
        isStarted.set(true);
    }

    @Override
    protected void onStop() {
        DJILogger.v(TAG, "Stopped");
        isStarted.set(false);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BridgeApplication.getInstance().getBus().register(this);
        DJILogger.init();
        USBConnectionManager.getInstance().init();
        if (isInternalVersion()) {
            BridgeActivity.this.registerReceiver(updateAvailableReceiver, updateAvailableFilter);
        }
        DJILogger.v(TAG, "Resumed");
    }

    @Override
    protected void onPause() {
        DJILogger.v(TAG, "Paused");
        USBConnectionManager.getInstance().destroy();
        stopStreamTransfer();
        BridgeApplication.getInstance().getBus().unregister(this);

        super.onPause();

    }

    @Override
    protected void onDestroy() {
        DJILogger.v(TAG, "Destroyed");
        if (isInternalVersion()) {
            this.unregisterReceiver(updateAvailableReceiver);
        }
        super.onDestroy();
    }

    /**
     * ACTION_USB_ACCESSORY_ATTACHED is an Activity Broadcast.
     * Thus this needs to be here not inside {@link USBConnectionManager}
     */
    @Override
    protected void onNewIntent(Intent intent) {
        if (intent.getAction() != null)
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
        setUpUpdateViews();

        // Show version number
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = pInfo.versionName;
            mVersionTextView.setText(version);
        } catch (PackageManager.NameNotFoundException e) {
            mVersionTextView.setText("N/A");
        }
    }

    private void setUpUpdateViews() {
        btnSettings = (ImageButton) findViewById(R.id.btnSettings);
        btnInstallUpdate = (ImageButton) findViewById(R.id.btnInstallUpdate);
        if (isInternalVersion()) {
            btnSettings.setVisibility(View.VISIBLE);
            btnSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent settingsIntent = new Intent(BridgeActivity.this, SettingsActivity.class);
                    startActivity(settingsIntent);

                }
            });
            btnInstallUpdate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AlertDialog.Builder builder;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        builder = new AlertDialog.Builder(BridgeActivity.this, android.R.style.Theme_Material_Dialog_Alert);
                    } else {
                        builder = new AlertDialog.Builder(BridgeActivity.this);
                    }
                    builder.setTitle("Update avaialble !")
                            .setMessage("Update is available. Do you want to update the app?")
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    btnInstallUpdate.clearAnimation();
                                    btnInstallUpdate.setVisibility(View.GONE);
                                    Intent install = new Intent(Intent.ACTION_VIEW);
                                    install.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                    install.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    Uri uri = Uri.parse(sharedPreferences.getString(getResources().getString(R.string.preference_update_uri), ""));
                                    install.setDataAndType(uri,
                                            sharedPreferences.getString(getResources().getString(R.string.preference_update_mimetype), ""));
                                    startActivity(install);
                                }
                            })
                            .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    // do nothing
                                }
                            })
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .show();

                }
            });
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
                        DJILogger.logConnectionChange(DJILogger.CONNECTION_RC, DJILogger.CONNECTION_GOOD);
                    } else {
                        mRCIconView.setColorFilter(getResources().getColor(android.R.color.holo_purple),
                                android.graphics.PorterDuff.Mode.MULTIPLY);
                        DJILogger.logConnectionChange(DJILogger.CONNECTION_RC, DJILogger.CONNTECTION_WARNING);
                    }
                } else {
                    if (isRCConnected.get()) {
                        mRCIconView.setColorFilter(getResources().getColor(android.R.color.holo_purple),
                                android.graphics.PorterDuff.Mode.MULTIPLY);
                        DJILogger.logConnectionChange(DJILogger.CONNECTION_RC, DJILogger.CONNTECTION_WARNING);
                    } else {
                        mRCIconView.setColorFilter(getResources().getColor(android.R.color.holo_red_light),
                                PorterDuff.Mode.MULTIPLY);
                        DJILogger.logConnectionChange(DJILogger.CONNECTION_RC, DJILogger.CONNECTION_BAD);
                    }
                }
                if (isWiFiConnected.get()) {
                    mWifiIconView.setColorFilter(getResources().getColor(android.R.color.holo_green_light),
                            PorterDuff.Mode.MULTIPLY);
                    DJILogger.logConnectionChange(DJILogger.CONNECTION_BRIDGE, DJILogger.CONNECTION_GOOD);
                } else {
                    mWifiIconView.setColorFilter(getResources().getColor(android.R.color.holo_red_light),
                            PorterDuff.Mode.MULTIPLY);
                    DJILogger.logConnectionChange(DJILogger.CONNECTION_BRIDGE, DJILogger.CONNECTION_BAD);

                }
            }
        });
    }

    /**
     * This is to make sure the app keeps sending some message every 2 seconds to the server
     */
    private void startHeartBeat() {
        HEART_BEAT.subscribe(new Consumer() {
            @Override
            public void accept(Object o) throws Exception {
                refreshViews();
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
                deviceToWSRunner = new BridgeToUSBRunner(wsInputStream, usbOutputStream, "Bridge to USB");
                wsToDeviceRunner = new StreamRunner(usbInputStream, wsOutputStream, "USB to Bridge");
                try {
                    //import static com.dji.wsbridge.lib.Utils.recordExceptionToFirebase;Firebase("Device to WS Runner alive " + deviceToWSRunner.isAlive());
                    //logToFirebase("WS to Device Runner alive " + wsToDeviceRunner.isAlive());
                    deviceToWSRunner.start();
                    wsToDeviceRunner.start();
                } catch (IllegalThreadStateException exception) {
                    //recordExceptionToFirebase(exception);
                    stopStreamTransfer();
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    }, 1500);
                }

            } else {
                Log.d(TAG, "Stream Transfers NOT started");
                DJILogger.e(TAG, "Stream Transfers NOT started");
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
            DJILogger.e(TAG, "USB Streams not available");
            return false;
        }

        ArrayList<Object> wsStreams = WSConnectionManager.getInstance().getStreams();
        if (wsStreams.size() == 2) {
            wsInputStream = (InputStream) wsStreams.get(0);
            wsOutputStream = (OutputStream) wsStreams.get(1);
        } else {
            Log.d(TAG, "WS Streams not available");
            DJILogger.e(TAG, "WS Streams not available");
            return false;
        }

        return true;
    }

    private void stopStreamTransfer() {
        if (wsToDeviceRunner != null) {
            wsToDeviceRunner.cleanup();
            wsToDeviceRunner = null;
        }
        if (deviceToWSRunner != null) {
            deviceToWSRunner.cleanup();
            deviceToWSRunner = null;
        }
        isStreamRunnerActive.set(false);

        USBConnectionManager.getInstance().closeStreams();
        WSConnectionManager.getInstance().closeStreams();
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
            //recordExceptionToFirebase(e);
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

    @Subscribe
    public void onWSTrafficEvent(WSConnectionManager.WSTrafficEvent event) {

        boolean shouldRefresh = false;
        if (event.isSlowConnection()) {
            if (isWSTrafficSlow.compareAndSet(false, true)) {
                shouldRefresh = true;
                showToast("Bad Network Connection: " + event.getMessage() + "!", isWiFiConnected.get(), event.getMessage());
            }
        } else {
            if (isWSTrafficSlow.compareAndSet(true, false)) {
                shouldRefresh = true;
            }
        }
        if (shouldRefresh) {
            refresh();
        }
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
                DJILogger.i(TAG, finalMessage);
            }
        });
    }

    private boolean isMyServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                Log.i("isMyServiceRunning?", true + "");
                return true;
            }
        }
        Log.i("isMyServiceRunning?", false + "");
        return false;
    }

    //endregion
}
