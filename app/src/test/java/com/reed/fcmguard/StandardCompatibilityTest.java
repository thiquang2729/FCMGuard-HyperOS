package com.reed.fcmguard;

import static org.junit.Assert.*;

import android.content.*;
import com.reed.fcmguard.backend.*;
import com.reed.fcmguard.data.*;
import com.reed.fcmguard.guard.*;
import com.reed.fcmguard.model.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StandardCompatibilityTest {
  @Test
  @Config(sdk = 21)
  public void oldAndroidUsesStandard() {
    Context c = RuntimeEnvironment.getApplication();
    ShizukuProtectionBackend s = new ShizukuProtectionBackend(c, new StandardProtectionBackend(c));
    s.setEnabled(true);
    s.requestPermission();
    s.disconnect();
    assertFalse(s.isAvailable());
    assertNotNull(s.status());
  }

  @Test
  public void noShizukuKeepsStandardAvailable() {
    Context c = RuntimeEnvironment.getApplication();
    ProtectionBackend b = new StandardProtectionBackend(c);
    ShizukuProtectionBackend s = new ShizukuProtectionBackend(c, b);
    assertFalse(s.isAvailable());
    assertTrue(b.isAvailable());
    assertNotNull(s.status());
    s.setEnabled(true);
    assertFalse(s.isAvailable());
  }

  @Test
  public void uninstalledSelectedAppIsFailed() {
    Context c = RuntimeEnvironment.getApplication();
    ProtectedAppRepository repo = new ProtectedAppRepository(c);
    repo.select("com.example.missing", "Missing app", ProtectedApp.Level.MAXIMUM);
    assertNotNull(repo.find("com.example.missing"));
    ProtectionState state = new AppGuard(c).inspect("com.example.missing");
    assertEquals(HealthState.FAILED, state.installed);
    repo.update(state, false);
    assertTrue(repo.find("com.example.missing").lastCheck > 0);
  }

  @Test
  public void historyBounded() {
    Context c = RuntimeEnvironment.getApplication();
    RepairHistoryRepository h = new RepairHistoryRepository(c);
    h.clear();
    for (int i = 0; i < 120; i++)
      h.add(
          new RepairResult(
              "gms",
              RepairAction.RESTORE_GMS,
              "Standard",
              RepairResult.Outcome.FAILED,
              false,
              "a",
              "b",
              "test"));
    assertEquals(100, h.all().length());
  }

  @Test
  public void bootDisabledDoesNotStartService() {
    Context c = RuntimeEnvironment.getApplication();
    SettingsGuard.setProtectionEnabled(c, false);
    new BootReceiver().onReceive(c, new Intent(Intent.ACTION_BOOT_COMPLETED));
    assertNull(Shadows.shadowOf((android.app.Application) c).getNextStartedService());
  }

  @Test
  public void bootEnabledResumesService() {
    Context c = RuntimeEnvironment.getApplication();
    SettingsGuard.setProtectionEnabled(c, true);
    new BootReceiver().onReceive(c, new Intent(Intent.ACTION_BOOT_COMPLETED));
    assertEquals(
        GuardService.class.getName(),
        Shadows.shadowOf((android.app.Application) c)
            .getNextStartedService()
            .getComponent()
            .getClassName());
  }

  @Test
  public void serviceRestartKeepsEnabledPreference() {
    Context c = RuntimeEnvironment.getApplication();
    SettingsGuard.setProtectionEnabled(c, false);
    org.robolectric.android.controller.ServiceController<GuardService> first =
        Robolectric.buildService(GuardService.class).create();
    assertTrue(GuardService.isRunning());
    first.get().onStartCommand(null, 0, 1);
    first.destroy();
    assertFalse(GuardService.isRunning());
    assertTrue(SettingsGuard.isProtectionEnabled(c));
    org.robolectric.android.controller.ServiceController<GuardService> second =
        Robolectric.buildService(GuardService.class).create();
    assertTrue(GuardService.isRunning());
    second.destroy();
  }

  @Test
  public void reconnectRequestRateLimited() {
    Context c = RuntimeEnvironment.getApplication();
    c.getSharedPreferences("transport_v2", 0).edit().clear().commit();
    RepairResult first = FcmReconnect.controlled(c);
    assertNotEquals(RepairResult.Outcome.SUCCESS, first.outcome);
    assertEquals(RepairResult.Outcome.COOLDOWN, FcmReconnect.controlled(c).outcome);
  }

  @Test
  public void offlineRepairDefersReconnect() {
    Context c = RuntimeEnvironment.getApplication();
    c.getSharedPreferences("transport_v2", 0).edit().clear().commit();
    TransportGuard t = new TransportGuard(c);
    assertEquals(HealthState.WARNING, t.inspect());
    assertEquals(RepairResult.Outcome.UNKNOWN, t.reconnect(true, false).outcome);
    assertTrue(t.pending());
  }

  @Test
  @Config(sdk = 21)
  public void homeCanLaunchWithoutShizukuOnOldAndroid() {
    org.robolectric.android.controller.ActivityController<com.reed.fcmguard.ui.DashboardActivity>
        a = Robolectric.buildActivity(com.reed.fcmguard.ui.DashboardActivity.class).create();
    assertNotNull(a.get().findViewById(android.R.id.content));
    a.destroy();
  }
}
