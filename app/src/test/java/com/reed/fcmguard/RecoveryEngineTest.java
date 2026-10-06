package com.reed.fcmguard;

import static org.junit.Assert.*;

import android.content.Context;
import com.reed.fcmguard.backend.*;
import com.reed.fcmguard.core.*;
import com.reed.fcmguard.model.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RecoveryEngineTest {
  private Context context;
  private RecoveryEngine engine;

  static class Fake implements ProtectionBackend {
    boolean healthy = false, lie = false, fail = false;
    int writes;

    public String name() {
      return "Fake";
    }

    public boolean isAvailable() {
      return true;
    }

    public ProtectionState inspect(String pkg) {
      return new ProtectionState(
          pkg,
          HealthState.OK,
          HealthState.OK,
          HealthState.OK,
          HealthState.OK,
          healthy ? HealthState.OK : HealthState.WARNING,
          HealthState.OK,
          healthy ? RepairAction.NONE : RepairAction.RESTORE_GMS,
          "");
    }

    public RepairResult repair(String pkg, RepairAction action) {
      writes++;
      if (!lie && !fail) healthy = true;
      return new RepairResult(
          pkg,
          action,
          name(),
          fail ? RepairResult.Outcome.FAILED : RepairResult.Outcome.SUCCESS,
          true,
          "",
          "",
          "");
    }
  }

  @Before
  public void setup() {
    context = RuntimeEnvironment.getApplication();
    engine = new RecoveryEngine(context);
    engine.reset();
  }

  @Test
  public void healthyDoesNothing() {
    Fake f = new Fake();
    f.healthy = true;
    assertEquals(RepairResult.Outcome.NO_CHANGE, engine.recover("gms", f).outcome);
    assertEquals(0, f.writes);
  }

  @Test
  public void backendSuccessIsIndependentlyVerified() {
    Fake f = new Fake();
    f.lie = true;
    assertEquals(RepairResult.Outcome.FAILED, engine.recover("gms", f).outcome);
  }

  @Test
  public void realRepairSucceeds() {
    Fake f = new Fake();
    assertEquals(RepairResult.Outcome.SUCCESS, engine.recover("gms", f).outcome);
    assertTrue(f.healthy);
  }

  @Test
  public void failureCooldownPersistsAcrossEngineRestart() {
    Fake f = new Fake();
    f.fail = true;
    engine.recover("gms", f);
    engine = new RecoveryEngine(context);
    assertEquals(RepairResult.Outcome.COOLDOWN, engine.recover("gms", f).outcome);
    assertEquals(1, f.writes);
  }

  @Test
  public void repeatedFailureStops() {
    Fake f = new Fake();
    f.fail = true;
    for (int i = 0; i < 3; i++) {
      context
          .getSharedPreferences("recovery_gate_v2", 0)
          .edit()
          .remove("gms:RESTORE_GMS:last")
          .commit();
      engine.recover("gms", f);
    }
    assertEquals(RepairResult.Outcome.STOPPED, engine.recover("gms", f).outcome);
    assertEquals(3, f.writes);
  }
}
