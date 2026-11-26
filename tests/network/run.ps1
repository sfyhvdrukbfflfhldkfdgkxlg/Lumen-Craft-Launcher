param([switch]$Live)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$work=Join-Path ([IO.Path]::GetTempPath()) 'lumencraft-network-check'
$classes=Join-Path $work 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$json=Join-Path $work 'android-json.jar'
$expected='DFB7BAE2F404CFE0B72B4D23944698CB716B7665171812A0A4D0F5926C0FAC79'
if (!(Test-Path $json) -or (Get-FileHash $json).Hash -ne $expected) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar' -OutFile $json
}
if ((Get-FileHash $json).Hash -ne $expected) { throw 'Test JSON library checksum mismatch' }
$source = Join-Path $root 'core\src\main\java\dev\lumencraft\core'
$files = @('Modrinth','ModrinthFiles','ServerProbe','ServerPack','SrvLookup','PackImport','FabricInstaller','VanillaInstaller','VerifiedDownloads') | ForEach-Object { Join-Path $source ($_+'.java') }
& javac --release 8 -Xlint:-options -encoding UTF-8 -classpath $json -d $classes @files (Join-Path $PSScriptRoot NetworkTest.java) (Join-Path $PSScriptRoot PackTest.java)
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$arguments = @()
if ($Live) { $arguments += '--live' }
foreach ($name in 'NetworkTest','PackTest') {
    & java '-Dfile.encoding=UTF-8' -cp "$classes;$json" "dev.lumencraft.core.$name" @arguments
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
}
