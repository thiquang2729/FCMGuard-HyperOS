package com.reed.fcmguard.data;

import android.content.Context;
import android.content.SharedPreferences;
import com.reed.fcmguard.model.*;
import java.util.*;
import org.json.*;

/** User selections only. Uninstalled selections remain visible until explicitly removed. */
public final class ProtectedAppRepository {
  public static final int LIMIT = 64;
  private final SharedPreferences prefs;

  public ProtectedAppRepository(Context context) {
    prefs = context.getSharedPreferences("protected_apps_v2", 0);
  }

  public synchronized List<ProtectedApp> all() {
    List<ProtectedApp> out = new ArrayList<>();
    try {
      JSONArray list = new JSONArray(prefs.getString("apps", "[]"));
      for (int i = 0; i < Math.min(LIMIT, list.length()); i++) {
        JSONObject a = list.getJSONObject(i);
        try {
          out.add(
              new ProtectedApp(
                  a.getString("package"),
                  a.getString("name"),
                  ProtectedApp.Level.valueOf(a.optString("level", "STRONG")),
                  a.optLong("check"),
                  a.optLong("repair"),
                  a.optString("state", "Not checked")));
        } catch (IllegalArgumentException invalid) {
          /* Ignore corrupt individual entries. */
        }
      }
    } catch (JSONException corrupt) {
      /* Never crash Standard Mode on corrupt local state. */
    }
    return out;
  }

  public synchronized ProtectedApp find(String pkg) {
    for (ProtectedApp app : all()) if (app.packageName.equals(pkg)) return app;
    return null;
  }

  public synchronized void select(String pkg, String name, ProtectedApp.Level level) {
    if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
      throw new IllegalArgumentException("Invalid package");
    List<ProtectedApp> apps = all();
    ProtectedApp old = find(pkg);
    for (int i = apps.size() - 1; i >= 0; i--)
      if (apps.get(i).packageName.equals(pkg)) apps.remove(i);
    if (apps.size() >= LIMIT) throw new IllegalStateException("Maximum 64 protected apps");
    apps.add(
        new ProtectedApp(
            pkg,
            name,
            level,
            old == null ? 0 : old.lastCheck,
            old == null ? 0 : old.lastRepair,
            old == null ? "Not checked" : old.lastState));
    save(apps);
  }

  public synchronized void remove(String pkg) {
    List<ProtectedApp> apps = all();
    for (int i = apps.size() - 1; i >= 0; i--)
      if (apps.get(i).packageName.equals(pkg)) apps.remove(i);
    save(apps);
  }

  public synchronized void update(ProtectionState state, boolean repaired) {
    List<ProtectedApp> apps = all();
    for (int i = 0; i < apps.size(); i++) {
      ProtectedApp a = apps.get(i);
      if (a.packageName.equals(state.packageName))
        apps.set(
            i,
            new ProtectedApp(
                a.packageName,
                a.displayName,
                a.level,
                state.checkedAt,
                repaired ? state.checkedAt : a.lastRepair,
                state.health() + ": " + state.detail));
    }
    save(apps);
  }

  private void save(List<ProtectedApp> apps) {
    JSONArray list = new JSONArray();
    for (ProtectedApp a : apps)
      try {
        list.put(
            new JSONObject()
                .put("package", a.packageName)
                .put("name", a.displayName)
                .put("level", a.level.name())
                .put("check", a.lastCheck)
                .put("repair", a.lastRepair)
                .put("state", a.lastState));
      } catch (JSONException impossible) {
        throw new IllegalStateException(impossible);
      }
    String next = list.toString();
    if (!next.equals(prefs.getString("apps", "[]"))) prefs.edit().putString("apps", next).apply();
  }
}
