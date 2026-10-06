package com.reed.fcmguard.core;

import com.reed.fcmguard.model.*;
import java.util.*;

/**
 * Transparent configuration score, not a delivery success percentage. Unknowns reduce confidence.
 */
public final class HealthEngine {
  public enum Overall {
    HEALTHY,
    ATTENTION,
    DEGRADED,
    CRITICAL
  }

  public static final class Report {
    public final int score;
    public final Overall state;
    public final String detail;

    public Report(int score, Overall state, String detail) {
      this.score = score;
      this.state = state;
      this.detail = detail;
    }
  }

  public Report assess(
      ProtectionState gms,
      HealthState transport,
      boolean service,
      boolean enabled,
      boolean enhanced,
      boolean shizuku,
      List<ProtectionState> apps) {
    int score = 100;
    if (gms.installed == HealthState.FAILED) score -= 45;
    else if (gms.background != HealthState.OK) score -= 35;
    if (transport == HealthState.WARNING) score -= 20;
    else if (transport != HealthState.OK) score -= 5;
    if (!enabled || !service) score -= 15;
    if (enhanced && !shizuku) score -= 5;
    int penalty = 0;
    boolean unknown = transport != HealthState.OK;
    for (ProtectionState a : apps) {
      if (a.health() == HealthState.FAILED) penalty += 10;
      else if (a.health() == HealthState.WARNING) penalty += 5;
      else if (a.health() == HealthState.UNKNOWN) {
        penalty += 2;
        unknown = true;
      }
    }
    score = Math.max(0, score - Math.min(20, penalty));
    Overall state =
        gms.installed == HealthState.FAILED
            ? Overall.CRITICAL
            : score < 50
                ? Overall.CRITICAL
                : score < 75
                    ? Overall.DEGRADED
                    : score < 95 || unknown ? Overall.ATTENTION : Overall.HEALTHY;
    return new Report(
        score,
        state,
        "Configuration score. Unknown transport is not Connected. Unknown vendor policies reduce"
            + " confidence.");
  }
}
