param([string]$Sdk=$env:ANDROID_HOME,[string]$Jdk=$env:JAVA_HOME,[string]$Device)
$ErrorActionPreference='Stop'
if (!$Sdk -or !$Jdk) { throw 'Supply -Sdk and -Jdk or set ANDROID_HOME and JAVA_HOME' }
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$work=Join-Path ([IO.Path]::GetTempPath()) ('lumen-account-test-'+[Guid]::NewGuid().ToString('N'))
$classes=New-Item -ItemType Directory -Force "$work\classes"
$dex=New-Item -ItemType Directory -Force "$work\dex"
$manifest='<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.lumencraft.test.accounts"><uses-sdk android:minSdkVersion="23" android:targetSdkVersion="28"/><application android:testOnly="true" android:allowBackup="false" android:label="Lumen account test"/><instrumentation android:name="dev.lumencraft.account.AccountTest" android:targetPackage="dev.lumencraft.test.accounts"/></manifest>'
[IO.File]::WriteAllText("$work\AndroidManifest.xml",$manifest)
$android="$Sdk\platforms\android-35\android.jar"
$bt="$Sdk\build-tools\35.0.0"
$src=Get-ChildItem "$root\app\src\main\java\dev\lumencraft\account" -Filter '*.java'|Select-Object -Expand FullName
function Checked([scriptblock]$action) { & $action; if ($LASTEXITCODE) { throw "Command failed with exit $LASTEXITCODE" } }
Checked { & "$Jdk\bin\javac.exe" --release 8 -encoding UTF-8 -cp $android -d $classes @src "$PSScriptRoot\AccountTest.java" }
Checked { & "$Jdk\bin\jar.exe" cf "$work\test.jar" -C $classes . }
Checked { & "$Jdk\bin\java.exe" -cp "$bt\lib\d8.jar" com.android.tools.r8.D8 --min-api 23 --lib $android --output $dex "$work\test.jar" }
Checked { & "$bt\aapt2.exe" link -I $android --manifest "$work\AndroidManifest.xml" -o "$work\account-test.apk" }
Checked { & "$Jdk\bin\jar.exe" uf "$work\account-test.apk" -C $dex classes.dex }
$key=Join-Path ([IO.Path]::GetTempPath()) 'lumencraft-account-test.p12'
if (!(Test-Path $key)) {
    Checked { & "$Jdk\bin\keytool.exe" -genkeypair -keystore $key -storetype PKCS12 -alias test -keyalg RSA -storepass testonly -dname 'CN=Lumen Test Only' -validity 3650 }
}
Checked { & "$Jdk\bin\java.exe" -jar "$bt\lib\apksigner.jar" sign --ks $key --ks-pass pass:testonly "$work\account-test.apk" }
$adb="$Sdk\platform-tools\adb.exe"
$target=@()
if ($Device) { $target=@('-s',$Device) }
Checked { & $adb @target install -r -t "$work\account-test.apk" }
$log=& $adb @target shell am instrument -w dev.lumencraft.test.accounts/dev.lumencraft.account.AccountTest
$log
if (($log -join ' ') -notmatch 'result=PASS') { throw 'Android account regression failed' }
