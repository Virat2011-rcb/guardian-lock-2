param(
    [Parameter(Mandatory = $true)]
    [string] $ApkPath,

    [Parameter(Mandatory = $true)]
    [string] $DownloadUrl,

    [string] $OutputPath = "guardian-lock-provisioning-payload.json"
)

if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK not found: $ApkPath"
}

$hashHex = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
$bytes = for ($i = 0; $i -lt $hashHex.Length; $i += 2) {
    [Convert]::ToByte($hashHex.Substring($i, 2), 16)
}
$checksum = [Convert]::ToBase64String([byte[]]$bytes).TrimEnd('=').Replace('+','-').Replace('/','_')

$payload = [ordered]@{
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME" = "com.morningsearch.guard/.GuardianDeviceAdminReceiver"
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION" = $DownloadUrl
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM" = $checksum
    "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED" = $true
    "android.app.extra.PROVISIONING_SKIP_ENCRYPTION" = $false
}

$json = $payload | ConvertTo-Json -Depth 5
Set-Content -LiteralPath $OutputPath -Value $json -Encoding UTF8

Write-Host "Provisioning payload written to $OutputPath"
Write-Host "Checksum: $checksum"
Write-Host ""
Write-Host "Create a QR code from this JSON payload and scan it from the factory-reset Android setup wizard."
