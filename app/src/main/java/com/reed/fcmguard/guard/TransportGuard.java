package com.reed.fcmguard.guard;

import android.content.*;
import android.net.*;
import android.os.Build;
import com.reed.fcmguard.FcmReconnect;
import com.reed.fcmguard.backend.StandardProtectionBackend;
import com.reed.fcmguard.data.RepairHistoryRepository;
import com.reed.fcmguard.model.*;

/** Public network health is a prerequisite, never proof of a live authenticated MCS connection. */
public final class TransportGuard {
  private final Context context;

  public TransportGuard(Context c) {
    context = c.getApplicationContext();
  }

  public HealthState inspect() {
    try {
      ConnectivityManager cm =
          (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (cm == null) return HealthState.UNKNOWN;
      if (Build.VERSION.SDK_INT >= 23) {
        Network network = cm.getActiveNetwork();
        if (network == null) return HealthState.WARNING;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) return HealthState.UNKNOWN;
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
          return HealthState.WARNING;
        return HealthState.UNKNOWN;
      }
      NetworkInfo network = cm.getActiveNetworkInfo();
      return network != null && network.isConnected() ? HealthState.UNKNOWN : HealthState.WARNING;
    } catch (RuntimeException unavailable) {
      return HealthState.UNKNOWN;
    }
  }

  public String detail() {
    return inspect() == HealthState.WARNING
        ? "Network unavailable / not validated. Reconnect cannot repair the network."
        : "Network prerequisites available or unreadable. MCS connection UNKNOWN; use GMS"
              + " diagnostics.";
  }

  public boolean pending() {
    return context.getSharedPreferences("transport_v2", 0).getBoolean("pending", false);
  }

  public RepairResult reconnect(boolean repaired, boolean manual) {
    if (!repaired && !manual)
      return new RepairResult(
          StandardProtectionBackend.GMS,
          RepairAction.RECONNECT,
          "Standard",
          RepairResult.Outcome.NO_CHANGE,
          false,
          "UNKNOWN",
          "UNKNOWN",
          "No justified reconnect condition");
    if (inspect() == HealthState.WARNING) {
      if (repaired)
        context.getSharedPreferences("transport_v2", 0).edit().putBoolean("pending", true).apply();
      return new RepairResult(
          StandardProtectionBackend.GMS,
          RepairAction.RECONNECT,
          "Standard",
          RepairResult.Outcome.UNKNOWN,
          false,
          "Network unavailable",
          "Network unavailable",
          "Reconnect deferred until network is available");
    }
    RepairResult result = FcmReconnect.controlled(context);
    if (result.changed)
      context.getSharedPreferences("transport_v2", 0).edit().remove("pending").apply();
    new RepairHistoryRepository(context).add(result);
    return result;
  }
}
