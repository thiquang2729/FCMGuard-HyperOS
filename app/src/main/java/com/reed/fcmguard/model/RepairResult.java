package com.reed.fcmguard.model;

public final class RepairResult {
  public enum Outcome {
    SUCCESS,
    NO_CHANGE,
    FAILED,
    UNKNOWN,
    UNSUPPORTED,
    NEED_USER_ACTION,
    COOLDOWN,
    STOPPED
  }

  public final String packageName, backend, message, before, after;
  public final RepairAction action;
  public final Outcome outcome;
  public final boolean changed;

  public RepairResult(
      String pkg,
      RepairAction action,
      String backend,
      Outcome outcome,
      boolean changed,
      String before,
      String after,
      String message) {
    this.packageName = pkg;
    this.action = action;
    this.backend = backend;
    this.outcome = outcome;
    this.changed = changed;
    this.before = before;
    this.after = after;
    this.message = message;
  }

  public boolean success() {
    return outcome == Outcome.SUCCESS || outcome == Outcome.NO_CHANGE;
  }
}
