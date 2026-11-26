param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$arguments = @()
if ($Offline) { $arguments += '--offline' }
$command = Join-Path $PSScriptRoot 'gradlew.bat'
# Use the prepared Android toolchain when available on this workstation.
$prepared = 'D:\apk\.tools'
if (-not $env:JAVA_HOME -and (Test-Path "$prepared\jdk")) {
    $jdk = Get-ChildItem "$prepared\jdk" -Directory | Where-Object {Test-Path "$($_.FullName)\bin\javac.exe"} | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:ANDROID_HOME -and (Test-Path "$prepared\android-sdk")) { $env:ANDROID_HOME = "$prepared\android-sdk" }
if (-not $env:GRADLE_USER_HOME -and (Test-Path "$prepared\gradle-user-home")) {
    $env:GRADLE_USER_HOME = "$prepared\gradle-user-home"
    $command = "$prepared\gradle-8.9\bin\gradle.bat"
}
& $command -p $PSScriptRoot --max-workers=2 @arguments assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$out = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk" "$out\LumenCraft-Alpha1.0.0.apk" -Force
Copy-Item "$PSScriptRoot\rooms\build\outputs\apk\debug\rooms-debug.apk" "$out\LumenCraft-Rooms-Alpha1.0.0.apk" -Force
