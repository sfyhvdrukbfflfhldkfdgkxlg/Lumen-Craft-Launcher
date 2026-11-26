param(
    [Parameter(Mandatory=$true)] [string]$JavaHome,
    [Parameter(Mandatory=$true)] [int]$Major,
    [Parameter(Mandatory=$true)] [string]$Abi,
    [string]$Adb = 'adb',
    [string]$Serial
)
# Tests the Android JVM invocation path using a supplied unpacked Bionic OpenJDK.
# The isolated remote directory is printed for subsequent inspection.
$ErrorActionPreference = 'Stop'
$adbArgs = if ($Serial) { @('-s', $Serial) } else { @() }
if ($Abi -notin @('arm64-v8a','armeabi-v7a','x86_64','x86')) { throw 'Unknown ABI' }
if (!(Test-Path -LiteralPath "$JavaHome/lib")) { throw 'Expected a Java home with a lib directory' }
$hostBin = Join-Path $PSScriptRoot "../../app/src/main/jniLibs/$Abi/liblumenjvm.so"
$vm = Get-ChildItem -LiteralPath "$JavaHome/lib" -Filter libjvm.so -Recurse | Select-Object -First 1
if (!$vm) { throw 'Missing libjvm.so' }
$rel = $vm.FullName.Substring((Resolve-Path -LiteralPath $JavaHome).Path.Length+1).Replace('\','/')
if ($rel -notmatch '^lib/([a-zA-Z0-9_]+/)?(client|server)/libjvm\.so$') { throw 'Unsupported OpenJDK layout' }
$folder = "/data/local/tmp/lumencraft-qa-$([guid]::NewGuid().ToString('N'))"
& $Adb @adbArgs shell "mkdir -p $folder/java"
if ($LASTEXITCODE) { throw 'Cannot create test directory' }
& $Adb @adbArgs push "$JavaHome/." "$folder/java/"
if ($LASTEXITCODE) { throw 'Runtime transfer failed' }
& $Adb @adbArgs push $hostBin "$folder/liblumenjvm.so"
if ($LASTEXITCODE) { throw 'Host transfer failed' }
$vmFolder = $rel.Substring(0,$rel.LastIndexOf('/'))
$archFolder = $vmFolder.Substring(0,$vmFolder.LastIndexOf('/'))
Write-Output "Device test directory $folder"
$environment = "LD_LIBRARY_PATH=$folder/java/${vmFolder}:$folder/java/${archFolder}:$folder/java/${archFolder}/jli:$folder/java/lib JAVA_HOME=$folder/java HOME=$folder TMPDIR=$folder"
& $Adb @adbArgs shell "chmod 755 $folder/liblumenjvm.so && cd $folder && $environment $folder/liblumenjvm.so $folder/java/$rel $folder/java $Major 64 --probe"
if ($LASTEXITCODE) { throw 'JNI invocation or major version check failed' }
