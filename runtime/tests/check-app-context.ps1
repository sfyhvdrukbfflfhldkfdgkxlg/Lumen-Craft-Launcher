param(
    [Parameter(Mandatory=$true)] [string]$Sdk,
    [Parameter(Mandatory=$true)] [string]$Jdk,
    [string]$Serial,
    [string]$RuntimeZip,
    [switch]$Graphics,
    [int]$Major = 8
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$adb=Join-Path $Sdk 'platform-tools/adb.exe'
$target=@()
if ($Serial) { $target=@('-s',$Serial) }
$apk=[string](& $adb @target shell pm path dev.lumencraft.android | Where-Object { $_ -like "package:*" } | Select-Object -First 1)
$apk=$apk.Trim().Replace('package:','')
if ($apk -notmatch '^/data/app/[A-Za-z0-9_./=+~-]+/base\.apk$') {throw 'Install the debug APK first'}
$classes=(Get-ChildItem "$project/app/build/intermediates/javac" -Recurse -Filter RuntimeStore.class | Select-Object -First 1).FullName
if (!$classes) {throw 'Build app:assembleDebug first'}
$main=$classes.Substring(0,$classes.LastIndexOf('\dev\lumencraft\'))
$area=Join-Path ([IO.Path]::GetTempPath()) "lumencraft-device-check-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force "$area/classes" | Out-Null
$android=Join-Path $Sdk 'platforms/android-35/android.jar'
& "$Jdk/bin/javac.exe" --release 8 -encoding UTF-8 -cp "$android;$main" -d "$area/classes" (Join-Path $PSScriptRoot RuntimeDeviceCheck.java)
if ($LASTEXITCODE) { exit $LASTEXITCODE }
& "$Jdk/bin/jar.exe" cf "$area/test.jar" -C "$area/classes" .
if ($LASTEXITCODE) { exit $LASTEXITCODE }
& "$Jdk/bin/java.exe" -cp "$Sdk/build-tools/35.0.0/lib/d8.jar" com.android.tools.r8.D8 --lib $android --min-api 23 --output "$area/dex.jar" "$area/test.jar"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$tag="qa-$([guid]::NewGuid().ToString('N'))"
& $adb @target push "$area/dex.jar" "/data/local/tmp/$tag.jar"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
& $adb @target shell "cat /data/local/tmp/$tag.jar | run-as dev.lumencraft.android sh -c 'cat > cache/$tag.jar'"
$mode=if ($Graphics) { 'graphics' } else { 'tls' }
if ($RuntimeZip) {
    & $adb @target push $RuntimeZip "/data/local/tmp/$tag.zip"
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
    & $adb @target shell "cat /data/local/tmp/$tag.zip | run-as dev.lumencraft.android sh -c 'cat > cache/$tag.zip'"
    $mode="zip /data/data/dev.lumencraft.android/cache/$tag.zip $Major"
}
$out=& $adb @target shell "run-as dev.lumencraft.android sh -c 'CLASSPATH=${apk}:cache/$tag.jar app_process /system/bin dev.lumencraft.runtime.RuntimeDeviceCheck $mode'"
$out
if (!($out -match '^PASS')) { throw 'No successful result from device' }


