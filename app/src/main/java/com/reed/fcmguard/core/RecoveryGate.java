package com.reed.fcmguard.core;

/**
 * Persisted by the engine; no retry timer. State is reset only after observed success or by the
 * user.
 */
public final class RecoveryGate {
  public static final long COOLDOWN_MS = 5L * 60L * 1000L;
  public static final int MAX_FAILURES = 3;

  private RecoveryGate() {}

  public static boolean stopped(int failures) {
    return failures >= MAX_FAILURES;
  }

  public static boolean cooling(long now, long last) {
    return last > 0 && (now < last || now - last < COOLDOWN_MS);
  }
}
