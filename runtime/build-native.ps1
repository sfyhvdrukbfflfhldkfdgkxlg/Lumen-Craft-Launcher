param([string]$Ndk = $env:ANDROID_NDK_HOME, [string]$CxxNdk, [switch]$RefreshCxx)
$ErrorActionPreference = "Stop"
if (!$Ndk) { throw "Pass -Ndk <Android NDK path> or set ANDROID_NDK_HOME" }
$toolchain = Join-Path $Ndk "toolchains/llvm/prebuilt/windows-x86_64"
$compiler = Join-Path $toolchain "bin/clang.exe"
$src = Join-Path $PSScriptRoot "src/main/cpp"
$jni = Join-Path $PSScriptRoot "../app/src/main/jniLibs"
$targets = [ordered]@{ 'arm64-v8a'='aarch64-linux-android21'; 'armeabi-v7a'='armv7a-linux-androideabi21'; 'x86_64'='x86_64-linux-android21'; 'x86'='i686-linux-android21' }
foreach ($pair in $targets.GetEnumerator()) {
    $out = New-Item -ItemType Directory -Force (Join-Path $jni $pair.Key)
    $flags = @("--target=$($pair.Value)",'-fuse-ld=lld','-O2','-Wall','-Wextra','-Werror','-fvisibility=hidden','-fstack-protector-strong','-ffunction-sections','-fdata-sections','-Wl,--gc-sections','-Wl,-z,relro,-z,now','-Wl,-z,max-page-size=16384','-nostdlib++','-fno-exceptions','-fno-rtti')
    & $compiler @flags -shared -fPIC "$src/probe.cpp" -lEGL -lGLESv2 '-Wl,-soname,liblumencap.so' -o "$out/liblumencap.so"
    if ($LASTEXITCODE) { throw "Graphics build failed: $($pair.Key)" }
    & $compiler @flags -fPIE -pie "$src/headless.cpp" -ldl -o "$out/liblumenjvm.so"
    if ($LASTEXITCODE) { throw "JVM host build failed: $($pair.Key)" }
}



# The source checkout includes pinned prebuilt Java 25 companions. Refresh explicitly
# or rebuild them automatically on a stripped-down checkout missing these files.
$vendor = Join-Path $PSScriptRoot 'tools/vendor-cxx.py'
$needed = @('arm64-v8a','armeabi-v7a','x86_64') | Where-Object {
    !(Test-Path -LiteralPath (Join-Path $jni "$_/libc++_shared.so"))
}
if ($CxxNdk -or $RefreshCxx -or $needed -or !(Test-Path (Join-Path $PSScriptRoot '../app/src/main/assets/licenses/Android-NDK-r28c-NOTICE.txt'))) {
    $extra = if ($CxxNdk) { @('--ndk28',$CxxNdk) } else { @() }
    & python $vendor --ndk24 $Ndk @extra
    if ($LASTEXITCODE) { throw 'Failed to reproduce the pinned libc++ shared libraries' }
}
