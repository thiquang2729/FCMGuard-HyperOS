package com.reed.fcmguard.core;

import android.content.Context;
import com.reed.fcmguard.*;
import com.reed.fcmguard.backend.*;
import com.reed.fcmguard.data.*;
import com.reed.fcmguard.guard.*;
import com.reed.fcmguard.model.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * One worker shared by Activities and Service: no main-thread shell calls or parallel repair loops.
 */
public final class ProtectionEngine {
  private static ProtectionEngine instance;

  public static synchronized ProtectionEngine get(Context c) {
    if (instance == null) instance = new ProtectionEngine(c);
    return instance;
  }

  public final ExecutorService worker = Executors.newSingleThreadExecutor();
  public final ProtectedAppRepository apps;
  public final ShizukuProtectionBackend shizuku;
  public final RecoveryEngine recovery;
  public final TransportGuard transport;
  public final GmsGuard gms;
  private final Context context;
  private final ProtectionBackend standard;
  private volatile Snapshot latest;

  public static final class Snapshot {
    public final ProtectionState gms;
    public final HealthState transport;
    public final List<ProtectionState> apps;
    public final HealthEngine.Report health;
    public final RepairResult lastResult;

    Snapshot(
        ProtectionState gms,
        HealthState transport,
        List<ProtectionState> apps,
        HealthEngine.Report health,
        RepairResult result) {
      this.gms = gms;
      this.transport = transport;
      this.apps = Collections.unmodifiableList(apps);
      this.health = health;
      lastResult = result;
    }
  }

  private ProtectionEngine(Context c) {
    context = c.getApplicationContext();
    apps = new ProtectedAppRepository(context);
    standard = new StandardProtectionBackend(context);
    shizuku = new ShizukuProtectionBackend(context, standard);
    recovery = new RecoveryEngine(context);
    transport = new TransportGuard(context);
    gms = new GmsGuard(standard);
  }

  public Snapshot latest() {
    return latest;
  }

  public ProtectionBackend backend(ProtectedApp app) {
    return app.level == ProtectedApp.Level.MAXIMUM && shizuku.isAvailable() ? shizuku : standard;
  }

  /** Run only on worker. Inspect-only is the Home full-check action. */
  public synchronized Snapshot check(boolean repair, boolean manualReconnect) {
    RepairResult last = null;
    if (repair) {
      last = recovery.recover(StandardProtectionBackend.GMS, standard);
      if (last.changed && last.success()) {
        RepairResult kick = transport.reconnect(true, false);
        if (kick.outcome != RepairResult.Outcome.NO_CHANGE) last = kick;
      }
    }
    if (repair && transport.pending()) last = transport.reconnect(true, false);
    if (manualReconnect) last = transport.reconnect(false, true);
    List<ProtectionState> states = new ArrayList<>();
    for (ProtectedApp app : apps.all()) {
      ProtectionBackend b = backend(app);
      if (repair && app.level != ProtectedApp.Level.NORMAL) {
        RepairResult result = recovery.recover(app.packageName, b);
        if (result.outcome != RepairResult.Outcome.NO_CHANGE
            && result.outcome != RepairResult.Outcome.COOLDOWN) last = result;
        ProtectionState state = b.inspect(app.packageName);
        apps.update(state, result.changed && result.success());
        states.add(state);
      } else {
        ProtectionState state = b.inspect(app.packageName);
        apps.update(state, false);
        states.add(state);
      }
    }
    ProtectionState gmsState = gms.inspect();
    HealthState transportState = transport.inspect();
    HealthEngine.Report report =
        new HealthEngine()
            .assess(
                gmsState,
                transportState,
                GuardService.isRunning(),
                SettingsGuard.isProtectionEnabled(context),
                shizuku.enabled(),
                shizuku.isAvailable(),
                states);
    latest = new Snapshot(gmsState, transportState, states, report, last);
    return latest;
  }

  public synchronized RepairResult repairApp(String pkg) {
    ProtectedApp app = apps.find(pkg);
    if (app == null)
      return new RepairResult(
          pkg,
          RepairAction.NONE,
          "Standard",
          RepairResult.Outcome.UNSUPPORTED,
          false,
          "",
          "",
          "Select this app first");
    RepairResult result = recovery.recover(pkg, backend(app));
    apps.update(backend(app).inspect(pkg), result.changed && result.success());
    return result;
  }

  public void resetLimits() {
    recovery.reset();
    context.getSharedPreferences("transport_v2", 0).edit().clear().apply();
  }
}
