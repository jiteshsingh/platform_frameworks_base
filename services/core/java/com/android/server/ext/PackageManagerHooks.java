package com.android.server.ext;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppBindArgs;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.ext.PackageId;
import android.location.HookedLocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.app.ContactScopes;
import com.android.server.pm.GosPackageStatePmHooks;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.ext.PackageExt;
import com.android.server.pm.ext.PackageHooks;
import com.android.server.pm.permission.SpecialRuntimePermUtils;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.GosPackageStatePm;
import com.android.server.pm.pkg.PackageStateInternal;

public class PackageManagerHooks {

    // Called when package enabled setting for a system package is deserialized from storage
    @Nullable
    public static Integer maybeOverrideSystemPackageEnabledSetting(String pkgName, @UserIdInt int userId) {
        switch (pkgName) {
            case PackageId.EUICC_SUPPORT_PIXEL_NAME:
                if (userId == UserHandle.USER_SYSTEM) {
                    // EuiccSupportPixel handles firmware updates of embedded secure element that is
                    // used for eSIM, NFC, Felica, etc and should always be enabled.
                    // It was previously unconditionally disabled after reboot.
                    return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
                } else {
                    // one of the previous OS versions enabled EuiccSupportPixel in all users
                    return PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                }
            default:
                return null;
        }
    }

    public static boolean shouldBlockGrantRuntimePermission(
            PackageManagerInternal pm, String permName, String packageName, int userId)
    {
        if (ContactScopes.getSpoofablePermissionDflag(permName) != 0) {
            GosPackageStatePm gosPs = pm.getGosPackageState(packageName, userId);
            if (gosPs != null && gosPs.hasFlags(GosPackageState.FLAG_CONTACT_SCOPES_ENABLED)) {
                String msg = "refusing to grant " + permName + " to " + packageName +
                        ": Contact Scopes is enabled";
                Slog.d("PermissionManager", msg);
                return true;
            }
        }

        return false;
    }

    @Nullable
    public static Bundle getExtraAppBindArgs(PackageManagerService pm, String packageName) {
        final int callingUid = Binder.getCallingUid();
        final int appId = UserHandle.getAppId(callingUid);
        final int userId = UserHandle.getUserId(callingUid);

        PackageStateInternal pkgState = pm.snapshotComputer().getPackageStateInternal(packageName);
        if (pkgState == null) {
            return null;
        }

        if (pkgState.getAppId() != appId) {
            return null;
        }

        AndroidPackage pkg = pkgState.getPkg();

        if (pkg == null) {
            return null;
        }

        // isSystem() remains true even if isUpdatedSystemApp() is true
        final boolean isUserApp = !pkgState.isSystem();

        GosPackageState gosPs = GosPackageStatePmHooks.get(pm, callingUid, packageName, userId);

        int[] flagsArr = new int[AppBindArgs.FLAGS_ARRAY_LEN];
        flagsArr[AppBindArgs.FLAGS_IDX_SPECIAL_RUNTIME_PERMISSIONS] =
                SpecialRuntimePermUtils.getFlags(pm, pkg, pkgState, userId);

        flagsArr[AppBindArgs.FLAGS_IDX_HOOKED_LOCATION_MANAGER] =
                HookedLocationManager.getFlags(gosPs, isUserApp);

        var b = new Bundle();
        b.putParcelable(AppBindArgs.KEY_GOS_PACKAGE_STATE, gosPs);
        b.putIntArray(AppBindArgs.KEY_FLAGS_ARRAY, flagsArr);

        return b;
    }

    // Called when AppsFilter decides whether to restrict package visibility
    public static boolean shouldFilterApplication(
            @Nullable PackageStateInternal callingPkgSetting,
            ArraySet<PackageStateInternal> callingSharedPkgSettings,
            int callingUserId,
            PackageStateInternal targetPkgSetting, int targetUserId
    ) {
        if (callingPkgSetting != null && restrictedVisibilityPackages.contains(callingPkgSetting.getPackageName())) {
            if (!targetPkgSetting.isSystem()) {
                return true;
            }
        }

        if (restrictedVisibilityPackages.contains(targetPkgSetting.getPackageName())) {
            if (callingPkgSetting != null) {
                return !callingPkgSetting.isSystem();
            } else {
                for (int i = callingSharedPkgSettings.size() - 1; i >= 0; i--) {
                    if (!callingSharedPkgSettings.valueAt(i).isSystem()) {
                        return true;
                    }
                }
            }
        }

        if (PackageHooks.shouldBlockAppsFilterVisibility(
                callingPkgSetting, callingSharedPkgSettings, callingUserId,
                targetPkgSetting, targetUserId)
        ) {
            return true;
        }

        if (callingPkgSetting != null && isPlayStoreFrontend(callingPkgSetting.getPackageName())) {
            AndroidPackage pkg = targetPkgSetting.getPkg();
            if (pkg != null) {
                switch (PackageExt.get(pkg).getPackageId()) {
                    case PackageId.GSF:
                    case PackageId.GMS_CORE:
                    case PackageId.PLAY_STORE:
                    case PackageId.EUICC_SUPPORT_PIXEL:
                    case PackageId.G_EUICC_LPA:
                    case PackageId.PIXEL_CAMERA_SERVICES:
                    case PackageId.ANDROID_AUTO:
                        return true;
                }
            }
        }

        return false;
    }

    private static boolean isPlayStoreFrontend(String pkg) {
        switch (pkg) {
            case "com.aurora.store":
            case "com.aurora.store.nightly":
                return true;
        }
        return false;
    }

    // Packages in this array are restricted from interacting with and being interacted by non-system apps
    private static final ArraySet<String> restrictedVisibilityPackages = new ArraySet<>(new String[] {
    });
}
