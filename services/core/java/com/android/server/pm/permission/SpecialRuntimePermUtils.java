/*
 * Copyright (C) 2022 GrapheneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm.permission;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SrtPermissions;
import android.ext.settings.ExtSettings;
import android.os.Build;
import android.os.Bundle;
import android.util.LruCache;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.pm.pkg.component.ParsedUsesPermission;
import com.android.server.LocalServices;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;

import java.util.List;

public class SpecialRuntimePermUtils {
    private static final String TAG = "SpecialRuntimePermUtils";

    public static boolean isSpecialRuntimePermission(String permission) {
        switch (permission) {
            case Manifest.permission.INTERNET:
            case Manifest.permission.OTHER_SENSORS:
                return true;
        }
        return false;
    }

    public static boolean shouldAutoGrant(Context ctx, String packageName, int userId, String perm) {
        if (!isSpecialRuntimePermission(perm)) {
            return false;
        }

        if (Manifest.permission.OTHER_SENSORS.equals(perm)) {
            if (ActivityManager.getService() == null) {
                // a failsafe: should never happen
                Slog.d(TAG, "AMS is null");
                if (Build.isDebuggable()) {
                    throw new IllegalStateException();
                }
                return false;
            }

            var um = LocalServices.getService(UserManagerInternal.class);
            // use parent profile settings for work profile
            int userIdForSettings = um.getProfileParentId(userId);

            return ExtSettings.AUTO_GRANT_OTHER_SENSORS_PERMISSION.get(ctx, userIdForSettings);
        }

        return !isAutoGrantSkipped(packageName, userId, perm);
    }

    public static int getFlags(PackageManagerService pm, AndroidPackage pkg, PackageState pkgState, int userId) {
        int flags = 0;

        for (ParsedUsesPermission perm : pkg.getUsesPermissions()) {
            String name = perm.getName();
            switch (name) {
                case Manifest.permission.INTERNET:
                    if (shouldEnableInternetCompat(pkg, pkgState, userId)) {
                        flags |= SrtPermissions.FLAG_INTERNET_COMPAT_ENABLED;
                    }
                    continue;
                default:
                    continue;
            }
        }

        return flags;
    }

    private static boolean shouldEnableInternetCompat(AndroidPackage pkg, PackageState pkgState, int userId) {
        if (pkgState.isSystem() || pkgState.isUpdatedSystemApp()) {
            // system packages should be aware of runtime INTERNET permission
            return false;
        }

        Bundle metadata = pkg.getMetaData();
        if (metadata != null) {
            String key = Manifest.permission.INTERNET + ".mode";
            if ("runtime".equals(metadata.getString(key))) {
                // AndroidManifest has
                // <meta-data android:name="android.permission.INTERNET.mode" android:value="runtime" />
                // declaration inside the <application> element
                return false;
            }
        }

        var permManager = LocalServices.getService(PermissionManagerServiceInternal.class);
        // enable InternetCompat if package doesn't have the INTERNET permission
        return permManager.checkPermission(pkg.getPackageName(),
                Manifest.permission.INTERNET, Context.DEVICE_ID_DEFAULT, userId)
                != PackageManager.PERMISSION_GRANTED;
    }

    // Maps userIds to map of package names to permissions that should not be auto granted
    private static SparseArray<LruCache<String, List<String>>> skipAutoGrantsMap = new SparseArray<>();

    public static void skipAutoGrantsForPackage(String packageName, int userId, List<String> perms) {
        PackageStateInternal psi = LocalServices.getService(PackageManagerInternal.class).getPackageStateInternal(packageName);
        if (psi != null && psi.isSystem()) {
            return;
        }

        synchronized (skipAutoGrantsMap) {
            LruCache<String, List<String>> userMap = skipAutoGrantsMap.get(userId);
            if (userMap == null) {
                // 50 entries should be enough, only 1 is needed in vast majority of cases
                userMap = new LruCache<>(50);
                skipAutoGrantsMap.put(userId, userMap);
            }
            userMap.put(packageName, perms);
        }
    }

    private static boolean isAutoGrantSkipped(String packageName, int userId, String perm) {
        List<String> permList;
        synchronized (skipAutoGrantsMap) {
            LruCache<String, List<String>> userMap = skipAutoGrantsMap.get(userId);
            if (userMap == null) {
                return false;
            }
            permList = userMap.get(packageName);
        }
        if (permList == null) {
            return false;
        }
        return permList.contains(perm);
    }

    private SpecialRuntimePermUtils() {}
}
