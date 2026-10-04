# Guardian Lock QR provisioning

Use this for Android Enterprise QR provisioning after factory reset.

## Current APK

```text
APK file: guardian-lock-release-aligned.apk
Package: com.morningsearch.guard
Admin receiver: com.morningsearch.guard/.GuardianDeviceAdminReceiver
APK SHA-256 checksum, base64url: sygn4sozfw4elQKgqdbp2WrHU-8jDcLxvAaP8BGKVK8
Signing cert SHA-256: 6D:39:04:4B:6F:AF:D4:E7:97:74:1F:4C:DF:45:E4:A0:0F:43:86:E9:13:DC:F4:95:D5:4B:64:E2:4B:1D:E0:54
```

## Steps

1. Upload `guardian-lock-release-aligned.apk` to a direct HTTPS URL.
2. Edit `guardian-lock-provisioning-template.json`.
3. Replace:

```text
https://YOUR-DOMAIN.example/guardian-lock-release-aligned.apk
```

with your real direct APK URL.

4. Generate QR PNG:

```powershell
python -m pip install qrcode[pil]
python provisioning\make_qr.py provisioning\guardian-lock-provisioning-template.json provisioning\guardian-lock-provisioning-qr.png
```

5. Factory reset phone.
6. On first setup screen, tap screen 6 times.
7. Connect Wi-Fi.
8. Scan the generated QR.

Do not add Google or Xiaomi accounts before provisioning.
