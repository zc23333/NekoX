/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;
import androidx.multidex.MultiDex;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ForegroundDetector;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Set;

import tw.nekomimi.nekogram.ExternalGcm;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.utils.EnvUtil;
import tw.nekomimi.nekogram.utils.FileUtil;
import tw.nekomimi.nekogram.utils.ProxyUtil;
import tw.nekomimi.nekogram.utils.UIUtil;

import static android.os.Build.VERSION.SDK_INT;

public class ApplicationLoader extends Application {

    @SuppressLint("StaticFieldLeak")
    public static volatile Context applicationContext;

    public static volatile NetworkInfo currentNetworkInfo;
    public static volatile boolean unableGetCurrentNetwork;
    public static volatile Handler applicationHandler;

    private static ConnectivityManager connectivityManager;
    private static volatile boolean applicationInited = false;

    public static volatile boolean isScreenOn = false;
    public static volatile boolean mainInterfacePaused = true;
    public static volatile boolean externalInterfacePaused = true;
    public static volatile boolean mainInterfacePausedStageQueue = true;
    public static volatile long mainInterfacePausedStageQueueTime;

    public static boolean hasPlayServices;

    @Override
    protected void attachBaseContext(Context base) {
        if (SDK_INT >= Build.VERSION_CODES.P) {
            Reflection.unseal(base);
        }
        super.attachBaseContext(base);
        if (SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            MultiDex.install(this);
        }
    }

    /**
     * @author weishu
     * @date 2018/6/7.
     */
    public static class Reflection {
        private static final String TAG = "Reflection";

        private static Object sVmRuntime;
        private static Method setHiddenApiExemptions;

