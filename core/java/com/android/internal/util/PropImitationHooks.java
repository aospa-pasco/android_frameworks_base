/*
 * Copyright (C) 2022 Paranoid Android
 *           (C) 2023 ArrowOS
 *           (C) 2023 The LibreMobileOS Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import android.app.ActivityTaskManager;
import android.app.Application;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Binder;
import android.os.Process;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class PropImitationHooks {

    private static final String TAG = "PropImitationHooks";
    private static final boolean DEBUG = false;

    private static final String PACKAGE_ARCORE = "com.google.ar.core";
    private static final String PACKAGE_ASI = "com.google.android.as";
    private static final String PACKAGE_ASSISTANT = "com.google.android.apps.googleassistant";
    private static final String PACKAGE_FINSKY = "com.android.vending";
    private static final String PACKAGE_GBOARD = "com.google.android.inputmethod.latin";
    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PACKAGE_GPHOTOS = "com.google.android.apps.photos";
    private static final String PACKAGE_NETFLIX = "com.netflix.mediaclient";
    private static final String PACKAGE_SETUPWIZARD = "com.google.android.setupwizard";
    private static final String PACKAGE_SUBSCRIPTION_RED = "com.google.android.apps.subscriptions.red";
    private static final String PACKAGE_TURBO = "com.google.android.apps.turbo";
    private static final String PACKAGE_VELVET = "com.google.android.googlequicksearchbox";

    private static final String PROCESS_GMS_PERSISTENT = PACKAGE_GMS + ".persistent";
    private static final String PROCESS_GMS_UNSTABLE = PACKAGE_GMS + ".unstable";

    private static final String FEATURE_NEXUS_PRELOAD = "com.google.android.apps.photos.NEXUS_PRELOAD";

    private static final Map<String, String> sPixelEightProps = Map.of(
        "PRODUCT", "husky",
        "DEVICE", "husky",
        "HARDWARE", "husky",
        "MANUFACTURER", "Google",
        "BRAND", "google",
        "MODEL", "Pixel 8 Pro",
        "ID", "AP1A.240505.005",
        "FINGERPRINT", "google/husky/husky:14/AP1A.240505.005/11677807:user/release-keys"
    );

    private static final Map<String, String> sPixelXLProps = new HashMap<>();
    static {
        sPixelXLProps.put("BRAND", "google");
        sPixelXLProps.put("MANUFACTURER", "Google");
        sPixelXLProps.put("DEVICE", "marlin");
        sPixelXLProps.put("PRODUCT", "marlin");
        sPixelXLProps.put("MODEL", "Pixel XL");
        sPixelXLProps.put("FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
    }

    private static final String PROP_SECURITY_PATCH = "persist.sys.pihooks.security_patch";
    private static final String PROP_FIRST_API_LEVEL = "persist.sys.pihooks.first_api_level";

    private static final ComponentName GMS_ADD_ACCOUNT_ACTIVITY = ComponentName.unflattenFromString(
            "com.google.android.gms/.auth.uiflows.minutemaid.MinuteMaidActivity");

    private static final Set<String> sPixelFeatures = Set.of(
        "PIXEL_2017_PRELOAD",
        "PIXEL_2018_PRELOAD",
        "PIXEL_2019_MIDYEAR_PRELOAD",
        "PIXEL_2019_PRELOAD",
        "PIXEL_2020_EXPERIENCE",
        "PIXEL_2020_MIDYEAR_EXPERIENCE",
        "PIXEL_EXPERIENCE"
    );

    private static volatile String[] sCertifiedProps;
    private static volatile String sStockFp, sFinskyFp, sNetflixModel;

    private static volatile String sProcessName;
    private static volatile boolean sIsPixelDevice, sIsGms, sIsFinsky, sIsPhotos;

    public static void setProps(Context context) {
        final String packageName = context.getPackageName();
        final String processName = Application.getProcessName();

        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(processName)) {
            Log.e(TAG, "Null package or process name");
            return;
        }

        final Resources res = context.getResources();
        if (res == null) {
            Log.e(TAG, "Null resources");
            return;
        }

        sCertifiedProps = res.getStringArray(R.array.config_certifiedBuildProperties);
        sStockFp = res.getString(R.string.config_stockFingerprint);
        sFinskyFp = res.getString(R.string.config_finskyFingerprint);
        sNetflixModel = res.getString(R.string.config_netflixSpoofModel);

        sProcessName = processName;
        sIsPixelDevice = Build.MANUFACTURER.equals("Google") && Build.MODEL.contains("Pixel");
        sIsGms = packageName.equals(PACKAGE_GMS) && processName.equals(PROCESS_GMS_UNSTABLE);
        sIsFinsky = packageName.equals(PACKAGE_FINSKY);
        sIsPhotos = packageName.equals(PACKAGE_GPHOTOS);

        /* Set Certified Properties for GMSCore
         * Set Stock Fingerprint for ARCore
         * Set custom model for Netflix
         */
        if (sIsGms) {
            setCertifiedPropsForGms();
        } else if (!sFinskyFp.isEmpty() && sIsFinsky) {
            dlog("Setting certified fingerprint for: " + packageName);
            setPropValue("FINGERPRINT", sFinskyFp);
        } else if (!sStockFp.isEmpty() && packageName.equals(PACKAGE_ARCORE)) {
            dlog("Setting stock fingerprint for: " + packageName);
            setPropValue("FINGERPRINT", sStockFp);
        } else if ((packageName.equals(PACKAGE_ASSISTANT) || packageName.equals(PACKAGE_ASI)
                || packageName.equals(PACKAGE_GBOARD) || packageName.equals(PACKAGE_SETUPWIZARD)
                || packageName.equals(PACKAGE_SUBSCRIPTION_RED) || packageName.equals(PACKAGE_TURBO)
                || packageName.equals(PACKAGE_VELVET) || (packageName.equals(PACKAGE_GMS) && processName.equals(PROCESS_GMS_PERSISTENT)))) {
            dlog("Spoofing Pixel 8 Pro for: " + packageName + " process: " + processName);
            sPixelEightProps.forEach((k, v) -> setPropValue(k, v));
        } else if (!sNetflixModel.isEmpty() && packageName.equals(PACKAGE_NETFLIX)) {
            dlog("Setting model to " + sNetflixModel + " for Netflix");
            setPropValue("MODEL", sNetflixModel);
        } else if (sIsPhotos) {
            dlog("Spoofing Pixel XL for Google Photos");
            sPixelXLProps.forEach((k, v) -> setPropValue(k, v));
        }
    }

    private static void setPropValue(String key, String value) {
        try {
            dlog("Setting prop " + key + " to " + value.toString());
            Class clazz = Build.class;
            if (key.startsWith("VERSION.")) {
                clazz = Build.VERSION.class;
                key = key.substring(8);
            }
            Field field = clazz.getDeclaredField(key);
            field.setAccessible(true);
            // Cast the value to int if it's an integer field, otherwise string.
            field.set(null, field.getType().equals(Integer.TYPE) ? Integer.parseInt(value) : value);
            field.setAccessible(false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static void setCertifiedPropsForGms() {
        if (sCertifiedProps.length == 0) {
            dlog("Certified props are not set");
            return;
        }
        final boolean was = isGmsAddAccountActivityOnTop();
        final TaskStackListener taskStackListener = new TaskStackListener() {
            @Override
            public void onTaskStackChanged() {
                final boolean is = isGmsAddAccountActivityOnTop();
                if (is ^ was) {
                    dlog("GmsAddAccountActivityOnTop is:" + is + " was:" + was +
                            ", killing myself!"); // process will restart automatically later
                    Process.killProcess(Process.myPid());
                }
            }
        };
        if (!was) {
            dlog("Spoofing build for GMS");
            setCertifiedProps();
        } else {
            dlog("Skip spoofing build for GMS, because GmsAddAccountActivityOnTop");
        }
        try {
            ActivityTaskManager.getService().registerTaskStackListener(taskStackListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register task stack listener!", e);
        }
    }

    private static void setCertifiedProps() {
        for (String entry : sCertifiedProps) {
            // Each entry must be of the format FIELD:value
            final String[] fieldAndProp = entry.split(":", 2);
            if (fieldAndProp.length != 2) {
                Log.e(TAG, "Invalid entry in certified props: " + entry);
                continue;
            }
            setPropValue(fieldAndProp[0], fieldAndProp[1]);
        }
        setSystemProperty(PROP_SECURITY_PATCH, Build.VERSION.SECURITY_PATCH);
        setSystemProperty(PROP_FIRST_API_LEVEL,
                Integer.toString(Build.VERSION.DEVICE_INITIAL_SDK_INT));
    }

    private static void setSystemProperty(String name, String value) {
        try {
            SystemProperties.set(name, value);
            dlog("Set system prop " + name + "=" + value);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set system prop " + name + "=" + value, e);
        }
    }

    private static boolean isGmsAddAccountActivityOnTop() {
        try {
            final ActivityTaskManager.RootTaskInfo focusedTask =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            return focusedTask != null && focusedTask.topActivity != null
                    && focusedTask.topActivity.equals(GMS_ADD_ACCOUNT_ACTIVITY);
        } catch (Exception e) {
            Log.e(TAG, "Unable to get top activity!", e);
        }
        return false;
    }

    public static boolean shouldBypassTaskPermission(Context context) {
        // GMS doesn't have MANAGE_ACTIVITY_TASKS permission
        final int callingUid = Binder.getCallingUid();
        final int gmsUid;
        try {
            gmsUid = context.getPackageManager().getApplicationInfo(PACKAGE_GMS, 0).uid;
            dlog("shouldBypassTaskPermission: gmsUid:" + gmsUid + " callingUid:" + callingUid);
        } catch (Exception e) {
            Log.e(TAG, "shouldBypassTaskPermission: unable to get gms uid", e);
            return false;
        }
        return gmsUid == callingUid;
    }

    private static boolean isCallerSafetyNet() {
        return sIsGms && Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(elem -> elem.getClassName().contains("DroidGuard"));
    }

    public static void onEngineGetCertificateChain() {
        // Check stack for SafetyNet or Play Integrity
        if (isCallerSafetyNet() || sIsFinsky) {
            dlog("Blocked key attestation sIsGms=" + sIsGms + " sIsFinsky=" + sIsFinsky);
            throw new UnsupportedOperationException();
        }
    }

    public static boolean hasSystemFeature(String name, boolean has) {
        if (sIsPhotos) {
            if (has && sPixelFeatures.stream().anyMatch(name::contains)) {
                dlog("Blocked system feature " + name + " for Google Photos");
                has = false;
            } else if (!has && name.equalsIgnoreCase(FEATURE_NEXUS_PRELOAD)) {
                dlog("Enabled system feature " + name + " for Google Photos");
                has = true;
            }
        }
        return has;
    }

    public static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, "[" + sProcessName + "] " + msg);
    }
}
