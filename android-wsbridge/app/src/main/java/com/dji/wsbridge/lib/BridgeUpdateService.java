package com.dji.wsbridge.lib;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.androidnetworking.AndroidNetworking;
import com.androidnetworking.error.ANError;
import com.androidnetworking.interfaces.StringRequestListener;
import com.dji.wsbridge.BridgeActivity;
import com.dji.wsbridge.BuildConfig;
import com.dji.wsbridge.R;

import org.json.JSONObject;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by sidd on 3/20/18.
 */

public class BridgeUpdateService extends Service {

    public static final String UPDATED_APP_URL = BuildConfig.BASE_URL + "/bridgeapp.apk";
    public static final String VERSION_CHECK_URL = BuildConfig.BASE_URL + "/getBridgeVersion.php";

    //get url of app on server
    private static final String SYSTEM_PACKAGE_NAME = "android";
    private int counter = 0;
    private Handler myHandler;
    private Timer timer;
    private TimerTask timerTask;

    SharedPreferences sharedPreferences;

    public BridgeUpdateService() {
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        myHandler = new Handler();
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        startTimer();
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public void startTimer() {
        //set a new Timer
        timer = new Timer();

        //initialize the TimerTask's job
        initializeTimerTask();

        //schedule the timer, to wake up every 1 HOUR
        timer.schedule(timerTask, 0, 60 * 60 * 1000); //
    }

    /**
     * it sets the timer to print the counter every x seconds
     */
    public void initializeTimerTask() {
        timerTask = new TimerTask() {
            public void run() {
                myHandler.post(new checkVersionRunnable());
            }
        };
    }

    /**
     * not needed
     */
    public void stoptimertask() {
        //stop the timer, if it's not already null
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void updateApp() {

        String fileName = "bridgeapp.apk";
        final String destination = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/" + fileName;
        final Uri uri = Uri.parse("file://" + destination);

        //Delete update file if exists
        File file = new File(destination);
        if (file.exists())
            file.delete();


        //set downloadmanager
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(UPDATED_APP_URL));
        request.setDescription("Updating BridgeApp");
        request.setTitle("Update");

        //set destination
        request.setDestinationUri(uri);

        // get download service and enqueue file
        final DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        final long downloadId = manager.enqueue(request);

        //set BroadcastReceiver to install app when .apk is downloaded
        BroadcastReceiver onComplete = new BroadcastReceiver() {
            public void onReceive(Context ctxt, Intent intent) {
                if (sharedPreferences.contains(getResources().getString(R.string.preference_auto_update_key))
                        && sharedPreferences.getBoolean(getResources().getString(R.string.preference_auto_update_key), false)) {
                    try {
                        Intent afterUpdateIntent = new Intent(getApplicationContext(), BridgeActivity.class);
                        intent.setAction(Intent.ACTION_MAIN);
                        intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(), 0, afterUpdateIntent, 0);

                        final long DELAY_IN_MILLIS = 1000 * 2 + System.currentTimeMillis();
                        AlarmManager alarmManager = (AlarmManager)
                                getSystemService(Activity.ALARM_SERVICE);
                        alarmManager.set(AlarmManager.RTC, DELAY_IN_MILLIS, pi);
                        String command;
                        command = "pm install -r " + destination;
                        Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
                        proc.waitFor();
                        pi.send();

                    } catch (Exception e) {
                        e.printStackTrace();
                    }


                } else {
                    sharedPreferences.edit().putString(getResources().getString(R.string.preference_update_uri), uri.toString()).commit();
                    sharedPreferences.edit().putString(getResources().getString(R.string.preference_update_mimetype), manager.getMimeTypeForDownloadedFile(downloadId)).commit();
                    Intent updateAvailableIntent = new Intent(getResources().getString(R.string.intent_filter_update_available));
                    sendBroadcast(updateAvailableIntent);

                }

                unregisterReceiver(this);

            }
        };
        //register receiver for when .apk download is compete
        registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private class checkVersionRunnable implements Runnable {

        @Override
        public void run() {
            AndroidNetworking.post(VERSION_CHECK_URL).build().getAsString(new StringRequestListener() {
                @Override
                public void onResponse(String response) {

                    try {
                        JSONObject jsonObject = new JSONObject(response);
                        if (jsonObject != null) {
                            String versionName = getApplicationContext().getPackageManager().getPackageInfo(getApplicationContext().getPackageName(), 0).versionName;
                            if (versionName != null) {
                                if (Utils.compareVersionNames(versionName, jsonObject.getString("version")) == -1) {
                                    Runtime.getRuntime().exec("dpm set-device-owner com.dji.wsbridge/.DeviceAdminRcvr");
                                    updateApp();
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                }

                @Override
                public void onError(ANError anError) {
                    Log.d("BridgeUpdateService", "error " + anError.getErrorCode() + anError.getErrorDetail());

                }

            });

        }
    }

}

