param([switch]$Offline, [switch]$Release, [switch]$Unsigned, [switch]$UseInstalledAlphaKey)
$ErrorActionPreference = 'Stop'
if (($Unsigned -or $UseInstalledAlphaKey) -and !$Release) { throw 'Signing options require -Release.' }
if ($Unsigned -and $UseInstalledAlphaKey) { throw 'Select exactly one signing mode.' }
$command = Join-Path $PSScriptRoot 'gradlew.bat'
$prepared = 'D:\apk\.tools'
if (!$env:JAVA_HOME -and (Test-Path "$prepared\jdk")) {
    $jdk = Get-ChildItem "$prepared\jdk" -Directory | Where-Object { Test-Path "$($_.FullName)\bin\javac.exe" } | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME\bin;$env:Path" }
if (!$env:ANDROID_HOME -and (Test-Path "$prepared\android-sdk")) { $env:ANDROID_HOME = "$prepared\android-sdk" }
if (!$env:GRADLE_USER_HOME -and (Test-Path "$prepared\gradle-user-home")) {
    $env:GRADLE_USER_HOME = "$prepared\gradle-user-home"
    $command = "$prepared\gradle-8.9\bin\gradle.bat"
}
$arguments = @('-p', $PSScriptRoot, '--max-workers=2')
if ($Offline) { $arguments += '--offline' }
if (!$Release) {
    & $command @arguments :app:assembleDebug
    exit $LASTEXITCODE
}
& $command @arguments '-PreleaseSplits=true' :app:assembleRelease :app:testReleaseUnitTest
if ($LASTEXITCODE) { exit $LASTEXITCODE }
foreach ($suite in 'local', 'installer', 'controls', 'network') {
    & "$PSScriptRoot\tests\$suite\run.ps1"
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
}
if ($Unsigned) { exit 0 }
$signing = @()
if ($UseInstalledAlphaKey) { $signing += '--installed-alpha-key' }
& python "$PSScriptRoot\tools\package.py" sign @signing
exit $LASTEXITCODE
