package com.reed.fcmguard.data;

import android.content.Context;
import android.content.SharedPreferences;
import com.reed.fcmguard.model.RepairResult;
import org.json.*;

public final class RepairHistoryRepository {
  public static final int LIMIT = 100;
  private final SharedPreferences prefs;

  public RepairHistoryRepository(Context c) {
    prefs = c.getSharedPreferences("repair_history_v2", 0);
  }

  public synchronized JSONArray all() {
    try {
      return new JSONArray(prefs.getString("records", "[]"));
    } catch (JSONException e) {
      return new JSONArray();
    }
  }

  public synchronized void add(RepairResult result) {
    if (result.outcome == RepairResult.Outcome.NO_CHANGE
        || result.outcome == RepairResult.Outcome.COOLDOWN) return;
    JSONArray out = new JSONArray();
    try {
      out.put(
          new JSONObject()
              .put("timestamp", System.currentTimeMillis())
              .put("package", result.packageName)
              .put("problem", result.message)
              .put("action", result.action.name())
              .put("backend", result.backend)
              .put("before", result.before)
              .put("after", result.after)
              .put("result", result.outcome.name()));
    } catch (JSONException e) {
      return;
    }
    JSONArray previous = all();
    for (int i = 0; i < Math.min(LIMIT - 1, previous.length()); i++) out.put(previous.opt(i));
    prefs.edit().putString("records", out.toString()).apply();
  }

  public synchronized void clear() {
    prefs.edit().remove("records").apply();
  }
}
