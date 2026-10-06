# FCMGuard 2.0 architecture

## Upstream audit

Base: `b6eb75c3213da24369efbe7e02f13e96dc303a69` (1.5.9).
The project uses Java 17, AGP 8.6.1, compileSdk 35, minSdk 21 and targetSdk 22.
It has no application framework dependency. MainActivity owns configuration, native locale/theme,
FCM manifest scanner, read-only Xiaomi AppOps assistant and GMS diagnostic deep links.
SettingsGuard stores configuration, enabled/persistent-notification preferences and last-known-good lists
in `guard_state`. BootReceiver resumes GuardService after boot/update only when enabled.
GuardService watches the exact configured Settings.System URI with a 400 ms debounce and uses a
non-waking, in-process 30-minute fallback. FcmReconnect sends package-scoped heartbeat broadcasts.

## Preserved path

`HyperOS setting change -> exact ContentObserver -> debounce -> ProtectionEngine -> RecoveryEngine ->
StandardProtectionBackend -> SettingsGuard.repair -> read-back verification -> controlled heartbeat request`.

SettingsGuard remains the writer. The old Settings screen, scanner, boot receiver, notification channel,
quiet mode, locale/theme support and expert configurable setting are retained. No targetSdk increase.
The default required item is `com.google.android.gms`. Expert override changes the key/item being
protected, as upstream; GMS protection assumes these remain at their defaults. The dashboard describes
its configured-whitelist result rather than treating it as evidence of notification delivery.

## Responsibilities

| Area | Responsibility |
| --- | --- |
| core/ProtectionEngine | Shared serial worker, Standard/optional enhanced routing, ordered inspection/recovery |
| core/RecoveryEngine | Observed cause, minimum action, backend execution, independent verification, history |
| core/RecoveryGate | Persisted 5-minute action cooldown and 3-failure stop |
| guard/GmsGuard | GMS/configured whitelist inspection backed by the existing SettingsGuard |
| guard/TransportGuard | Public network prerequisites, unknown live MCS state, justified reconnect |
| guard/AppGuard | Installed/enabled, manifest capability, notification permission, read-only autostart, battery exemption |
| backend/ProtectionBackend | Backend contract; RecoveryEngine has no Shizuku API dependency |
| backend/StandardProtectionBackend | Whitelist repair; unsupported app policy directs user to Settings |
| backend/ShizukuProtectionBackend | Optional permission/binder/UserService lifecycle and narrow verified AppOps repairs |
| data/ProtectedAppRepository | Up to 64 user-selected apps, protection level, last check/verified repair |
| data/RepairHistoryRepository | Newest 100 local diagnostic repair records |
| ui/DashboardActivity | Home, selected apps, app detail, diagnostics, history, enhanced settings |
| MainActivity | Retained advanced configuration, automatic service, notification and appearance settings |

Future RootProtectionBackend can implement the same contract. It must preserve selection scope,
capability checks and verification; it does not require changing RecoveryEngine.

## Event and power budget

One serial engine worker; concurrent service requests coalesce to at most one follow-up.
The exact settings observer remains primary. Package add/remove/change updates selected-app state.
A default-network callback (API 24+) schedules a debounced check and resumes a justified deferred
reconnect after validated networking returns. The 30-minute fallback remains non-waking.
There are no alarms, wakelocks, sockets, packet inspection, continuous polling or reconnect timers.
A Shizuku UserService exists only with enhanced mode enabled and granted; it uses non-daemon mode.
Maximum selected-app checks can involve several shell probes; use MAXIMUM only where necessary.
Battery impact and vendor compatibility must still be measured on real HyperOS hardware.

## State honesty

Manifest capability is not live FCM transport health. Public Android networking cannot prove an
authenticated MCS connection, so transport is UNKNOWN when prerequisites are available, WARNING
when unavailable/unvalidated. A broadcast sent is UNKNOWN, never SUCCESS/Connected.
App channels, vendor policies, defaults and inaccessible AppOps remain UNKNOWN/UNSUPPORTED.
The score is configuration/confidence feedback; recovery never branches on the score.

## Compatibility changes

Shizuku API/provider 13.1.5, AIDL generation, AndroidX annotation compatibility and core-library desugaring
are added. Desugaring is required by the Shizuku dependency on old Android APIs; minSdk and targetSdk
are preserved. The provider is protected by INTERACT_ACROSS_USERS_FULL. Enhanced operations require
API 28+, a live binder, permission, explicit opt-in and MAXIMUM selection. Standard mode remains API 21+. Library minSdk overrides are intentional: the optional provider is disabled
below API 28, and no Shizuku calls are made below API 28. API 21 Standard fallback has a Robolectric check.
ACCESS_NETWORK_STATE permits read-only network prerequisites and callbacks; INTERNET is not added.
QUERY_ALL_PACKAGES is not added; launcher intent visibility permits explicit app selection.
