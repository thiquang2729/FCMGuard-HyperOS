package com.reed.fcmguard.model;

public final class ProtectedApp {
  public enum Level {
    NORMAL,
    STRONG,
    MAXIMUM
  }

  public final String packageName, displayName;
  public final Level level;
  public final long lastCheck, lastRepair;
  public final String lastState;

  public ProtectedApp(
      String pkg, String label, Level level, long check, long repair, String state) {
    packageName = pkg;
    displayName = label;
    this.level = level;
    lastCheck = check;
    lastRepair = repair;
    lastState = state;
  }
}
