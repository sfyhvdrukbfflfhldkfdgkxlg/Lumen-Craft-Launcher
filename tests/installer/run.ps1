param([string[]]$LiveCases = @())
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$work = Join-Path ([IO.Path]::GetTempPath()) 'lumencraft-installer-tests'
$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$json = Join-Path $work 'json-20240303.jar'
$expected = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if (!(Test-Path $json) -or (Get-FileHash $json).Hash -ne $expected) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $json
}
if ((Get-FileHash $json).Hash -ne $expected) { throw 'Test JSON dependency checksum mismatch' }
$source = Join-Path $root 'core\src\main\java\dev\lumencraft\core'
$files = @('MirrorBench.java','VanillaInstaller.java','VerifiedDownloads.java','FabricInstaller.java','ModLoaderInstaller.java','LaunchPlan.java','ServerDistributions.java','ClassicInstall.java') | ForEach-Object { Join-Path $source $_ }
& javac --release 8 -Xlint:-options -encoding UTF-8 -cp $json -d $classes @files (Join-Path $PSScriptRoot 'InstallerTest.java')
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$argsForTest = @()
if ($LiveCases.Count) { $argsForTest = @((Join-Path $work 'live')) + $LiveCases }
& java '-Dfile.encoding=UTF-8' -cp "$json;$classes" dev.lumencraft.core.InstallerTest @argsForTest
exit $LASTEXITCODE
