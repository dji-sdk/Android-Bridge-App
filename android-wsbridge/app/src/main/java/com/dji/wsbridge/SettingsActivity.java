package com.dji.wsbridge;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.androidnetworking.AndroidNetworking;
import com.androidnetworking.common.Priority;
import com.androidnetworking.error.ANError;
import com.androidnetworking.interfaces.StringRequestListener;
import com.dji.wsbridge.lib.BridgeUpdateService;
import com.dji.wsbridge.lib.Utils;

import org.json.JSONObject;

import java.io.File;

public class SettingsActivity extends Activity {
    SharedPreferences sharedPreferences;
    private Switch switchAutoUpdate;
    private Button buttonCheckUpdate;
    private Button buttonClose;
    private TextView textViewHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        initView();
    }

    private void initView() {
        switchAutoUpdate = (Switch) findViewById(R.id.switchAutoUpdate);
        buttonCheckUpdate = (Button) findViewById(R.id.btnCheckUpdate);
        buttonClose = (Button) findViewById(R.id.btnClose);

        textViewHint = (TextView) findViewById(R.id.tvHint);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(SettingsActivity.this);
        if (Utils.isRooted()) {
            switchAutoUpdate.setEnabled(true);

            if (sharedPreferences.contains(getResources().getString(R.string.preference_auto_update_key))) {
                switchAutoUpdate.setChecked(sharedPreferences.getBoolean(getResources().getString(R.string.preference_auto_update_key), false));
                if (sharedPreferences.getBoolean(getResources().getString(R.string.preference_auto_update_key), false)) {
                    textViewHint.setVisibility(View.VISIBLE);
                } else {
                    textViewHint.setVisibility(View.GONE);
                }
            }
        } else {
            switchAutoUpdate.setEnabled(false);
            textViewHint.setText(getResources().getString(R.string.auto_update_msg_root));
            textViewHint.setVisibility(View.VISIBLE);
        }


        switchAutoUpdate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                String msg = isChecked ? "Auto update switced on." : "Auto update switced off.";
                if (isChecked) {
                    textViewHint.setVisibility(View.VISIBLE);
                } else {
                    textViewHint.setVisibility(View.GONE);
                }

                Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_SHORT).show();
                sharedPreferences.edit().putBoolean(getResources().getString(R.string.preference_auto_update_key), isChecked).commit();
            }
        });
        buttonClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SettingsActivity.this.finish();
            }
        });
        buttonCheckUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AndroidNetworking.post(BridgeUpdateService.VERSION_CHECK_URL).setPriority(Priority.IMMEDIATE).build().getAsString(new StringRequestListener() {
                    @Override
                    public void onResponse(String response) {
                        try {
                            JSONObject jsonObject = new JSONObject(response);
                            if (jsonObject != null) {
                                String versionName = getApplicationContext().getPackageManager().getPackageInfo(getApplicationContext().getPackageName(), 0).versionName;
                                if (versionName != null) {

                                    if (Utils.compareVersionNames(versionName, jsonObject.getString("version")) == -1) {
                                        updateApp();
                                    } else {
                                        Toast.makeText(getApplicationContext(), "Application is already up to date.", Toast.LENGTH_LONG).show();

                                    }
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                    }

                    @Override
                    public void onError(ANError anError) {
                        Log.d("Sid", "error " + anError.getErrorCode() + anError.getErrorDetail());
                    }

                });


            }
        });
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
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(BridgeUpdateService.UPDATED_APP_URL));
        request.setDescription("Updating BridgeApp");
        request.setTitle("Update");

        //set destination
        request.setDestinationUri(uri);

        // get download service and enqueue file
        final DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
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
                    SettingsActivity.this.finish();

                }

                unregisterReceiver(this);

            }
        };
        //register receiver for when .apk download is compete
        registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }
}
