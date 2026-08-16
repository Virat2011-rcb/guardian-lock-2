# Poco / MIUI provisioning

## Before starting

Device Owner provisioning normally requires a factory reset and removal of all accounts.
Back up photos, chats, authenticator recovery codes, files, and app data first. Build and
test the signed APK in ordinary mode before resetting the phone.

The guardian should retain the 6-10 digit PIN privately for the 45-day commitment and
should not reveal it to the user unless there is a real device-safety emergency. Losing
both the PIN and signing key can make normal maintenance difficult; physical
recovery/factory reset remains the final escape.

## Strong-mode sequence

On MIUI Global 12.5.5, if ADB returns `MANAGE_DEVICE_ADMINS`, use the supported QR
workflow in [MIUI 12.5.5 Device Owner provisioning](MIUI_12_5_DEVICE_OWNER.md) instead
of the ADB command below.

1. Factory-reset the Poco phone.
2. Complete initial setup without adding Google/Xiaomi/work accounts.
3. Enable Developer options by tapping **MIUI version** seven times.
4. Enable USB debugging and connect the phone to the PC.
5. Install the final signed APK:

   ```powershell
   adb install guardian-lock-release.apk
   ```

6. Open Guardian Lock. Let the guardian privately read the instructions and set a
   6-10 digit PIN without telling it to the user.
7. Enable **Guardian Lock** in Settings > Accessibility > Downloaded apps.
8. On the PC, provision Device Owner:

   ```powershell
   adb shell dpm set-device-owner com.morningsearch.guard/.GuardianDeviceAdminReceiver
   ```

9. Reopen Guardian Lock. It applies uninstall/data-clear/debugging/time policies and
   starts the commitment. USB debugging will then be disabled by policy.
10. In Guardian Lock, open the MIUI AutoStart shortcut and permit AutoStart. Open the
    battery shortcut and choose unrestricted/no-restrictions background use.
11. The guardian selects any entertainment apps that level-3 escalation may suspend.
12. Add accounts and finish normal phone setup only after Device Owner succeeds.

## MIUI background reliability checklist

MIUI can kill third-party background processes even when Android would normally keep
them alive. After Device Owner provisioning, apply all of these:

1. Settings > Apps > Manage apps > Guardian Lock > Battery saver:
   choose **No restrictions**.
2. Security app > Manage apps / Permissions > AutoStart:
   allow **Guardian Lock**.
3. Recents screen:
   lock Guardian Lock in recents if the ROM offers the lock icon.
4. Settings > Notifications:
   allow Guardian Lock notification.
5. Settings > Accessibility:
   keep Guardian Lock enabled.
6. Do not put the phone into Ultra Battery Saver if you expect third-party app monitoring
   to continue. MIUI may stop non-system apps in that mode. Guardian Lock now self-heals
   through foreground service, boot/user-present receivers and alarms, but Ultra Battery
   Saver is controlled by the ROM and cannot be fully overridden by an app.

If `dpm set-device-owner` reports existing accounts, users, or an already provisioned
device, do not try bypass commands. Reset again and repeat without adding accounts. Some
MIUI builds require enterprise QR provisioning; that flow needs a separately hosted DPC
enrollment package and is not silently emulated by this project.

## Guardian emergency release

The guardian opens Guardian Lock, chooses **Guardian: emergency release**, and enters the
PIN. The app unsuspends managed packages, clears its restrictions, and relinquishes Device
Owner. This is intentionally available during the commitment for device-safety failures.

## Do not test casually

A real positive detection starts the 60-second recovery screen and then an actual lock.
Perform the release-candidate test only when a 2-hour first lock is acceptable.
