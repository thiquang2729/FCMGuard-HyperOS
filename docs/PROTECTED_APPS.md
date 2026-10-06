# Protected Apps

Add installed user-facing apps from the launcher list. FCM detected means standard manifest receivers
or services were found; no match means UNKNOWN, not proof of a broken client. Manual selection is
allowed even when the app uses a different notification mechanism. GMS itself is always the shared guard.
Maximum 64 selected apps; selection never modifies unselected apps. Remove protection stops future
checks/repairs but does not revert an already granted AppOp. Existing policies are not automatically
reverted because the user or Android may have changed them independently.

| Level | Automatic behavior |
| --- | --- |
| NORMAL | Basic selected-app inspection; no automatic app policy repair |
| STRONG | Shared GMS/transport protection and selected-app policy monitoring / Standard guidance |
| MAXIMUM | STRONG plus opted-in Shizuku inspection/minimum verified background repairs when available |

GMS and transport are shared system layers, not per-app connections. All levels benefit from the
existing GMS guard while automatic protection is enabled. MAXIMUM fallback explicitly shows STRONG.

App Detail shows package/label, installed state, FCM capability, notification permission, read-only
Xiaomi autostart, background policy, battery exemption, health, last check and last verified repair.
Uninstalled apps remain selected and display FAILED until removed/reinstalled; no repair runs on them.
Not exempt from battery optimization is UNKNOWN, not evidence of an explicit restriction.
Android notification permission does not prove all notification channels are enabled.

Standard exposes Open app settings and Notification settings. Configure HyperOS battery mode and
Autostart manually where safe programmatic repair is unavailable. Full Check is inspect-only;
Repair detected problems/Repair with Shizuku performs only observed, supported actions within cooldowns.
History is local and limited to the newest 100 entries. Unknown transport is never displayed as Connected.
