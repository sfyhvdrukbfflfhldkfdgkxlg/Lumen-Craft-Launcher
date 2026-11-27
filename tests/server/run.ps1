param([ValidateSet('fabric','purpur','paper','neoforge','forge')][string]$Distribution='fabric', [string]$MinecraftVersion='1.20.1')
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$src=Join-Path $project 'core/src/main/java/dev/lumencraft/core'
$work=(New-Item -ItemType Directory -Force (Join-Path ([IO.Path]::GetTempPath()) 'lumencraft-distro-test')).FullName
$classes=(New-Item -ItemType Directory -Force (Join-Path $work classes)).FullName
$json=Join-Path $work 'json-20240303.jar'
if (!(Test-Path $json)) { Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $json }
if ((Get-FileHash $json).Hash -ne '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed') { throw 'Test JSON digest mismatch' }
$files=@('MirrorBench.java','ServerDistributions.java','VerifiedDownloads.java','VanillaInstaller.java','FabricInstaller.java','ModLoaderInstaller.java') | ForEach-Object { Join-Path $src $_ }
& javac --release 8 -encoding UTF-8 -cp $json -d $classes @files (Join-Path $PSScriptRoot 'ServerDistributionLiveTest.java')
if ($LASTEXITCODE) { exit $LASTEXITCODE }
& java '-Dfile.encoding=UTF-8' -cp "$json;$classes" dev.lumencraft.core.ServerDistributionLiveTest (Join-Path $work library) $Distribution $MinecraftVersion
exit $LASTEXITCODE
