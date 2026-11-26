param([string]$Ndk=$env:ANDROID_NDK_HOME)
$ErrorActionPreference='Stop'
if (!$Ndk -and (Test-Path 'D:\LumenCraft-build-tools\android-ndk-r24')) { $Ndk='D:\LumenCraft-build-tools\android-ndk-r24' }
if (!$Ndk) { throw 'Set ANDROID_NDK_HOME to an Android NDK installation' }
$clang=Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
$target=@{'arm64-v8a'='aarch64-linux-android23';'armeabi-v7a'='armv7a-linux-androideabi23';'x86'='i686-linux-android23';'x86_64'='x86_64-linux-android23'}
foreach ($abi in $target.Keys){
    $dir=Join-Path $PSScriptRoot ("..\src\main\jniLibs\"+$abi)
    New-Item -ItemType Directory -Force $dir | Out-Null
    & $clang ("--target="+$target[$abi]) -fuse-ld=lld -O2 -Werror -Wall -shared -fPIC '-Wl,-z,global,-z,relro,-z,now,-z,max-page-size=16384,-soname,libroomnet23.so' (Join-Path $PSScriptRoot interfaces23.c) -o (Join-Path $dir libroomnet23.so)
    if($LASTEXITCODE){exit $LASTEXITCODE}
}
