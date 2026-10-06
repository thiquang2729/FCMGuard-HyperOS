package com.reed.fcmguard;

import static org.junit.Assert.*;

import com.reed.fcmguard.backend.AppOpsOutput;
import com.reed.fcmguard.core.*;
import com.reed.fcmguard.guard.Whitelist;
import com.reed.fcmguard.model.*;
import java.util.Collections;
import org.junit.Test;

public class CorePolicyTest {
  @Test
  public void presentRequiredIsNoWritePlan() {
    String list = "com.foo,com.google.android.gms,com.foo";
    assertEquals(list, Whitelist.restore(list, null, "com.google.android.gms"));
  }

  @Test
  public void removedGmsRestoredAndOthersPreserved() {
    assertEquals(
        "com.foo,com.bar,com.google.android.gms",
        Whitelist.restore("com.foo,com.bar", null, "com.google.android.gms"));
  }

  @Test
  public void repairNormalizesDuplicates() {
    assertEquals(
        "com.foo,com.google.android.gms",
        Whitelist.restore("com.foo, com.foo,,", null, "com.google.android.gms"));
  }

  @Test
  public void emptyRecoversLastGood() {
    assertEquals(
        "com.foo,com.google.android.gms",
        Whitelist.restore("", "com.foo,com.google.android.gms", "com.google.android.gms"));
  }

  @Test
  public void unknownAppOpNeverMutated() {
    assertFalse(
        AppOpsOutput.restricted(AppOpsOutput.mode("0\nNo operations.", "RUN_IN_BACKGROUND")));
    assertEquals("unknown", AppOpsOutput.mode("1\nRUN_IN_BACKGROUND: deny", "RUN_IN_BACKGROUND"));
  }

  @Test
  public void explicitAppOpDetected() {
    assertEquals(
        "ignore", AppOpsOutput.mode("0\nRUN_IN_BACKGROUND: ignore; time=+1m", "RUN_IN_BACKGROUND"));
  }

  @Test
  public void uidOverridesAreNotPackageRepairCandidates() {
    assertEquals(
        "unknown",
        AppOpsOutput.mode(
            "0\nUid mode: RUN_IN_BACKGROUND: deny\nRUN_IN_BACKGROUND: allow", "RUN_IN_BACKGROUND"));
  }

  @Test
  public void cooldownSurvivesClockRollback() {
    assertTrue(RecoveryGate.cooling(50, 100));
    assertTrue(RecoveryGate.cooling(200, 100));
    assertFalse(RecoveryGate.cooling(RecoveryGate.COOLDOWN_MS + 100, 100));
  }

  @Test
  public void stopAfterThreeFailures() {
    assertFalse(RecoveryGate.stopped(2));
    assertTrue(RecoveryGate.stopped(3));
  }

  @Test
  public void scoreIsDeterministicAndUnknownNotHealthy() {
    ProtectionState gms =
        new ProtectionState(
            "gms",
            HealthState.OK,
            HealthState.OK,
            HealthState.UNSUPPORTED,
            HealthState.UNSUPPORTED,
            HealthState.OK,
            HealthState.UNSUPPORTED,
            RepairAction.NONE,
            "");
    HealthEngine.Report report =
        new HealthEngine()
            .assess(gms, HealthState.UNKNOWN, true, true, false, false, Collections.emptyList());
    assertEquals(95, report.score);
    assertEquals(HealthEngine.Overall.ATTENTION, report.state);
  }
}
