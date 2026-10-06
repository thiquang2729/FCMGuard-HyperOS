package com.reed.fcmguard;

import static org.junit.Assert.*;

import android.content.*;
import android.provider.Settings;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = SettingsGuardTest.SystemSettings.class)
public class SettingsGuardTest {
  @Implements(Settings.System.class)
  public static class SystemSettings {
    static Map<String, String> values = new HashMap<>();
    static int writes;
    static boolean allowed = true, rejected = false, lie = false;

    @Implementation
    protected static String getString(ContentResolver c, String key) {
      return values.get(key);
    }

    @Implementation
    protected static boolean canWrite(Context c) {
      return allowed;
    }

    @Implementation
    protected static boolean putString(ContentResolver c, String key, String value) {
      writes++;
      if (rejected) return false;
      if (!lie) values.put(key, value);
      return true;
    }
  }

  private Context context;

  @Before
  public void setup() {
    context = RuntimeEnvironment.getApplication();
    SystemSettings.values.clear();
    SystemSettings.writes = 0;
    SystemSettings.allowed = true;
    SystemSettings.rejected = false;
    SystemSettings.lie = false;
    context.getSharedPreferences(SettingsGuard.PREFS, 0).edit().clear().commit();
  }

  @Test
  public void existingGmsDoesNotWrite() {
    SystemSettings.values.put("MILLET_NO_RESTRICT_APP", "com.foo,com.google.android.gms");
    assertTrue(SettingsGuard.repair(context).success);
    assertEquals(0, SystemSettings.writes);
  }

  @Test
  public void actualRepairVerified() {
    SystemSettings.values.put("MILLET_NO_RESTRICT_APP", "com.foo,com.foo");
    SettingsGuard.Result result = SettingsGuard.repair(context);
    assertTrue(result.changed);
    assertEquals("com.foo,com.google.android.gms", result.value);
    assertEquals(1, SystemSettings.writes);
  }

  @Test
  public void failedWriteIsNotSuccess() {
    SystemSettings.rejected = true;
    assertFalse(SettingsGuard.repair(context).success);
  }

  @Test
  public void acceptedWriteWithoutReadBackIsNotSuccess() {
    SystemSettings.lie = true;
    assertFalse(SettingsGuard.repair(context).success);
    assertFalse(SettingsGuard.repair(context).changed);
  }

  @Test
  public void permissionDeniedDoesNotCrashOrWrite() {
    SystemSettings.allowed = false;
    assertFalse(SettingsGuard.repair(context).success);
    assertEquals(0, SystemSettings.writes);
  }

  @Test
  public void lastGoodSurvivesUnprotectedObservation() {
    SystemSettings.values.put("MILLET_NO_RESTRICT_APP", "com.foo,com.google.android.gms");
    SettingsGuard.rememberIfUseful(context);
    SystemSettings.values.put("MILLET_NO_RESTRICT_APP", "com.bar");
    SettingsGuard.rememberIfUseful(context);
    SystemSettings.values.remove("MILLET_NO_RESTRICT_APP");
    assertEquals("com.foo,com.google.android.gms", SettingsGuard.repair(context).value);
  }
}
