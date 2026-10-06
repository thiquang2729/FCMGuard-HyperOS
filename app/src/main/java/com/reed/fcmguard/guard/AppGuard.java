package com.reed.fcmguard.guard;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import android.os.PowerManager;
import com.reed.fcmguard.AutostartStatusReader;
import com.reed.fcmguard.model.*;

public final class AppGuard {
  private final Context context;

  public AppGuard(Context c) {
    context = c.getApplicationContext();
  }

  public ProtectionState inspect(String pkg) {
    PackageManager pm = context.getPackageManager();
    try {
      ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
      if (!info.enabled) return missing(pkg, "App is disabled");
      HealthState fcm = HealthState.UNKNOWN;
      try {
        if (!pm.queryIntentServices(
                    new Intent("com.google.firebase.MESSAGING_EVENT").setPackage(pkg), 0)
                .isEmpty()
            || !pm.queryBroadcastReceivers(
                    new Intent("com.google.android.c2dm.intent.RECEIVE").setPackage(pkg), 0)
                .isEmpty()) fcm = HealthState.OK;
      } catch (RuntimeException ignored) {
      }
      HealthState notification = HealthState.UNKNOWN;
      if (Build.VERSION.SDK_INT >= 33)
        notification =
            pm.checkPermission(Manifest.permission.POST_NOTIFICATIONS, pkg)
                    == PackageManager.PERMISSION_GRANTED
                ? HealthState.OK
                : HealthState.WARNING;
      else
        try {
          AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
          int mode = ops.checkOpNoThrow("android:post_notification", info.uid, pkg);
          if (mode == AppOpsManager.MODE_ALLOWED) notification = HealthState.OK;
          else if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED)
            notification = HealthState.WARNING;
        } catch (RuntimeException unavailable) {
        }
      AutostartStatusReader.Status auto = AutostartStatusReader.check(context, pkg);
      HealthState autostart =
          auto == AutostartStatusReader.Status.ENABLED
              ? HealthState.OK
              : auto == AutostartStatusReader.Status.UNKNOWN
                  ? HealthState.UNKNOWN
                  : HealthState.WARNING;
      HealthState battery = HealthState.UNKNOWN;
      if (Build.VERSION.SDK_INT >= 23) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power != null && power.isIgnoringBatteryOptimizations(pkg)) battery = HealthState.OK;
      }
      // Not exempt is NOT evidence of a background restriction. Vendor policy remains unknown.
      RepairAction action =
          (notification == HealthState.WARNING || autostart == HealthState.WARNING)
              ? RepairAction.OPEN_SETTINGS
              : RepairAction.NONE;
      return new ProtectionState(
          pkg,
          HealthState.OK,
          fcm,
          notification,
          autostart,
          HealthState.UNKNOWN,
          battery,
          action,
          "FCM capability is a manifest signal; transport and vendor battery policy are not proven."
              + " "
              + (action == RepairAction.OPEN_SETTINGS ? "Review app settings." : ""));
    } catch (PackageManager.NameNotFoundException missing) {
      return missing(pkg, "App is uninstalled or not visible");
    } catch (RuntimeException blocked) {
      return new ProtectionState(
          pkg,
          HealthState.UNKNOWN,
          HealthState.UNKNOWN,
          HealthState.UNKNOWN,
          HealthState.UNKNOWN,
          HealthState.UNKNOWN,
          HealthState.UNKNOWN,
          RepairAction.NONE,
          "Inspection unavailable");
    }
  }

  private ProtectionState missing(String pkg, String detail) {
    return new ProtectionState(
        pkg,
        HealthState.FAILED,
        HealthState.UNKNOWN,
        HealthState.UNKNOWN,
        HealthState.UNKNOWN,
        HealthState.UNKNOWN,
        HealthState.UNKNOWN,
        RepairAction.NONE,
        detail);
  }
}
