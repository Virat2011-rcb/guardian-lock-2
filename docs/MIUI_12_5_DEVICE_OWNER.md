# MIUI 12.5.5 Device Owner provisioning

## What the `MANAGE_DEVICE_ADMINS` error means

On MIUI Global 12.5.5, this error:

```text
java.lang.SecurityException:
Neither user 2000 nor current process has android.permission.MANAGE_DEVICE_ADMINS
```

does **not** mean `GuardianDeviceAdminReceiver` is missing from the app.

The receiver is declared correctly. The failing user is `2000`, which is Android's
`shell` user. MIUI is refusing the ADB shell path for Device Owner provisioning on this
build. Signing in to a Xiaomi account to enable **Install via USB** is not the correct
fix, because Device Owner provisioning must happen before personal accounts are added.

## Supported workflow for this phone

Use Android Enterprise QR provisioning. Do not use `adb install` or the ADB
`set-device-owner` path on this MIUI build.

## Required APK

Use the signed release APK:

```text
guardian-lock-release.apk
```

Host it at a direct HTTPS download URL. The URL must return the APK file directly, not a
preview page.

Example:

```text
https://example.com/guardian-lock-release.apk
```

## Create the QR payload

From the project directory:

```powershell
.\tools\create-provisioning-payload.ps1 `
  -ApkPath .\guardian-lock-release.apk `
  -DownloadUrl "https://example.com/guardian-lock-release.apk" `
  -OutputPath .\guardian-lock-provisioning-payload.json
```

Create a QR code from the JSON written to:

```text
guardian-lock-provisioning-payload.json
```

The payload uses:

```text
com.morningsearch.guard/.GuardianDeviceAdminReceiver
```

## Provision the Poco

1. Factory reset the phone.
2. At the first welcome screen, do not add a Google account.
3. Do not add a Xiaomi account.
4. Tap the first setup screen six times in the same spot to open QR provisioning.
5. Connect Wi-Fi when prompted.
6. Scan the QR code.
7. Let Android download, verify, install, and provision Guardian Lock.
8. Complete setup.
9. Open Guardian Lock.
10. Let the guardian privately set the 6-10 digit PIN.
11. Enable Guardian Lock Accessibility.
12. Enable MIUI AutoStart and unrestricted battery for Guardian Lock.

## Do not use these workarounds

Do not sign in to a Xiaomi account just to enable **Install via USB**.

Do not add and remove accounts before Device Owner provisioning.

Do not rely on this failing command on MIUI 12.5.5:

```powershell
adb shell dpm set-device-owner com.morningsearch.guard/.GuardianDeviceAdminReceiver
```

If QR provisioning is unavailable or blocked by the ROM, that specific MIUI build does
not provide a clean supported custom-DPC Device Owner path without an enterprise
provisioning channel such as QR/NFC/zero-touch.
