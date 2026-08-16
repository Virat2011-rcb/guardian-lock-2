# Architecture and security boundaries

## Trust model

Guardian Lock is a local Device Policy Controller (DPC). The guardian owns the PIN and
the signing-key backup. The protected user can use the phone normally but cannot change
critical settings inside the app. The DPC must be provisioned as Device Owner for strong
enforcement.

There is no backend and the manifest intentionally omits `android.permission.INTERNET`.
Room and private SharedPreferences are the only persistence stores. Android application
sandboxing protects them; Device Owner policy prevents ordinary clear-data/force-stop.

## Detection pipeline

```text
Browser editable field
  -> Accessibility event (max 500 chars, memory only)
  -> normalization / recovery-intent allowlist / local keyword classifier
  -> category only is recorded (never the query)
  -> rolling 24-hour escalation plan
  -> persisted 60-second urge delay
  -> DevicePolicyManager package suspension
```

VPN and private browsing encrypt or isolate network/history state; they do not normally
hide the visible address field from Android Accessibility. Browser implementations can
still omit or redact nodes, so each browser/version must pass the device test matrix.

The app does not inspect TLS traffic, take screenshots, run an image nudity model, or
read page bodies. These are deliberate privacy boundaries.

## Enforcement state

Critical pending/lock state is synchronously committed to private SharedPreferences so
an Accessibility callback can fail safely without waiting for Room. Analytics and audit
records use Room asynchronously.

Active locks store both wall-clock and `elapsedRealtime` deadlines. On the same boot,
the later remaining duration wins, preventing a manual clock rollback from shortening a
lock. Device Owner also requires automatic time and disallows date/time configuration.
After reboot, the persisted wall deadline is restored and package suspension reconciled.

## Anti-tamper

- `setUninstallBlocked`: prevents ordinary uninstall.
- `setUserControlDisabledPackages`: prevents user force-stop/data-clear of the DPC.
- `DISALLOW_DEBUGGING_FEATURES`: closes ADB bypass after provisioning.
- Device Owner cannot be removed through ordinary Device Admin settings.
- Accessibility loss is logged; browsers are suspended fail-closed until it returns.
- watchdog gaps, admin callbacks, clock drift, and policy failures are logged locally.
- newly installed browser-capable packages are reconciled from package broadcasts.

Android 11 has no supported DPC API that silently re-enables an Accessibility Service.
Fail-closed browser suspension provides enforcement without blocking Settings or device
safety controls.

“Repeated force-stop attempts” cannot be observed directly after a process is dead.
Device Owner prevents the Settings force-stop action; unexplained same-boot watchdog gaps
are logged as suspected interruptions rather than falsely labelled as proven tampering.

## Safety allowlist

Only browser-capable packages and guardian-selected entertainment packages are ever
suspended. Guardian Lock never dynamically classifies banking, education, work, health,
system Settings, launcher, dialer, permission controller, or emergency packages as
entertainment. The entire phone is never placed in kiosk/lock-task mode.

## Keyword updates

The built-in classifier ships in the signed APK. The guardian can import an offline JSON
pack after PIN authentication; entries are normalized and capped at 10,000. See
`app/src/main/assets/keyword_pack_example.json`. A public distribution should additionally
require an Ed25519 signature on packs before accepting centrally distributed updates.

## Residual bypasses

- recovery-mode factory reset;
- rooted/compromised firmware;
- a browser whose private UI exposes no editable Accessibility node;
- content reached without a detectable search phrase;
- web content embedded inside a non-browser app not selected by the guardian.

These limits should be presented during guardian onboarding rather than hidden behind a
“tamper-proof” claim.
