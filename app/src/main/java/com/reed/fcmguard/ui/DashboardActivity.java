package com.reed.fcmguard.ui;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.reed.fcmguard.*;
import com.reed.fcmguard.core.*;
import com.reed.fcmguard.data.RepairHistoryRepository;
import com.reed.fcmguard.model.*;
import java.util.*;
import org.json.*;

/** Small native screens; all inspection/repair work stays on the engine worker. */
public final class DashboardActivity extends Activity {
  private ProtectionEngine engine;
  private LinearLayout content;
  private String page = "Home", selected;
  private boolean alive, busy;
  private final Handler main = new Handler(Looper.getMainLooper());

  @Override
  protected void attachBaseContext(Context c) {
    super.attachBaseContext(ThemeHelper.apply(LocaleHelper.apply(c)));
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    engine = ProtectionEngine.get(this);
    alive = true;
    if (saved != null) {
      page = saved.getString("page", "Home");
      selected = saved.getString("selected");
    }
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(getResources().getColor(R.color.bg));
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(18), dp(20), dp(18), dp(28));
    scroll.addView(content);
    setContentView(scroll);
    render();
  }

  @Override
  protected void onResume() {
    super.onResume();
    engine.shizuku.connect();
    check(false, false);
  }

  @Override
  protected void onDestroy() {
    alive = false;
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putString("page", page);
    out.putString("selected", selected);
  }

  @Override
  public void onBackPressed() {
    if (!"Home".equals(page)) {
      page = "Home";
      render();
    } else super.onBackPressed();
  }

  private void navigate(String next) {
    page = next;
    render();
  }

  private void check(boolean repair, boolean reconnect) {
    if (busy) return;
    busy = true;
    render();
    engine.worker.execute(
        () -> {
          String error = null;
          try {
            engine.check(repair, reconnect);
          } catch (RuntimeException failure) {
            error = "Check unavailable: " + failure.getClass().getSimpleName();
          }
          final String message = error;
          main.post(
              () -> {
                if (!alive) return;
                busy = false;
                render();
                if (message != null) toast(message);
              });
        });
  }

  private void render() {
    content.removeAllViews();
    title("FCM GUARD 2.0");
    TextView header = text(page, 24, true);
    header.setPadding(0, dp(8), 0, dp(12));
    if (!"Home".equals(page)) button("← Home", () -> navigate("Home"));
    if (busy) text("Checking…", 14, false);
    switch (page) {
      case "Protected Apps":
        apps();
        break;
      case "App Detail":
        detail();
        break;
      case "Diagnostics":
        diagnostics();
        break;
      case "Repair History":
        history();
        break;
      case "Settings":
        settings();
        break;
      default:
        home();
    }
  }

  private void home() {
    ProtectionEngine.Snapshot snapshot = engine.latest();
    if (snapshot == null) {
      text("Run a full check to inspect protection.", 16, false);
    } else {
      title(snapshot.health.state + " · " + snapshot.health.score + " / 100");
      text(snapshot.health.detail, 12, false);
      text("Google Services: " + snapshot.gms.background, 16, true);
      text("FCM Transport: " + snapshot.transport + " (not a delivery test)", 15, false);
      text(
          "Guard Service: "
              + (GuardService.isRunning() ? "Running" : "Stopped")
              + " · auto protection "
              + (SettingsGuard.isProtectionEnabled(this) ? "ON" : "OFF"),
          15,
          false);
      text("Shizuku: " + engine.shizuku.status(), 15, false);
      int healthy = 0;
      for (ProtectionState state : snapshot.apps) if (state.health() == HealthState.OK) healthy++;
      text(
          "Protected Apps: " + healthy + " / " + snapshot.apps.size() + " policies verified OK",
          15,
          false);
    }
    if (engine.recovery.stoppedCount() > 0)
      text(
          "Recovery paused after repeated failures. Review settings and reset recovery limits.",
          15,
          true);
    if (snapshot != null && snapshot.lastResult != null)
      text(
          "Last check: " + snapshot.lastResult.outcome + " · " + snapshot.lastResult.message,
          13,
          false);
    JSONArray records = new RepairHistoryRepository(this).all();
    if (records.length() > 0) {
      JSONObject last = records.optJSONObject(0);
      if (last != null) {
        title("Last recovery");
        text(
            last.optString("package")
                + " · "
                + last.optString("result")
                + "\n"
                + last.optString("problem")
                + "\n"
                + time(last.optLong("timestamp")),
            13,
            false);
      }
    }
    button("Run Full Check", () -> check(false, false));
    button("Repair detected problems", () -> check(true, false));
    button("Protected Apps", () -> navigate("Protected Apps"));
    button("Diagnostics", () -> navigate("Diagnostics"));
    button("Repair History", () -> navigate("Repair History"));
    button("Settings", () -> navigate("Settings"));
  }

  private void apps() {
    text(
        "Only apps you select are monitored. MAXIMUM falls back to STRONG when Shizuku is"
            + " unavailable.",
        14,
        false);
    for (ProtectedApp app : engine.apps.all()) {
      title(app.displayName);
      text(app.packageName + "\n" + app.level + " · " + app.lastState, 13, false);
      button(
          "View / protect " + app.displayName,
          () -> {
            selected = app.packageName;
            navigate("App Detail");
          });
    }
    if (engine.apps.all().isEmpty()) text("No selected apps", 16, false);
    button("Add installed app", this::chooseApp);
  }

  private void chooseApp() {
    if (busy) return;
    busy = true;
    render();
    engine.worker.execute(
        () -> {
          List<String> packages = new ArrayList<>(), names = new ArrayList<>();
          try {
            PackageManager pm = getPackageManager();
            List<ResolveInfo> list =
                pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            Set<String> seen = new HashSet<>();
            Set<String> fcm = new HashSet<>();
            for (FcmAppScanner.AppEntry a : FcmAppScanner.scan(this)) fcm.add(a.packageName);
            Collections.sort(
                list,
                (a, b) ->
                    a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
            for (ResolveInfo item : list) {
              String pkg = item.activityInfo.packageName;
              if (pkg.equals(getPackageName())
                  || pkg.equals("com.google.android.gms")
                  || !seen.add(pkg)) continue;
              packages.add(pkg);
              names.add(
                  item.loadLabel(pm)
                      + " · "
                      + (fcm.contains(pkg) ? "FCM detected" : "FCM unknown"));
            }
          } catch (RuntimeException unavailable) {
          }
          main.post(
              () -> {
                if (!alive) return;
                busy = false;
                render();
                if (packages.isEmpty()) {
                  toast("No visible installed apps");
                  return;
                }
                new AlertDialog.Builder(this)
                    .setTitle("Select app")
                    .setItems(
                        names.toArray(new String[0]),
                        (dialog, i) -> {
                          String pkg = packages.get(i);
                          try {
                            String label =
                                getPackageManager()
                                    .getApplicationLabel(
                                        getPackageManager().getApplicationInfo(pkg, 0))
                                    .toString();
                            engine.apps.select(pkg, label, ProtectedApp.Level.STRONG);
                            selected = pkg;
                            navigate("App Detail");
                            check(false, false);
                          } catch (Exception failure) {
                            toast(failure.getMessage());
                          }
                        })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
              });
        });
  }

  private ProtectionState selectedState() {
    ProtectionEngine.Snapshot snapshot = engine.latest();
    if (snapshot != null)
      for (ProtectionState state : snapshot.apps)
        if (state.packageName.equals(selected)) return state;
    return null;
  }

  private void detail() {
    ProtectedApp app = engine.apps.find(selected == null ? "" : selected);
    if (app == null) {
      text("App is no longer selected", 16, false);
      return;
    }
    title(app.displayName);
    text(app.packageName, 12, false);
    String effective =
        app.level == ProtectedApp.Level.MAXIMUM && !engine.shizuku.isAvailable()
            ? "STRONG (Shizuku unavailable)"
            : app.level.name();
    text("Protection: " + app.level + " · effective " + effective, 16, true);
    ProtectionState state = selectedState();
    if (state != null) {
      text(
          "Installed: "
              + state.installed
              + "\nFCM capability: "
              + state.fcmCapability
              + "\nNotification permission: "
              + state.notification
              + "\nAutostart: "
              + state.autostart
              + "\nBackground: "
              + state.background
              + "\nBattery exemption: "
              + state.battery,
          16,
          false);
      text(state.detail, 13, false);
    }
    text(
        "Last check: " + time(app.lastCheck) + "\nLast verified repair: " + time(app.lastRepair),
        13,
        false);
    text(
        "Unknown does not mean broken. Notification channels and the live MCS connection may be"
            + " unreadable.",
        12,
        false);
    button(
        "Change protection level",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Protection level")
                .setItems(
                    new String[] {
                      "NORMAL · basic monitoring",
                      "STRONG · GMS + transport + app checks",
                      "MAXIMUM · STRONG + optional Shizuku repairs"
                    },
                    (d, i) -> {
                      engine.apps.select(
                          app.packageName, app.displayName, ProtectedApp.Level.values()[i]);
                      check(false, false);
                    })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    button(
        app.level == ProtectedApp.Level.MAXIMUM && engine.shizuku.isAvailable()
            ? "Repair with Shizuku"
            : "Repair / diagnose",
        () -> {
          if (busy) return;
          busy = true;
          render();
          engine.worker.execute(
              () -> {
                RepairResult result = engine.repairApp(app.packageName);
                engine.check(false, false);
                main.post(
                    () -> {
                      if (!alive) return;
                      busy = false;
                      render();
                      toast(result.outcome + ": " + result.message);
                    });
              });
        });
    button(
        "Open app settings", () -> HyperOsSettings.openAppPermissionEditor(this, app.packageName));
    button(
        "Notification settings",
        () -> {
          try {
            Intent intent =
                Build.VERSION.SDK_INT >= 26
                    ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                    : new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + app.packageName));
            startActivity(intent);
          } catch (RuntimeException unavailable) {
            toast("Settings unavailable");
          }
        });
    button(
        "Remove protection",
        () -> {
          engine.apps.remove(app.packageName);
          navigate("Protected Apps");
          check(false, false);
        });
  }

  private void diagnostics() {
    title("FCM transport");
    text(engine.transport.detail(), 15, false);
    text(
        "A sent heartbeat is a request, not proof that notifications work. No traffic, accounts,"
            + " notification contents or tokens are collected.",
        13,
        false);
    button("Check all policies", () -> check(false, false));
    button("Request controlled reconnect", () -> check(false, true));
    button(
        "Open Google Services diagnostics",
        () -> {
          for (String name :
              new String[] {
                "com.google.android.gms.gcm.GcmDiagnostics",
                "com.google.android.gms.gtalkservice.diagnostics.GTalkServiceDiagnostics"
              }) {
            try {
              startActivity(new Intent().setClassName("com.google.android.gms", name));
              return;
            } catch (RuntimeException ignored) {
            }
          }
          toast("Diagnostics activity unavailable on this GMS version");
        });
    button(
        "Advanced diagnostics / whitelist",
        () -> startActivity(new Intent(this, MainActivity.class)));
  }

  private void history() {
    JSONArray records = new RepairHistoryRepository(this).all();
    if (records.length() == 0) text("No recovery records", 16, false);
    for (int i = 0; i < records.length(); i++) {
      JSONObject entry = records.optJSONObject(i);
      if (entry == null) continue;
      title(time(entry.optLong("timestamp")) + " · " + entry.optString("result"));
      text(
          entry.optString("package")
              + "\n"
              + entry.optString("action")
              + " · "
              + entry.optString("backend")
              + "\n"
              + entry.optString("problem")
              + "\nBefore: "
              + entry.optString("before")
              + "\nAfter: "
              + entry.optString("after"),
          12,
          false);
    }
    text("Stored locally · newest 100 records only", 12, false);
    button(
        "Clear local history",
        () -> {
          new RepairHistoryRepository(this).clear();
          render();
        });
  }

  private void settings() {
    title("Standard Protection");
    text(
        "No root or Shizuku required. Enable automatic protection and grant Modify system settings"
            + " in advanced settings.",
        14,
        false);
    button(
        "Automatic protection / appearance / language",
        () -> startActivity(new Intent(this, MainActivity.class)));
    title("Enhanced Protection (optional)");
    text(engine.shizuku.status(), 15, true);
    text(
        "Only selected MAXIMUM apps. Repairs only explicit RUN_IN_BACKGROUND /"
            + " RUN_ANY_IN_BACKGROUND restrictions. Xiaomi autostart and battery policy are not"
            + " changed.",
        13,
        false);
    Switch enhanced = new Switch(this);
    enhanced.setText("Enable optional Shizuku backend");
    enhanced.setChecked(engine.shizuku.enabled());
    content.addView(enhanced);
    enhanced.setOnCheckedChangeListener(
        (v, on) -> {
          engine.shizuku.setEnabled(on);
          render();
        });
    button(
        "Grant Shizuku permission",
        () -> {
          engine.shizuku.requestPermission();
        });
    button(
        "Test connection",
        () -> {
          engine.shizuku.connect();
          check(false, false);
        });
    button(
        "Reset stopped recovery / cooldown limits",
        () -> {
          engine.resetLimits();
          toast("Recovery limits reset. Run a check before retrying.");
        });
    text(
        "NORMAL: basic monitoring. STRONG: shared GMS + transport and app checks. MAXIMUM: STRONG"
            + " plus optional enhanced repairs. Vendor restrictions remain UNKNOWN where no safe"
            + " API is available.",
        13,
        false);
  }

  private TextView text(String value, int size, boolean bold) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(getResources().getColor(R.color.text_primary));
    view.setPadding(0, dp(5), 0, dp(8));
    if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    content.addView(view);
    return view;
  }

  private void title(String value) {
    text(value, 18, true);
  }

  private void button(String label, Runnable action) {
    Button button = new Button(this);
    button.setText(label);
    button.setTextSize(14);
    button.setAllCaps(false);
    button.setTextColor(getResources().getColor(R.color.blue));
    button.setBackgroundResource(R.drawable.secondary_button_bg);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(8);
    button.setMinHeight(dp(48));
    content.addView(button, params);
    button.setEnabled(!busy);
    button.setOnClickListener(v -> action.run());
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private String time(long timestamp) {
    return timestamp <= 0
        ? "Never"
        : java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            .format(new Date(timestamp));
  }

  private void toast(String value) {
    Toast.makeText(this, value == null ? "Unavailable" : value, Toast.LENGTH_LONG).show();
  }
}
