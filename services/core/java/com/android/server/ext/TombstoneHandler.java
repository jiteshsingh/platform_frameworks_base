package com.android.server.ext;

import android.annotation.CurrentTimeMillisLong;
import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManagerInternal;
import android.ext.LogViewerApp;
import android.ext.SettingsIntents;
import android.ext.settings.ExtSettings;
import android.os.DropBoxManager;
import android.os.Process;
import android.os.TombstoneWithHeadersProto;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;
import android.util.proto.ProtoInputStream;

import com.android.internal.R;
import com.android.internal.os.Zygote;
import com.android.server.LocalServices;
import com.android.server.os.nano.TombstoneProtos;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

public class TombstoneHandler {
    private static final String TAG = TombstoneHandler.class.getSimpleName();

    private static final int SIGSEGV = 11;
    private static final int SEGV_MTEAERR = 8;
    private static final int SEGV_MTESERR = 9;

    public static final boolean isMemoryTaggingSupported = Zygote.nativeSupportsMemoryTagging();

    public static void handleNewFile(Context ctx, File protoFile) {
        long ts = System.currentTimeMillis();

        try {
            byte[] protoBytes = Files.readAllBytes(protoFile.toPath());

            handleTombstoneBytes(ctx, protoBytes, ts, false);
        } catch (Throwable e) {
            // catch everything to reduce the chance of getting into a crash loop
            Slog.e(TAG, "", e);
        }
    }

