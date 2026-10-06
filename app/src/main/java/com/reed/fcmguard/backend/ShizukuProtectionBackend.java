package com.reed.fcmguard.backend;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import com.reed.fcmguard.data.ProtectedAppRepository;
import com.reed.fcmguard.model.*;
import java.io.*;
import java.util.concurrent.*;
import rikka.shizuku.Shizuku;

/** All privileged operations live here, including the narrow Shizuku UserService. */
public final class ShizukuProtectionBackend implements ProtectionBackend {
  private final Context context;
  private final ProtectionBackend standard;
  private final ProtectedAppRepository apps;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Shizuku.UserServiceArgs args;
  private volatile IBackgroundPolicy remote;
  private volatile boolean binding;
  private volatile long bindStarted;
  // At most one blocked binder RPC: on timeout disable enhanced mode for this process.
  private volatile boolean timedOut;
  private final ExecutorService rpc =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "Shizuku RPC");
            t.setDaemon(true);
            return t;
          });
  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder binder) {
          remote = IBackgroundPolicy.Stub.asInterface(binder);
          binding = false;
        }

        public void onServiceDisconnected(ComponentName n) {
          remote = null;
          binding = false;
        }
      };
  private final Shizuku.OnBinderReceivedListener received = this::connect;
  private final Shizuku.OnBinderDeadListener dead =
      () -> {
        remote = null;
        binding = false;
      };
  private final Shizuku.OnRequestPermissionResultListener permission =
      (code, result) -> {
        if (result == PackageManager.PERMISSION_GRANTED) connect();
      };

  public ShizukuProtectionBackend(Context c, ProtectionBackend standard) {
    context = c.getApplicationContext();
    this.standard = standard;
    apps = new ProtectedAppRepository(context);
    args =
        Build.VERSION.SDK_INT >= 28
            ? new Shizuku.UserServiceArgs(new ComponentName(context, PolicyService.class))
                .daemon(false)
                .processNameSuffix("policy")
                .tag("fcmguard-policy-user-" + userId())
                .version(31)
            : null;
    if (Build.VERSION.SDK_INT < 28) return;
    try {
      Shizuku.addBinderReceivedListenerSticky(received);
      Shizuku.addBinderDeadListener(dead);
      Shizuku.addRequestPermissionResultListener(permission);
    } catch (RuntimeException | LinkageError unavailable) {
    }
  }

  public String name() {
    return "Shizuku";
  }

  public boolean enabled() {
    return context.getSharedPreferences("guard_state", 0).getBoolean("enhanced_v2", false);
  }

  public void setEnabled(boolean enabled) {
    context
        .getSharedPreferences("guard_state", 0)
        .edit()
        .putBoolean("enhanced_v2", enabled)
        .apply();
    if (enabled) connect();
    else disconnect();
  }

  public boolean permissionGranted() {
    if (Build.VERSION.SDK_INT < 28) return false;
    try {
      return Shizuku.pingBinder()
          && !Shizuku.isPreV11()
          && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    } catch (RuntimeException | LinkageError e) {
      return false;
    }
  }

  public boolean isAvailable() {
    return enabled()
        && Build.VERSION.SDK_INT >= 28
        && !timedOut
        && permissionGranted()
        && remote != null
        && remote.asBinder().isBinderAlive();
  }

  public String status() {
    if (Build.VERSION.SDK_INT < 28) return "Unsupported on this Android version; Standard active";
    try {
      if (!Shizuku.pingBinder()) return "Unavailable / not running; Standard active";
      if (!permissionGranted()) return "Permission not granted; Standard active";
      if (!enabled()) return "Permission granted; enhanced protection OFF";
      if (timedOut) return "Operation timed out; Standard active. Restart app to retry.";
      return isAvailable()
          ? "Connected · permission granted"
          : binding ? "Connecting; Standard active" : "Disconnected; Standard active";
    } catch (RuntimeException | LinkageError e) {
      return "Unavailable; Standard active";
    }
  }

  public void requestPermission() {
    if (Build.VERSION.SDK_INT < 28) return;
    try {
      if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(2002);
    } catch (RuntimeException | LinkageError ignored) {
    }
  }

  public void connect() {
    main.post(
        () -> {
          if (Build.VERSION.SDK_INT < 28 || !enabled() || !permissionGranted() || timedOut) return;
          if (remote != null && remote.asBinder().isBinderAlive()) return;
          if (binding && SystemClock.elapsedRealtime() - bindStarted < 10000) return;
          // Do not accumulate bindings if a previous connection never completed.
          if (binding) {
            disconnect();
            return;
          }
          try {
            binding = true;
            bindStarted = SystemClock.elapsedRealtime();
            Shizuku.bindUserService(args, connection);
          } catch (RuntimeException | LinkageError e) {
            binding = false;
            remote = null;
          }
        });
  }

  public void disconnect() {
    if (args == null) return;
    remote = null;
    binding = false;
    try {
      Shizuku.unbindUserService(args, connection, true);
    } catch (RuntimeException | LinkageError ignored) {
    }
  }

  private int userId() {
    return android.os.Process.myUid() / 100000;
  }

  private boolean selected(String pkg) {
    ProtectedApp app = apps.find(pkg);
    return app != null && app.level == ProtectedApp.Level.MAXIMUM;
  }

  private String call(String pkg, String op, boolean repair) {
    if (!isAvailable() || !selected(pkg)) return "unsupported";
    IBackgroundPolicy service = remote;
    Future<String> future = rpc.submit(() -> service.appOp(pkg, op, repair, userId()));
    try {
      return future.get(7, TimeUnit.SECONDS);
    } catch (TimeoutException timeout) {
      timedOut = true;
      remote = null;
      main.post(this::disconnect);
      future.cancel(true);
      return "timeout";
    } catch (Exception failure) {
      future.cancel(true);
      return "unavailable";
    }
  }

  public ProtectionState inspect(String pkg) {
    ProtectionState a = standard.inspect(pkg);
    if (!selected(pkg) || !isAvailable() || a.installed != HealthState.OK) return a;
    String first = AppOpsOutput.mode(call(pkg, "RUN_IN_BACKGROUND", false), "RUN_IN_BACKGROUND");
    String second =
        AppOpsOutput.mode(call(pkg, "RUN_ANY_IN_BACKGROUND", false), "RUN_ANY_IN_BACKGROUND");
    RepairAction action =
        AppOpsOutput.restricted(first)
            ? RepairAction.ALLOW_BACKGROUND
            : AppOpsOutput.restricted(second) ? RepairAction.ALLOW_ANY_BACKGROUND : a.action;
    HealthState background =
        AppOpsOutput.restricted(first) || AppOpsOutput.restricted(second)
            ? HealthState.WARNING
            : "allow".equals(first) && "allow".equals(second)
                ? HealthState.OK
                : HealthState.UNKNOWN;
    return new ProtectionState(
        pkg,
        a.installed,
        a.fcmCapability,
        a.notification,
        a.autostart,
        background,
        a.battery,
        action,
        "Background AppOps: "
            + first
            + " / "
            + second
            + ". Xiaomi autostart and vendor battery policy remain read-only.");
  }

  public RepairResult repair(String pkg, RepairAction action) {
    ProtectionState before = inspect(pkg);
    if (!selected(pkg) || !isAvailable())
      return result(
          pkg,
          action,
          before,
          before,
          RepairResult.Outcome.UNSUPPORTED,
          false,
          "Enhanced unavailable; Standard active");
    String op =
        action == RepairAction.ALLOW_BACKGROUND
            ? "RUN_IN_BACKGROUND"
            : action == RepairAction.ALLOW_ANY_BACKGROUND ? "RUN_ANY_IN_BACKGROUND" : null;
    if (op == null) return standard.repair(pkg, action);
    String mode = AppOpsOutput.mode(call(pkg, op, false), op);
    if (!AppOpsOutput.restricted(mode))
      return result(
          pkg,
          action,
          before,
          before,
          RepairResult.Outcome.UNKNOWN,
          false,
          "No verified explicit restriction; no write");
    String write = call(pkg, op, true);
    String afterMode = AppOpsOutput.mode(call(pkg, op, false), op);
    ProtectionState after = inspect(pkg);
    boolean verified = "0\nverified".equals(write) && "allow".equals(afterMode);
    return result(
        pkg,
        action,
        before,
        after,
        verified ? RepairResult.Outcome.SUCCESS : RepairResult.Outcome.FAILED,
        verified,
        verified
            ? op + " explicit restriction repaired and read back"
            : op + " repair not verified");
  }

  private RepairResult result(
      String pkg,
      RepairAction action,
      ProtectionState a,
      ProtectionState b,
      RepairResult.Outcome outcome,
      boolean changed,
      String detail) {
    return new RepairResult(
        pkg, action, name(), outcome, changed, a.summary(), b.summary(), detail);
  }

  /** Runs with shell identity supplied by Shizuku, not as an exported Android Service. */
  public static final class PolicyService extends IBackgroundPolicy.Stub {
    public PolicyService() {}

    public String appOp(String pkg, String op, boolean repair, int userId) {
      if (Build.VERSION.SDK_INT < 28
          || userId < 0
          || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
          || !("RUN_IN_BACKGROUND".equals(op) || "RUN_ANY_IN_BACKGROUND".equals(op)))
        return "unsupported";
      String before = run("get", pkg, op, null, userId);
      if (!repair) return before;
      // Re-inspect inside the privileged process immediately before the minimum write.
      if (!AppOpsOutput.restricted(AppOpsOutput.mode(before, op))) return "unverified";
      String write = run("set", pkg, op, "allow", userId);
      if (!write.startsWith("0\n")) return write;
      return "allow".equals(AppOpsOutput.mode(run("get", pkg, op, null, userId), op))
          ? "0\nverified"
          : "unverified";
    }

    private String run(String verb, String pkg, String op, String mode, int userId) {
      if (Build.VERSION.SDK_INT < 28) return "unsupported";
      java.util.List<String> command = new java.util.ArrayList<>();
      java.util.Collections.addAll(
          command, "/system/bin/cmd", "appops", verb, "--user", Integer.toString(userId), pkg, op);
      if (mode != null) command.add(mode);
      java.lang.Process process = null;
      try {
        process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final InputStream input = process.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread reader =
            new Thread(
                () -> {
                  try {
                    byte[] buffer = new byte[1024];
                    int n;
                    while ((n = input.read(buffer)) != -1)
                      if (bytes.size() < 8192)
                        bytes.write(buffer, 0, Math.min(n, 8192 - bytes.size()));
                  } catch (IOException ignored) {
                  }
                },
                "Policy output");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroy();
          return "timeout";
        }
        reader.join(500);
        if (reader.isAlive()) return "unavailable";
        return process.exitValue() + "\n" + bytes.toString("UTF-8");
      } catch (Exception failure) {
        return "unavailable";
      } finally {
        if (process != null) process.destroy();
      }
    }

    public void destroy() {
      System.exit(0);
    }
  }
}
