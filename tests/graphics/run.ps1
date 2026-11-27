$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$work = Join-Path ([IO.Path]::GetTempPath()) 'lumencraft-graphics-tests'
New-Item -ItemType Directory -Force $work | Out-Null
$rustc = if (Get-Command rustc -ErrorAction SilentlyContinue) { 'rustc' }
         elseif (Test-Path 'D:\LumenCraft-build-tools\cargo\bin\rustc.exe') {
             $env:RUSTUP_HOME = 'D:\LumenCraft-build-tools\rustup'
             $env:CARGO_HOME = 'D:\LumenCraft-build-tools\cargo'
             'D:\LumenCraft-build-tools\cargo\bin\rustc.exe'
         } else { throw 'rustc is required for graphics unit tests' }
$bin = Join-Path $work 'topology-tests.exe'
& $rustc --edition 2021 --test (Join-Path $root 'graphics\topology.rs') -o $bin
if ($LASTEXITCODE) { exit $LASTEXITCODE }
& $bin
exit $LASTEXITCODE
