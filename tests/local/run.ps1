$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$out=New-Item -ItemType Directory -Force (Join-Path $env:TEMP 'lumencraft-local-classes')
$core="$root/core/src/main/java/dev/lumencraft/core"
& javac --release 8 -Xlint:-options -encoding UTF-8 -d $out "$core/VerifiedDownloads.java" "$core/InstanceFiles.java" "$core/LocalFiles.java" "$core/ClassicSaves.java" "$PSScriptRoot/LocalOperationsTest.java"
if ($LASTEXITCODE) {exit $LASTEXITCODE}
& java -cp $out dev.lumencraft.core.LocalOperationsTest
exit $LASTEXITCODE
