$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$work=Join-Path ([IO.Path]::GetTempPath()) 'lumencraft-network-check'
$out=New-Item -ItemType Directory -Force (Join-Path $env:TEMP 'lumencraft-local-classes')
$json=Join-Path $work 'android-json.jar'
$expected='DFB7BAE2F404CFE0B72B4D23944698CB716B7665171812A0A4D0F5926C0FAC79'
New-Item -ItemType Directory -Force $work | Out-Null
if (!(Test-Path $json) -or (Get-FileHash $json).Hash -ne $expected) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar' -OutFile $json
}
if ((Get-FileHash $json).Hash -ne $expected) { throw 'Test JSON library checksum mismatch' }
$core="$root/core/src/main/java/dev/lumencraft/core"
& javac --release 8 -Xlint:-options -encoding UTF-8 -cp $json -d $out "$core/MirrorBench.java" "$core/VerifiedDownloads.java" "$core/VanillaInstaller.java" "$core/InstanceFiles.java" "$core/LocalFiles.java" "$core/ClassicSaves.java" "$core/CrashNotes.java" "$core/DeviceProfile.java" "$core/KeyBindingScanner.java" "$PSScriptRoot/LocalOperationsTest.java"
if ($LASTEXITCODE) {exit $LASTEXITCODE}
& java '-Dfile.encoding=UTF-8' -cp "$out;$json" dev.lumencraft.core.LocalOperationsTest
exit $LASTEXITCODE
