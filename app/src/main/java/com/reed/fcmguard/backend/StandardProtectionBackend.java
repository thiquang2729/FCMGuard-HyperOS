package com.reed.fcmguard.backend;

import android.content.Context;
import com.reed.fcmguard.SettingsGuard;
import com.reed.fcmguard.guard.AppGuard;
import com.reed.fcmguard.model.*;

public final class StandardProtectionBackend implements ProtectionBackend {
  public static final String GMS = "com.google.android.gms";
  private final Context context;
  private final AppGuard apps;

  public StandardProtectionBackend(Context c) {
    context = c.getApplicationContext();
    apps = new AppGuard(context);
  }

  public String name() {
    return "Standard";
  }

  public boolean isAvailable() {
    return true;
  }

  public ProtectionState inspect(String pkg) {
    ProtectionState a = apps.inspect(pkg);
    if (!GMS.equals(pkg)) return a;
    if (a.installed != HealthState.OK)
      return new ProtectionState(
          pkg,
          a.installed,
          HealthState.UNKNOWN,
          HealthState.UNSUPPORTED,
          HealthState.UNSUPPORTED,
          HealthState.UNKNOWN,
          HealthState.UNSUPPORTED,
          RepairAction.NONE,
          "Google Services unavailable; install/enable it before protecting FCM");
    String current = SettingsGuard.read(context);
    boolean present = SettingsGuard.hasRequiredItem(context, current);
    HealthState whitelist = present ? HealthState.OK : HealthState.WARNING;
    return new ProtectionState(
        pkg,
        a.installed,
        HealthState.OK,
        HealthState.UNSUPPORTED,
        HealthState.UNSUPPORTED,
        whitelist,
        HealthState.UNSUPPORTED,
        present ? RepairAction.NONE : RepairAction.RESTORE_GMS,
        present
            ? "Required item protected in configured whitelist"
            : "Required item missing; Modify settings permission required");
  }

  public RepairResult repair(String pkg, RepairAction action) {
    ProtectionState before = inspect(pkg);
    if (GMS.equals(pkg) && action == RepairAction.RESTORE_GMS) {
      try {
        SettingsGuard.Result write = SettingsGuard.repair(context);
        ProtectionState after = inspect(pkg);
        boolean verified = write.success && after.background == HealthState.OK;
        return new RepairResult(
            pkg,
            action,
            name(),
            verified
                ? (write.changed ? RepairResult.Outcome.SUCCESS : RepairResult.Outcome.NO_CHANGE)
                : RepairResult.Outcome.FAILED,
            verified && write.changed,
            before.summary(),
            after.summary(),
            write.message);
      } catch (RuntimeException failure) {
        return new RepairResult(
            pkg,
            action,
            name(),
            RepairResult.Outcome.FAILED,
            false,
            before.summary(),
            inspect(pkg).summary(),
            "Settings repair unavailable: " + failure.getClass().getSimpleName());
      }
    }
    return new RepairResult(
        pkg,
        action,
        name(),
        RepairResult.Outcome.NEED_USER_ACTION,
        false,
        before.summary(),
        before.summary(),
        "Standard mode cannot change this policy. Open app settings.");
  }
}
