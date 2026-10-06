package com.reed.fcmguard.model;

/** Observed policy state, never a claim of successful notification delivery. */
public final class ProtectionState {
  public final String packageName;
  public final HealthState installed, fcmCapability, notification, autostart, background, battery;
  public final RepairAction action;
  public final String detail;
  public final long checkedAt;

  public ProtectionState(
      String pkg,
      HealthState installed,
      HealthState fcm,
      HealthState notification,
      HealthState autostart,
      HealthState background,
      HealthState battery,
      RepairAction action,
      String detail) {
    this.packageName = pkg;
    this.installed = installed;
    this.fcmCapability = fcm;
    this.notification = notification;
    this.autostart = autostart;
    this.background = background;
    this.battery = battery;
    this.action = action;
    this.detail = detail;
    checkedAt = System.currentTimeMillis();
  }

  public HealthState health() {
    HealthState result = HealthState.OK;
    for (HealthState state :
        new HealthState[] {installed, notification, autostart, background, battery}) {
      if (state == HealthState.FAILED) return HealthState.FAILED;
      if (state == HealthState.WARNING) result = HealthState.WARNING;
      else if ((state == HealthState.UNKNOWN || state == HealthState.UNSUPPORTED)
          && result == HealthState.OK) result = HealthState.UNKNOWN;
    }
    return result;
  }

  public String summary() {
    return "installed="
        + installed
        + "; fcm="
        + fcmCapability
        + "; notification="
        + notification
        + "; autostart="
        + autostart
        + "; background="
        + background
        + "; battery="
        + battery;
  }
}
