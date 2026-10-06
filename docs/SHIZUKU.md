# Optional Shizuku enhanced protection

Standard Protection works without root, Shizuku, Accessibility, VPN or persistent ADB.
Do not install Shizuku unless you want optional selected-app background-policy recovery.

## Setup

1. Start Shizuku using its official setup instructions.
2. Open FCMGuard Settings, enable optional Shizuku backend and grant permission.
3. Add only the apps you want to protect and choose MAXIMUM.
4. Test connection and run a full check. Review app details before repair.

No permission dialog is raised automatically by the background service. Shizuku not installed/not
running/permission denied/disconnected is a normal fallback to STRONG. Android <28 uses Standard.
After reboot, non-root Shizuku normally must be started again; Standard resumes independently.

## Exact scope

Only `RUN_IN_BACKGROUND` and `RUN_ANY_IN_BACKGROUND` for selected MAXIMUM apps.
Read `cmd appops get --user <app-user> <package> <op>`; explicit `ignore`/`deny` permits
`cmd appops set --user <app-user> <package> <op> allow`. Re-read afterward.
Package names are validated and passed as individual ProcessBuilder arguments, never interpolated
into a shell. No arbitrary command endpoint. No `--uid`, resets, global Doze changes, force-stop,
package enabling, notification grants, vendor autostart writes or app-idle forcing.
UID override, default, foreground, absent, conflicting, unreadable and unsupported output yields UNKNOWN.
Xiaomi operations 10008/10053 remain strictly read-only through upstream AutostartStatusReader.
Vendor battery/background policies cannot be inferred from standard AppOps alone.

## Lifecycle and bounds

All privileged code, including the narrow nested PolicyService, is in ShizukuProtectionBackend.
The official UserService/AIDL API is used; deprecated Shizuku.newProcess is not used.
UserService is non-daemon, versioned and user-scoped. Each command has a 5-second process timeout,
output is capped at 8 KiB; client RPC is capped at 7 seconds. A hung Binder disables enhanced mode
for the app process so repeated checks do not create accumulating blocked threads.
The serial engine worker keeps Binder/process operations off the Android main thread.
Permission/binder state is checked before each operation, exceptions return explicit results.
Turning enhancement off unbinds/removes the user service. No Shizuku availability is needed to launch Home.

## Official references

- [Shizuku API and developer guide](https://github.com/RikkaApps/Shizuku-API)
- [Shizuku setup](https://shizuku.rikka.app/guide/setup/)
- [Android background restriction test commands](https://developer.android.com/topic/performance/background-optimization)

Platform behavior differs by ROM/version. Unknown policies are not guessed; test on the actual device.
