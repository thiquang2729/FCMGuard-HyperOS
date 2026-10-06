# Recovery engine

## Decision sequence

1. Inspect configured GMS whitelist.
2. No actionable cause: no Settings write, no reconnect.
3. Missing required item: restore only through SettingsGuard, preserve the current nonempty list;
   only an empty list recovers last-known-good/fallback values. Normalize duplicates during actual repair.
4. Read back and ensure the required item and all planned packages survived. Failed/unverified writes are FAILED.
5. A verified whitelist repair justifies a heartbeat request. No network: defer until network callback.
6. Inspect only user-selected apps. NORMAL is inspect-only automatically. STRONG uses Standard;
   MAXIMUM uses Shizuku only when opted in, permitted and connected, otherwise Standard.
7. Explicit readable background restriction: repair exactly one AppOp; inaccessible/default/ambiguous values
   do not cause writes. Standard cannot change those policies and returns NEED_USER_ACTION.
8. Re-read and independently verify the relevant action. Log timestamp, package, action, backend,
   before/after state, outcome and explanation. No tokens, accounts, content or traffic.

## Recovery levels

| Level | Condition/action |
| --- | --- |
| 0 | No actionable cause; inspect only |
| 1 | Configured GMS whitelist restore + verification |
| 2 | Verified restore plus controlled heartbeat request; manual request also permitted |
| 3 | Selected-app policy issue; Standard guidance/settings |
| 4 | Selected MAXIMUM app with available Shizuku; one explicitly restricted background AppOp |

The two background AppOps are repaired one at a time. If the first repair resolves the cause, no second
write is performed. UID overrides are deliberately not repaired because they can affect sibling packages.

## Limits and restart

Per `(package, action)` persisted cooldown: 5 minutes, including manual attempts. Failure counter:
3 consecutive failures stops automatic attempts until user reset or an observed healthy GMS whitelist.
A clock rollback is treated as cooling, not permission to spam. There is no automatic retry timer;
future legitimate events/fallback checks may retry only within these limits.
Manual Reset recovery limits clears counters/cooldowns but does not perform a repair.
Heartbeat requests have a separate persisted 5-minute cooldown and 3 send-failure stop.
Successful dispatch clears send failures but does not prove connection health.
Service restarts and normal boot reuse preferences and selections. Existing boot behavior is retained;
protection resumes after credential unlock, not as a new direct-boot data migration.

## Health score

Start 100. GMS unavailable -45; otherwise missing whitelist item -35. Unavailable/unvalidated network
-20; unknown live transport -5. Protection disabled/service stopped -15. Enhanced opted-in but unavailable
-5. Selected apps contribute -10 failed / -5 warning / -2 unknown, capped at -20. Clamp at 0.
CRITICAL: GMS unavailable or score <50. DEGRADED: <75. ATTENTION: <95 or any unknown transport/app.
HEALTHY requires verified components; current public transport inspection intentionally cannot produce
HEALTHY as a delivery claim. Scores are reproducible configuration feedback, not delivery probabilities.

## Verification

Local verification (2026-10-06, JDK 17 / Gradle 8.7 / Android SDK 35):
`assembleDebug`, `testDebugUnitTest` and `lintDebug` passed. All 31 tests passed.
Lint has no errors; existing compatibility, translation and layout warnings remain.
The six existing layout profiles (320–480 dp) passed. No physical device was connected;
Shizuku shell behavior, HyperOS recovery, real push latency and battery usage remain manual checks.

Run `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and
`python3 tools/check_layout_profiles.py`. Unit/Robolectric tests cover whitelist no-op/preservation,
read-back rejection, last-good behavior, backend verification, restart cooldown, repeated failure stop,
no-Shizuku fallback (including API 21), uninstall state, bounded history, boot enabled/disabled,
service restart, manual reconnect cooldown, offline reconnect deferral and API 21 Home creation.
Device matrix: HyperOS 3 China ROM, optional Shizuku stopped/denied/disconnected/restarted, GMS removed
from whitelist, duplicate/empty/vendor list, offline repair then restored network, selected app uninstall,
notification denied, blocked Xiaomi AppOps, service kill/reboot. Measure idle battery and real push latency.
A passing build does not replace these device checks.
