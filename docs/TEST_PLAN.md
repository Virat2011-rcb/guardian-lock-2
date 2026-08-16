# Release test plan

Run unit tests, then test the signed release build on the target Poco Android 11 device.

## Browser matrix

For every installed browser, test normal and private/incognito mode with VPN off and on:

- Opera, Opera GX, Opera Beta, Opera Mini;
- Chrome, Edge, Brave;
- Firefox and Focus;
- Samsung Internet, DuckDuckGo, Mi Browser;
- one browser installed after Guardian Lock provisioning.

Verify recovery-intent searches remain available and explicit test phrases activate the
urge delay. Confirm the recorded database row contains package/category/time only—not the
query.

## Escalation

- first rolling-24h trigger: 2 hours, browsers only;
- second: 6 hours, browsers only;
- third: 12 hours, browsers plus guardian-selected entertainment;
- fourth/fifth: 24/48 hours;
- emergency, banking, school, work, health and Settings remain usable.

## Persistence and tamper

- reboot during urge delay and during active lock;
- swipe app away and allow MIUI memory pressure;
- attempt app force-stop, clear data and uninstall;
- disable Accessibility and confirm browsers fail closed;
- install a new browser during a lock;
- attempt manual time change;
- let the guardian perform emergency release.

## Release checks

- inspect merged manifest: there must be no `INTERNET` permission;
- run `./gradlew lintRelease testDebugUnitTest assembleRelease`;
- verify R8 release launch, Room creation, reboot receiver and FGS notification;
- retain mapping file, signed APK, SHA-256 checksum, keystore backup and Room schema;
- repeat the matrix after every MIUI, browser, Accessibility, or target-SDK update.
