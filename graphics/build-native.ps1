param([string]$Ndk = $env:ANDROID_NDK_HOME, [string]$Rustc = "rustc")
$ErrorActionPreference = "Stop"
if (!$Ndk) { throw "Supply -Ndk or ANDROID_NDK_HOME" }
$compiler = Join-Path $Ndk "toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
$work = New-Item -ItemType Directory -Force "$PSScriptRoot/build"
$arch = @(
    @('arm64-v8a','aarch64-linux-android','aarch64-linux-android23'),
    @('armeabi-v7a','armv7-linux-androideabi','armv7a-linux-androideabi23'),
    @('x86','i686-linux-android','i686-linux-android23'),
    @('x86_64','x86_64-linux-android','x86_64-linux-android23')
)
foreach ($a in $arch) {
    $obj = "$work/topology-$($a[0]).o"
    & $Rustc --edition 2021 --target $a[1] --crate-type lib --emit obj -C opt-level=2 -C panic=abort "$PSScriptRoot/topology.rs" -o $obj
    if ($LASTEXITCODE) { throw "Rust build failed" }
    $dst=New-Item -ItemType Directory -Force "$PSScriptRoot/../app/src/main/jniLibs/$($a[0])"
    & $compiler "--target=$($a[2])" -fuse-ld=lld -std=c++17 -O2 -fPIC -shared -fno-exceptions -fno-rtti -nostdlib++ -Wall -Wextra -Werror '-Wl,--no-undefined,-z,max-page-size=16384,-soname,liblumengl.so' "$PSScriptRoot/legacy.cpp" "$PSScriptRoot/check.cpp" $obj -lEGL -lGLESv2 -lm -o "$dst/liblumengl.so"
    if ($LASTEXITCODE) { throw "NDK build failed" }
}
