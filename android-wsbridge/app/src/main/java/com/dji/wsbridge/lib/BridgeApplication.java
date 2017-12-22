package com.dji.wsbridge.lib;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.StrictMode;
import com.crashlytics.android.Crashlytics;
import com.dji.wsbridge.BuildConfig;
import com.github.moduth.blockcanary.BlockCanary;
import com.squareup.leakcanary.LeakCanary;
import com.squareup.otto.Bus;
import com.squareup.otto.ThreadEnforcer;
import io.fabric.sdk.android.Fabric;

public class BridgeApplication extends Application implements Application.ActivityLifecycleCallbacks {

    public static final String TAG = "APPLICATION";
    private static BridgeApplication instance;
    private final Bus eventBus = new Bus(ThreadEnforcer.ANY);

    @Override
    public void onCreate() {
        super.onCreate();
        Fabric.with(this, new Crashlytics());
        Utils.configureLogger();
        instance = this;
        //registerActivityLifecycleCallbacks(this);
        if (BuildConfig.DEBUG) {
            // Detect UI-Thread blockage
            //BlockCanary.install(this, new AppBlockCanaryContext()).start();
            //// Detect memory leakage
            //if (!LeakCanary.isInAnalyzerProcess(this)) {
            //    LeakCanary.install(this);
            //}
            // Detect thread violation
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectAll().penaltyDropBox().penaltyLog().build());
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll()
                                                                            .penaltyDropBox()
                                                                            .penaltyLog()
                                                                            .build());
        }
    }

    public static BridgeApplication getInstance() {
        return instance;
    }

    public Bus getBus() {
        return this.eventBus;
    }
    //region -------------------------------------- Activity Callbacks and Helpers ---------------------------------------------
    @Override
    public void onActivityCreated(Activity activity, Bundle bundle) {
        DJIRemoteLogger.v(TAG, activity.getLocalClassName() + " Created");
    }

    @Override
    public void onActivityResumed(Activity activity) {
        DJIRemoteLogger.v(TAG, activity.getLocalClassName() + " Resumed");
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        DJIRemoteLogger.v(TAG, activity.getLocalClassName() + " Destroyed");
    }

    @Override
    public void onActivityPaused(Activity activity) {
        DJIRemoteLogger.v(TAG, activity.getLocalClassName() + " Paused");
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {
        DJIRemoteLogger.v(TAG, activity.getLocalClassName() + " SaveInstance");
    }

    @Override
    public void onActivityStarted(Activity activity) {
        DJIRemoteLogger.v(TAG, activity.getLocalClassName() + " Started");
    }

    @Override
    public void onActivityStopped(Activity activity) {
        DJIRemoteLogger.v(TAG, activity.getLocalClassName() + " Stopped");
    }
    //endregion -----------------------------------------------------------------------------------------------------
}
