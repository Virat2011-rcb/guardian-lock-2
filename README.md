# Guardian Lock

Guardian Lock is a privacy-first Android 11+ recovery application that detects explicit
search intent locally and applies escalating, reboot-safe app locks. It is designed for
a personally owned Poco/MIUI phone with a trusted guardian.

The APK has **no `INTERNET` permission**. Search text, history, screenshots, PIN data,
analytics, reasons, and tamper records never leave the device.

## Implemented

- Guardian PIN stored as a salted PBKDF2-HMAC-SHA256 hash with constant-time checking.
- Guardian-facing 45-day commitment screen with instructions to keep the 6-10 digit PIN
  private from the user until the commitment ends unless there is a real safety emergency.
- Device Owner policies block ordinary uninstall, force-stop, data clearing, USB
  debugging, and manual date/time changes.
- Browser discovery covers Opera/Opera GX/Opera Beta/Opera Mini, Chrome, Edge, Brave,
  Firefox/Focus, Samsung Internet, DuckDuckGo, Tor, Vivaldi, Kiwi, Mi Browser, and newly
  installed apps that register as web browsers.
- Accessibility detection is limited to editable browser fields. Private/incognito UI
  is processed the same way when the browser exposes its address field.
- Local classifier supports Unicode normalization, leetspeak, repeated characters,
  one-edit misspellings, multilingual terms, and guardian-imported offline JSON packs.
- Recovery/medical intent allowlist prevents blocking help-seeking searches.
- 60-second urge-delay screen with guided breathing and a personal recovery reason.
- Stricter rolling 24-hour escalation: 2 hours, 6 hours, 12 hours, 24 hours, then
  48 hours.
- Level 3+ adds only entertainment apps explicitly selected by the guardian.
- Foreground enforcement, exact-alarm fallback, boot recovery, dual wall/monotonic
  clocks, automatic-time policy, Accessibility fail-closed behavior, and local tamper
  audit.
- Strict integrity controls: Device Owner blocker screen, protection score, tamper score,
  15-second Accessibility grace before browser suspension, safe-mode/time-change logging,
  new-browser auto-suspension, and automatic 12-hour level-3 lock when tamper score
  reaches 100.
- MIUI persistence hardening: sticky foreground service, one-minute self-heal alarm,
  restart after clear-recents/task removal, boot/user-present/package-replaced/time-change
  receivers, and Accessibility-service restart hooks.
- Room database for trigger events, tamper events, personal reasons, and local keywords.
- Local dashboard with streak, weekly triggers, common trigger hour, and seven-day graph.
- MIUI AutoStart and battery-optimization setup shortcuts.

## Build

1. Install [Android Studio](https://developer.android.com/studio/install) on 64-bit
   Windows and include Android SDK Platform 35 and Platform Tools.
2. Open this directory in Android Studio and allow the pinned Gradle 8.9 wrapper to sync.
3. Run unit tests:

   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```

4. Build a signed APK using **Build > Generate Signed Bundle / APK > APK**. Keep the
   signing keystore offline; future upgrades must use the same key.

Strong enforcement requires Device Owner provisioning; normal APK installation is only
a functional test mode. Follow [Provisioning](docs/PROVISIONING.md) after reading the
[Architecture and security boundaries](docs/ARCHITECTURE.md). For MIUI Global 12.5.5
with `MANAGE_DEVICE_ADMINS` from ADB, use
[MIUI 12.5.5 Device Owner provisioning](docs/MIUI_12_5_DEVICE_OWNER.md).

## Project map

```text
app/src/main/java/com/morningsearch/guard/
  MainActivity.kt                     local dashboard and guardian controls
  UrgeDelayActivity.kt                60-second recovery/breathing flow
  SearchGuardAccessibilityService.kt  browser UI detection and launch blocking
  EnforcementService.kt               foreground watchdog and reconciliation
  LockManager.kt                      DPC policies, suspension, alarms, escalation
  BrowserRegistry.kt                  known and dynamically discovered browsers
  SearchClassifier.kt                 local multilingual/fuzzy classification
  GuardianPinStore.kt                 guardian authentication
  GuardStore.kt                       synchronous enforcement state and dual clocks
  TimeIntegrityMonitor.kt             clock-tamper checks
  data/                                Room entities, DAO, database and local import
```

## Important boundary

No Android app is impossible to remove against a user with physical recovery access,
root, or a factory reset. Guardian Lock prevents ordinary UI/ADB bypasses in Device
Owner mode and keeps a guardian emergency release. It deliberately never blocks the
dialer, Settings as a whole, banking, school, work, health, or emergency apps.