        static {
            if (SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    Method forName = Class.class.getDeclaredMethod("forName", String.class);
                    Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);

                    Class<?> vmRuntimeClass = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
                    Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null);
                    setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "setHiddenApiExemptions", new Class[]{String[].class});
                    sVmRuntime = getRuntime.invoke(null);
                } catch (Throwable e) {
                    FileLog.e("reflect bootstrap failed:", e);
                }
            }

        }

        private static int UNKNOWN = -9999;

        private static final int ERROR_SET_APPLICATION_FAILED = -20;

        private static final int ERROR_EXEMPT_FAILED = -21;

        private static int unsealed = UNKNOWN;

        public static int unseal(Context context) {
            if (SDK_INT < 28) {
                // Below Android P, ignore
                return 0;
            }

            // try exempt API first.
            if (exemptAll()) {
                return 0;
            } else {
                return ERROR_EXEMPT_FAILED;
            }
        }

        /**
         * make the method exempted from hidden API check.
         *
         * @param method the method signature prefix.
         * @return true if success.
         */
        public static boolean exempt(String method) {
            return exempt(new String[]{method});
        }

        /**
         * make specific methods exempted from hidden API check.
         *
         * @param methods the method signature prefix, such as "Ldalvik/system", "Landroid" or even "L"
         * @return true if success
         */
        public static boolean exempt(String... methods) {
            if (sVmRuntime == null || setHiddenApiExemptions == null) {
                return false;
            }

            try {
                setHiddenApiExemptions.invoke(sVmRuntime, new Object[]{methods});
                return true;
            } catch (Throwable e) {
                return false;
            }
        }

        /**
         * Make all hidden API exempted.
         *
         * @return true if success.
         */
        public static boolean exemptAll() {
            return exempt(new String[]{"L"});
        }
    }

    @SuppressLint("SdCardPath")
    public static File getDataDirFixed() {
        try {
            File path = applicationContext.getFilesDir();
            if (path != null) {
                return path.getParentFile();
            }
        } catch (Exception ignored) {
        }
        try {
            ApplicationInfo info = applicationContext.getApplicationInfo();
            return new File(info.dataDir);
        } catch (Exception ignored) {
        }
        return new File("/data/data/" + BuildConfig.APPLICATION_ID + "/");
    }

    public static File getFilesDirFixed() {

        File filesDir = new File(getDataDirFixed(), "files");

        FileUtil.initDir(filesDir);

        return filesDir;

    }

    public static File getCacheDirFixed() {

        File filesDir = new File(getDataDirFixed(), "cache");

        FileUtil.initDir(filesDir);

        return filesDir;

    }

    public static void postInitApplication() {
        if (applicationInited) {
            return;
        }

        applicationInited = true;

        UIUtil.runOnIoDispatcher(() -> {

            try {
                LocaleController.getInstance();
            } catch (Exception e) {
                e.printStackTrace();
            }

        });

        try {
            connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            BroadcastReceiver networkStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                    } catch (Throwable ignore) {

                    }

                    boolean isSlow = isConnectionSlow();
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        ConnectionsManager.getInstance(a).checkConnection();
                        FileLoader.getInstance(a).onNetworkChanged(isSlow);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            ApplicationLoader.applicationContext.registerReceiver(networkStateReceiver, filter);
            //Utilities.globalQueue.postRunnable(ApplicationLoader::ensureCurrentNetworkGet);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            final BroadcastReceiver mReceiver = new ScreenReceiver();
            applicationContext.registerReceiver(mReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            isScreenOn = pm.isScreenOn();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("screen state = " + isScreenOn);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        SharedConfig.loadConfig();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final int finalA = a;
            Runnable initRunnable = () -> {
                UserConfig.getInstance(finalA).loadConfig();
                MessagesController.getInstance(finalA);
                if (finalA == 0) {
                    SharedConfig.pushStringStatus = "__FIREBASE_GENERATING_SINCE_" + ConnectionsManager.getInstance(finalA).getCurrentTime() + "__";
                } else {
                    ConnectionsManager.getInstance(finalA);
                }
                TLRPC.User user = UserConfig.getInstance(finalA).getCurrentUser();
                if (user != null) {
                    MessagesController.getInstance(finalA).putUser(user, true);
                    SendMessagesHelper.getInstance(finalA).checkUnsentMessages();
                }
            };
            if (finalA == UserConfig.selectedAccount) initRunnable.run();
            else UIUtil.runOnIoDispatcher(initRunnable);
        }

        if (ProxyUtil.isVPNEnabled()) {

            if (NekoConfig.disableProxyWhenVpnEnabled) {

                SharedConfig.setProxyEnable(false);

            }

        }

        ApplicationLoader app = (ApplicationLoader) ApplicationLoader.applicationContext;
        ExternalGcm.initPlayServices();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("app initied");
        }

        MediaController.getInstance();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final int finalA = a;
            Runnable initRunnable = () -> {
                ContactsController.getInstance(finalA).checkAppAccount();
                DownloadController.getInstance(finalA);
            };
            if (finalA == UserConfig.selectedAccount) initRunnable.run();
            else UIUtil.runOnIoDispatcher(initRunnable);
        }
    }

    public ApplicationLoader() {
        super();
    }

    @Override
    public void onCreate() {

        try {
            applicationContext = getApplicationContext();
        } catch (Throwable ignore) {
        }

        super.onCreate();

        if (applicationContext == null) {
            applicationContext = getApplicationContext();
        }

        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);
        ConnectionsManager.native_setJava(false);
        new ForegroundDetector(this);

        applicationHandler = new Handler(applicationContext.getMainLooper());

        org.osmdroid.config.Configuration.getInstance().setUserAgentValue("Telegram-FOSS ( NekoX ) " + BuildConfig.VERSION_NAME);
        org.osmdroid.config.Configuration.getInstance().setOsmdroidBasePath(new File(ApplicationLoader.applicationContext.getCacheDir(), "osmdroid"));

        startPushService();

        try {

            EnvUtil.doTest();

        } catch (Exception e) {

            FileLog.e("EnvUtil test Failed", e);

        }

    }

    public static void startPushService() {
        if (ExternalGcm.checkPlayServices() && (SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && isNotificationListenerEnabled())) {
            return;
        }
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        boolean enabled;
        if (preferences.contains("pushService")) {
            enabled = preferences.getBoolean("pushService", true);
            if (SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                if (!preferences.getBoolean("pushConnection", true)) return;
            }
        } else {
            enabled = MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("keepAliveService", true);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("pushService", enabled);
            editor.putBoolean("pushConnection", enabled);
            editor.apply();
            SharedPreferences preferencesCA = MessagesController.getNotificationsSettings(UserConfig.selectedAccount);
            SharedPreferences.Editor editorCA = preferencesCA.edit();
            editorCA.putBoolean("pushConnection", enabled);
            editorCA.putBoolean("pushService", enabled);
            editorCA.apply();
            ConnectionsManager.getInstance(UserConfig.selectedAccount).setPushConnectionEnabled(true);
        }
        if (enabled) {
            try {
                Log.d("TFOSS", "Starting push service...");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(new Intent(applicationContext, NotificationsService.class));
                } else {
                    applicationContext.startService(new Intent(applicationContext, NotificationsService.class));
                }
            } catch (Throwable e) {
                Log.d("TFOSS", "Failed to start push service");
            }
        } else {
            applicationContext.stopService(new Intent(applicationContext, NotificationsService.class));
            PendingIntent pintent = PendingIntent.getService(applicationContext, 0, new Intent(applicationContext, NotificationsService.class), 0);
            AlarmManager alarm = (AlarmManager) applicationContext.getSystemService(Context.ALARM_SERVICE);
            alarm.cancel(pintent);
        }
    }

    public static boolean isNotificationListenerEnabled() {
        Set<String> packageNames = NotificationManagerCompat.getEnabledListenerPackages(applicationContext);
        if (packageNames.contains(applicationContext.getPackageName())) {
            return true;
        }
        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            LocaleController.getInstance().onDeviceConfigurationChange(newConfig);
            AndroidUtilities.checkDisplaySize(applicationContext, newConfig);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
    private static void ensureCurrentNetworkGet() {
        if (currentNetworkInfo == null) {
            try {
                if (connectivityManager == null) {
                    connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                }
                currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                unableGetCurrentNetwork = false;
            } catch (Throwable ignore) {
                unableGetCurrentNetwork = true;
            }
        }
    }

    public static boolean isRoaming() {
        try {
            ensureCurrentNetworkGet();
            return currentNetworkInfo != null && currentNetworkInfo.isRoaming();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedOrConnectingToWiFi() {
        try {
            ensureCurrentNetworkGet();
            if (currentNetworkInfo != null && currentNetworkInfo.getType() == ) {
                NetworkInfo.State state = currentNetworkInfo.getState();
                if (state == NetworkInfo.State.CONNECTED || state == NetworkInfo.State.CONNECTING || state == NetworkInfo.State.SUSPENDED) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedToWiFi() {
        try {
            ensureCurrentNetworkGet();
            if (currentNetworkInfo != null && currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI && currentNetworkInfo.getState() == NetworkInfo.State.CONNECTED) {
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectionSlow() {
        try {
            ensureCurrentNetworkGet();
            if (currentNetworkInfo != null && currentNetworkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                switch (currentNetworkInfo.getSubtype()) {
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_IDEN:
                        return true;
                }
            }
        } catch (Throwable ignore) {

        }
        return false;
    }

    public static boolean isNetworkOnline() {
        try {
            ensureCurrentNetworkGet();
            if (!unableGetCurrentNetwork && currentNetworkInfo == null) {
                return false;
            }
            if (currentNetworkInfo.isConnectedOrConnecting() || currentNetworkInfo.isAvailable()) {
                return true;
            }

            NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }
    */

    public static boolean isRoaming() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connectivityManager.getActiveNetworkInfo();
            if (netInfo != null) {
                return netInfo.isRoaming();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedOrConnectingToWiFi() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            NetworkInfo.State state = netInfo.getState();
            if (netInfo != null && (state == NetworkInfo.State.CONNECTED || state == NetworkInfo.State.CONNECTING || state == NetworkInfo.State.SUSPENDED)) {
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static int getAutodownloadNetworkType() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (netInfo != null) {
                if (netInfo.getState() == NetworkInfo.State.CONNECTED) {
                    if (connectivityManager.isActiveNetworkMetered()) {
                        return StatsController.TYPE_MOBILE;
                    } else {
                        return StatsController.TYPE_WIFI;
                    }
                }
            }
            netInfo = connectivityManager.getActiveNetworkInfo();
            if (netInfo != null && netInfo.isRoaming()) {
                return StatsController.TYPE_ROAMING;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return StatsController.TYPE_MOBILE;
    }

    public static boolean isConnectedToWiFi() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (netInfo != null && netInfo.getState() == NetworkInfo.State.CONNECTED) {
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static int getCurrentNetworkType() {
        if (isConnectedOrConnectingToWiFi()) {
            return StatsController.TYPE_WIFI;
        } else if (isRoaming()) {
            return StatsController.TYPE_ROAMING;
        } else {
            return StatsController.TYPE_MOBILE;
        }
    }

    public static boolean isConnectionSlow() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connectivityManager.getActiveNetworkInfo();
            if (netInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                switch (netInfo.getSubtype()) {
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_IDEN:
                        return true;
                }
            }
        } catch (Throwable ignore) {

        }
        return false;
    }

    public static boolean isNetworkOnline() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connectivityManager.getActiveNetworkInfo();
            if (netInfo != null && (netInfo.isConnectedOrConnecting() || netInfo.isAvailable())) {
                return true;
            }

            netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }
}
