param([switch]$Upstream)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$work=Join-Path ([IO.Path]::GetTempPath()) 'lumencraft-network-check'
$classes=Join-Path $work 'controls-classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$json=Join-Path $work 'android-json.jar'
$expected='DFB7BAE2F404CFE0B72B4D23944698CB716B7665171812A0A4D0F5926C0FAC79'
if (!(Test-Path $json) -or (Get-FileHash $json).Hash -ne $expected) {
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar' -OutFile $json
}
if ((Get-FileHash $json).Hash -ne $expected) { throw 'Test JSON library checksum mismatch' }
$sdk=$env:ANDROID_HOME
if (!$sdk) { $sdk='D:\apk\.tools\android-sdk' }
$android=Join-Path $sdk 'platforms\android-35\android.jar'
$core=Join-Path $root 'core\src\main\java\dev\lumencraft\core'
$coreFiles='MirrorBench.java','VerifiedDownloads.java','KeyBindingScanner.java' | ForEach-Object {Join-Path $core $_}
$app=Join-Path $root 'app\src\main\java\dev\lumencraft\client'
$files='TextKeys.java','ControlProfile.java','ControlOverlay.java' | ForEach-Object {Join-Path $app $_}
& javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$json;$android" -d $classes @coreFiles @files (Join-Path $PSScriptRoot 'ControlsTest.java')
if ($LASTEXITCODE) {exit $LASTEXITCODE}
$arguments=@()
if ($Upstream) {
    $sample=Join-Path $work 'fcl-v21-input.json'
    $hash='C1309B6D4104BD8E1D47552882D788123B39CC3809ECB40C9F19B5198B6D4A9F'
    if (!(Test-Path $sample) -or (Get-FileHash $sample).Hash -ne $hash) {
        Invoke-WebRequest 'https://raw.githubusercontent.com/FCL-Team/FoldCraftLauncher/617ab175789c3f5436943892b6f53904e9404a99/FCL/src/main/assets/controllers/00000000.json' -OutFile $sample
    }
    if ((Get-FileHash $sample).Hash -ne $hash) {throw 'FCL test fixture checksum mismatch'}
    $arguments+= $sample
}
& java -cp "$classes;$json;$android" dev.lumencraft.client.ControlsTest @arguments
exit $LASTEXITCODE