    static void handleDropBoxEntry(DropBoxMonitor dm, DropBoxManager.Entry entry) {
        try (InputStream eis = entry.getInputStream()) {
            if (eis == null) {
                Slog.d(TAG, "tombstone entry getInputStream() is null");
                return;
            }

            var pis = new ProtoInputStream(eis);

            byte[] tombstoneBytes = null;
            while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                if (pis.getFieldNumber() == (int) TombstoneWithHeadersProto.TOMBSTONE) {
                    tombstoneBytes = pis.readBytes(TombstoneWithHeadersProto.TOMBSTONE);
                    break;
                }
            }

            if (tombstoneBytes == null) {
                Slog.d(TAG, "tombstoneBytes is null");
                return;
            }

            handleTombstoneBytes(dm.context, tombstoneBytes, entry.getTimeMillis(), true);
        } catch (Throwable e) {
            // catch everything to reduce the chance of getting into a crash loop
            Slog.e(TAG, "", e);
        }
    }

    private static void handleTombstoneBytes(Context ctx, byte[] tombstoneBytes,
            @CurrentTimeMillisLong long timestamp, boolean isHistorical)
            throws IOException
    {
        var tombstone = TombstoneProtos.Tombstone.parseFrom(tombstoneBytes);

        var sb = new StringBuilder();
        sb.append("osVersion: ");
        sb.append(tombstone.buildFingerprint);
        sb.append("\nuid: ");
        sb.append(tombstone.uid);
        {
            String sl = tombstone.selinuxLabel;
            if (!TextUtils.isEmpty(sl)) {
                int len = sl.length();
                // selinuxLabel usually includes terminating NUL byte. Amending protobuf generation
                // could break its other users, add a workaround here instead
                if (sl.charAt(len - 1) == '\u0000') {
                    sl = sl.substring(0, len - 1);
                }
            }
            sb.append(" (");
            sb.append(sl);
        }
        sb.append(")\ncmdline:");
        for (String s : tombstone.commandLine) {
            sb.append(' ');
            sb.append(s);
        }
        sb.append("\nprocessUptime: ");
        sb.append(tombstone.processUptime);
        sb.append('s');

        if (!tombstone.abortMessage.isEmpty()) {
            sb.append("\n\nabortMessage: ");
            sb.append(tombstone.abortMessage);
        }

        TombstoneProtos.Signal signal = tombstone.signalInfo;
        if (signal != null) {
            sb.append("\n\nsignal: ");
            sb.append(signal.number);
            sb.append(" (");
            sb.append(signal.name);
            sb.append("), code ");
            sb.append(signal.code);
            sb.append(" (");
            sb.append(signal.codeName);
            sb.append(")");
            if (signal.hasSender) {
                sb.append(", senderUid ");
                sb.append(signal.senderUid);
            }
            if (signal.hasFaultAddress) {
                sb.append(", faultAddr ");
                sb.append(Long.toHexString(signal.faultAddress));
            }
        }

        for (TombstoneProtos.Cause cause : tombstone.causes) {
            sb.append("\ncause: ");
            sb.append(cause.humanReadable);
        }

        var threads = tombstone.threads;
        TombstoneProtos.Thread thread = null;
        if (threads != null) {
            thread = threads.get(tombstone.tid);
        }

        if (thread == null) {
            sb.append("\n\nno thread info");
        } else {
            sb.append("\nthreadName: ");
            sb.append(thread.name);

            if (isMemoryTaggingSupported) {
                sb.append("\nMTE: ");

                final long PR_MTE_TCF_SYNC = 1 << 1;
                final long PR_MTE_TCF_ASYNC = 1 << 2;

                long tac = thread.taggedAddrCtrl;
                if ((tac & (PR_MTE_TCF_SYNC | PR_MTE_TCF_ASYNC)) != 0) {
                    if ((tac & PR_MTE_TCF_ASYNC) != 0) {
                        sb.append("enabled");
                    } else {
                        sb.append("enabled; sync");
                    }
                } else {
                    sb.append("not enabled");
                }
            }

            sb.append("\n\nbacktrace:");

            for (TombstoneProtos.BacktraceFrame frame : thread.currentBacktrace) {
                sb.append("\n    ");
                sb.append(frame.fileName);
                sb.append(" (");
                if (!frame.functionName.isEmpty()) {
                    sb.append(frame.functionName);
                    sb.append('+');
                    sb.append(frame.functionOffset);
                    sb.append(", ");
                }
                sb.append("pc ");
                sb.append(Long.toHexString(frame.relPc));
                sb.append(')');
            }
        }

        String msg = sb.toString();

        String progName = "//no progName//";
        if (tombstone.commandLine.length > 0) {
            String path = tombstone.commandLine[0];
            progName = path.substring(path.lastIndexOf('/') + 1);
        }


        var pm = LocalServices.getService(PackageManagerInternal.class);

        int uid = tombstone.uid;

        boolean isAppUid = Process.isApplicationUid(uid);

        boolean shouldSkip = false;

        if (isAppUid || Process.isIsolatedUid(uid)) {
            int pid = tombstone.pid;

            String firstPackageName = null;
            int packageUid = 0;
            boolean isSystem = false;

            var ami = LocalServices.getService(ActivityManagerInternal.class);
            ActivityManagerInternal.ProcessRecordSnapshot prs = ami.getProcessRecordByPid(pid);
            if (prs != null) {
                ApplicationInfo appInfo = prs.appInfo;
                packageUid = appInfo.uid;
                firstPackageName = appInfo.packageName;
                isSystem = appInfo.isSystemApp();
            } else if (isAppUid) {
                int appId = UserHandle.getAppId(uid);
                List<AndroidPackage> appIdPkgs = pm.getPackagesForAppId(appId);
                if (appIdPkgs.size() == 1) {
                    var pkg = appIdPkgs.get(0);
                    String pkgName = pkg.getPackageName();
                    firstPackageName = pkgName;
                    packageUid = uid;

                    PackageState pkgState = pm.getPackageStateInternal(pkg.getPackageName());
                    if (pkgState != null) {
                        isSystem = pkgState.isSystem() || pkgState.isUpdatedSystemApp();
                    }
                }
            }

            if (firstPackageName == null) {
                Slog.d(TAG, "firstPackageName is null for uid " + packageUid);
                return;
            }

            if (!isSystem) {
                if (!isHistorical) {
                    int aswNotifType = -1;
                    if (isMemtagError(tombstone)) {
                        aswNotifType = ASW_NOTIF_TYPE_MEMTAG;
                    } else if (isHardenedMallocFatalError(tombstone)) {
                        aswNotifType = ASW_NOTIF_TYPE_HARDENED_MALLOC;
                    }
                    if (aswNotifType != -1) {
                        maybeShowAswNotification(aswNotifType, ctx, tombstone, msg,
                                packageUid, firstPackageName);
                    }
                }
                // rely on the standard crash dialog for non-memtag crashes
                return;
            }

            progName = firstPackageName;
        } else {
            switch (progName) {
                // bootanimation intentionally crashes in some cases
                case "bootanimation" -> {
                    shouldSkip = true;
                }
            }
        }

        final boolean showReportButton;

        if ("system_server".equals(progName)) {
            showReportButton = true;
        } else {
            boolean ignoreSetting = !isHistorical && isMemtagError(tombstone);
            showReportButton = ignoreSetting && !shouldSkip;

            if (shouldSkip || (!ignoreSetting && !ExtSettings.SHOW_SYSTEM_PROCESS_CRASH_NOTIFICATIONS.get(ctx))) {
                Slog.d(TAG, "skipped crash notification for " + progName + "; msg: " + msg);
                return;
            }
        }

        SystemJournalNotif.showCrash(ctx, progName, msg, timestamp, showReportButton);
    }

    private static boolean isMemtagError(TombstoneProtos.Tombstone t) {
        TombstoneProtos.Signal s = t.signalInfo;

        return isMemoryTaggingSupported && s != null && s.number == SIGSEGV
                && (s.code == SEGV_MTEAERR || s.code == SEGV_MTESERR);
    }

    private static boolean isHardenedMallocFatalError(TombstoneProtos.Tombstone t) {
        String abortMsg = t.abortMessage;
        return abortMsg != null && abortMsg.startsWith("hardened_malloc: fatal allocator error: ");
    }

    private static final int ASW_NOTIF_TYPE_MEMTAG = 0;
    private static final int ASW_NOTIF_TYPE_HARDENED_MALLOC = 1;

    private static void maybeShowAswNotification(int type, Context ctx, TombstoneProtos.Tombstone tombstone,
                                                 String errorReport,
                                                 int packageUid, String firstPackageName) {
        AppSwitchNotification n;
        switch (type) {
            case ASW_NOTIF_TYPE_MEMTAG -> {
                n = AppSwitchNotification.maybeCreate(ctx, firstPackageName, packageUid,
                        SettingsIntents.APP_MEMTAG);
                if (n == null) {
                    return;
                }
                n.titleRes = R.string.notif_memtag_crash_title;
                n.gosPsFlagSuppressNotif = GosPackageState.FLAG_FORCE_MEMTAG_SUPPRESS_NOTIF;
            }
            case ASW_NOTIF_TYPE_HARDENED_MALLOC -> {
                n = AppSwitchNotification.maybeCreate(ctx, firstPackageName, packageUid,
                        SettingsIntents.APP_HARDENED_MALLOC);
                if (n == null) {
                    return;
                }
                n.titleRes = R.string.notif_hmalloc_crash_title;
            }
            default -> throw new IllegalArgumentException(Integer.toString(type));
        }
        Intent i = LogViewerApp.createBaseErrorReportIntent(errorReport);
        i.putExtra(LogViewerApp.EXTRA_SOURCE_PACKAGE, firstPackageName);
        n.moreInfoIntent = i;

        n.maybeShow();
    }
}
