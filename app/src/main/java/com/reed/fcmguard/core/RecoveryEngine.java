package com.reed.fcmguard.core;

import android.content.Context;
import android.content.SharedPreferences;
import com.reed.fcmguard.backend.*;
import com.reed.fcmguard.data.RepairHistoryRepository;
import com.reed.fcmguard.model.*;

/** Backend-independent diagnose -> minimum action -> execute -> independently verify -> record. */
public final class RecoveryEngine {
  private final SharedPreferences prefs;
  private final RepairHistoryRepository history;

  public RecoveryEngine(Context c) {
    prefs = c.getSharedPreferences("recovery_gate_v2", 0);
    history = new RepairHistoryRepository(c);
  }

  public synchronized RepairResult recover(String pkg, ProtectionBackend backend) {
    ProtectionState before = backend.inspect(pkg);
    RepairAction action = before.action;
    String key = pkg + ":" + action.name();
    long now = System.currentTimeMillis();
    if (action == RepairAction.NONE) {
      if (before.background == HealthState.OK)
        prefs.edit().remove(pkg + ":RESTORE_GMS:fail").apply();
      return result(
          pkg,
          backend,
          action,
          RepairResult.Outcome.NO_CHANGE,
          false,
          before,
          before,
          "No actionable cause");
    }
    int failures = prefs.getInt(key + ":fail", 0);
    if (RecoveryGate.stopped(failures))
      return result(
          pkg,
          backend,
          action,
          RepairResult.Outcome.STOPPED,
          false,
          before,
          before,
          "Automatic recovery stopped after 3 failures. Review settings, then reset recovery"
              + " limits.");
    if (RecoveryGate.cooling(now, prefs.getLong(key + ":last", 0)))
      return result(
          pkg,
          backend,
          action,
          RepairResult.Outcome.COOLDOWN,
          false,
          before,
          before,
          "Recovery cooldown");
    prefs.edit().putLong(key + ":last", now).apply();
    RepairResult written;
    try {
      written = backend.repair(pkg, action);
    } catch (RuntimeException failure) {
      written =
          result(
              pkg,
              backend,
              action,
              RepairResult.Outcome.FAILED,
              false,
              before,
              before,
              "Backend failure: " + failure.getClass().getSimpleName());
    }
    ProtectionState after = backend.inspect(pkg);
    boolean verified = written.success() && verifies(action, after);
    RepairResult.Outcome outcome =
        written.success() && !verified ? RepairResult.Outcome.FAILED : written.outcome;
    RepairResult finalResult =
        result(
            pkg,
            backend,
            action,
            outcome,
            verified && written.changed,
            before,
            after,
            verified
                ? written.message
                : written.success() ? "Verification failed" : written.message);
    if (outcome == RepairResult.Outcome.FAILED)
      prefs.edit().putInt(key + ":fail", failures + 1).apply();
    else if (verified) prefs.edit().remove(key + ":fail").apply();
    history.add(finalResult);
    return finalResult;
  }

  public static boolean verifies(RepairAction action, ProtectionState after) {
    if (after.installed != HealthState.OK) return false;
    if (action == RepairAction.RESTORE_GMS) return after.background == HealthState.OK;
    if (action == RepairAction.ALLOW_BACKGROUND || action == RepairAction.ALLOW_ANY_BACKGROUND)
      return after.action != action && after.background != HealthState.UNKNOWN;
    return false;
  }

  private RepairResult result(
      String pkg,
      ProtectionBackend b,
      RepairAction action,
      RepairResult.Outcome outcome,
      boolean changed,
      ProtectionState before,
      ProtectionState after,
      String detail) {
    return new RepairResult(
        pkg, action, b.name(), outcome, changed, before.summary(), after.summary(), detail);
  }

  public int stoppedCount() {
    int count = 0;
    for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet())
      if (entry.getKey().endsWith(":fail")
          && entry.getValue() instanceof Integer
          && RecoveryGate.stopped((Integer) entry.getValue())) count++;
    return count;
  }

  public synchronized void reset() {
    prefs.edit().clear().apply();
  }
}
