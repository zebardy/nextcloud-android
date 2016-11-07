/**
 *   ownCloud Android client application
 *
 *   @author masensio
 *   @author David A. Velasco
 *   Copyright (C) 2015 ownCloud Inc.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License version 2,
 *   as published by the Free Software Foundation.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.owncloud.android;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.preference.PreferenceManager;

import com.owncloud.android.authentication.PassCodeManager;
import com.owncloud.android.datamodel.ThumbnailsCacheManager;
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory;
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory.Policy;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.services.observer.SyncedFolderObserverService;
import com.owncloud.android.ui.activity.Preferences;
import com.owncloud.android.ui.activity.WhatsNewActivity;
import com.owncloud.android.utils.ExceptionHandler;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;


/**
 * Main Application of the project
 * 
 * Contains methods to build the "static" strings. These strings were before constants in different
 * classes
 */
public class MainApp extends Application {

    private static final String TAG = MainApp.class.getSimpleName();

    private static final String AUTH_ON = "on";

    @SuppressWarnings("unused")
    private static final String POLICY_SINGLE_SESSION_PER_ACCOUNT = "single session per account";
    @SuppressWarnings("unused")
    private static final String POLICY_ALWAYS_NEW_CLIENT = "always new client";

    private static Context mContext;

    private static String storagePath;

    private static boolean mOnlyOnDevice = false;

    private static SyncedFolderObserverService mObserverService;
    private boolean mBound;


    @SuppressFBWarnings("ST")
    public void onCreate(){
        super.onCreate();
        MainApp.mContext = getApplicationContext();

        // Setup handler for uncaught exceptions.
        Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler(this));


        SharedPreferences appPrefs =
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        MainApp.storagePath = appPrefs.getString(Preferences.PreferenceKeys.STORAGE_PATH, Environment.
                getExternalStorageDirectory().getAbsolutePath());

        boolean isSamlAuth = AUTH_ON.equals(getString(R.string.auth_method_saml_web_sso));

        OwnCloudClientManagerFactory.setUserAgent(getUserAgent());
        if (isSamlAuth) {
            OwnCloudClientManagerFactory.setDefaultPolicy(Policy.SINGLE_SESSION_PER_ACCOUNT);
        } else {
            OwnCloudClientManagerFactory.setDefaultPolicy(Policy.ALWAYS_NEW_CLIENT);
        }

        // initialise thumbnails cache on background thread
        new ThumbnailsCacheManager.InitDiskCacheTask().execute();


        String dataFolder = getDataFolder() + getAppContext().getResources().getString(R.string.log_name);

        // Set folder for store logs
        Log_OC.setLogDataFolder(dataFolder);

        Log_OC.startLogging(MainApp.storagePath);
        Log_OC.d("Debug", "start logging");

        Log_OC.d("SyncedFolderObserverService", "Start service SyncedFolderObserverService");
        Intent i = new Intent(this, SyncedFolderObserverService.class);
        startService(i);
        bindService(i, syncedFolderObserverServiceConnection, Context.BIND_AUTO_CREATE);

        // register global protection with pass code
        registerActivityLifecycleCallbacks( new ActivityLifecycleCallbacks() {

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                Log_OC.d(activity.getClass().getSimpleName(),  "onCreate(Bundle) starting" );
                WhatsNewActivity.runIfNeeded(activity);
                PassCodeManager.getPassCodeManager().onActivityCreated(activity);
            }

            @Override
            public void onActivityStarted(Activity activity) {
                Log_OC.d(activity.getClass().getSimpleName(),  "onStart() starting" );
                PassCodeManager.getPassCodeManager().onActivityStarted(activity);
            }

            @Override
            public void onActivityResumed(Activity activity) {
                Log_OC.d(activity.getClass().getSimpleName(), "onResume() starting" );
            }

            @Override
            public void onActivityPaused(Activity activity) {
                Log_OC.d(activity.getClass().getSimpleName(), "onPause() ending");
            }

            @Override
            public void onActivityStopped(Activity activity) {
                Log_OC.d(activity.getClass().getSimpleName(), "onStop() ending" );
                WhatsNewActivity.runIfNeeded(activity);
                PassCodeManager.getPassCodeManager().onActivityStopped(activity);
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                Log_OC.d(activity.getClass().getSimpleName(), "onSaveInstanceState(Bundle) starting" );
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                Log_OC.d(activity.getClass().getSimpleName(), "onDestroy() ending" );
            }
        });
    }

    public static Context getAppContext() {
        return MainApp.mContext;
    }

    public static String getStoragePath(){
        return MainApp.storagePath;
    }

    public static void setStoragePath(String path){
        MainApp.storagePath = path;
    }

    // Methods to obtain Strings referring app_name
    //   From AccountAuthenticator 
    //   public static final String ACCOUNT_TYPE = "owncloud";    
    public static String getAccountType() {
        return getAppContext().getResources().getString(R.string.account_type);
    }

    // Non gradle build systems do not provide BuildConfig.VERSION_CODE
    // so we must fallback to this method :(
    public static int getVersionCode() {
        try {
            String thisPackageName = getAppContext().getPackageName();
            return getAppContext().getPackageManager().getPackageInfo(thisPackageName, 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    //  From AccountAuthenticator
    //  public static final String AUTHORITY = "org.owncloud";
    public static String getAuthority() {
        return getAppContext().getResources().getString(R.string.authority);
    }
    
    //  From AccountAuthenticator
    //  public static final String AUTH_TOKEN_TYPE = "org.owncloud";
    public static String getAuthTokenType() {
        return getAppContext().getResources().getString(R.string.authority);
    }
    
    //  From ProviderMeta 
    //  public static final String DB_FILE = "owncloud.db";
    public static String getDBFile() {
        return getAppContext().getResources().getString(R.string.db_file);
    }
    
    //  From ProviderMeta
    //  private final String mDatabaseName = "ownCloud";
    public static String getDBName() {
        return getAppContext().getResources().getString(R.string.db_name);
    }
     
    /**
     * name of data_folder, e.g., "owncloud"
     */
    public static String getDataFolder() {
        return getAppContext().getResources().getString(R.string.data_folder);
    }
    
    // log_name
    public static String getLogName() {
        return getAppContext().getResources().getString(R.string.log_name);
    }

    public static void showOnlyFilesOnDevice(boolean state){
        mOnlyOnDevice = state;
    }

    public static boolean isOnlyOnDevice(){
        return mOnlyOnDevice;
    }

    public static SyncedFolderObserverService getSyncedFolderObserverService() {
        return mObserverService;
    }

    // user agent
    public static String getUserAgent() {
        String appString = getAppContext().getResources().getString(R.string.user_agent);
        String packageName = getAppContext().getPackageName();
        String version = "";

        PackageInfo pInfo = null;
        try {
            pInfo = getAppContext().getPackageManager().getPackageInfo(packageName, 0);
            if (pInfo != null) {
                version = pInfo.versionName;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log_OC.e(TAG, "Trying to get packageName", e.getCause());
        }

        // Mozilla/5.0 (Android) ownCloud-android/1.7.0
        String userAgent = String.format(appString, version);

        return userAgent;
    }

    /** Defines callbacks for service binding, passed to bindService() */
    private ServiceConnection syncedFolderObserverServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            SyncedFolderObserverService.SyncedFolderObserverBinder binder = (SyncedFolderObserverService.SyncedFolderObserverBinder) service;
            mObserverService = binder.getService();
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

}
