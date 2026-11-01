# Offline test double, never invokes ADB or accesses a device.
param([string]$s, [Parameter(ValueFromRemainingArguments)][string[]]$ReadArguments)
$ErrorActionPreference = 'Stop'
if ($s -ne 'synthetic-test' -or -not $env:FGO_CHAPTER_CAPTURE_TEST_ROOT) { throw 'Offline fixture invocation only' }
$global:LASTEXITCODE = 0
$root = [IO.Path]::GetFullPath($env:FGO_CHAPTER_CAPTURE_TEST_ROOT)
[IO.File]::AppendAllText((Join-Path $root 'adb-read-calls.txt'), ($ReadArguments -join ' ') + "`n")
function Fixture-Bytes([string]$Name) { return ,[Text.Encoding]::UTF8.GetBytes("synthetic fixture: $Name") }
if ($ReadArguments[0] -eq 'get-state') { 'device'; return }
if ($ReadArguments[0] -eq 'pull') {
    $target = [IO.Path]::GetFullPath($ReadArguments[2])
    if (-not $target.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture pull escaped test root' }
    [IO.File]::WriteAllBytes($target, (Fixture-Bytes ([IO.Path]::GetFileName($ReadArguments[1]))))
    'synthetic pull'; return
}
if ($ReadArguments[0] -ne 'shell') { throw 'Unexpected fixture command' }
switch ($ReadArguments[1]) {
    'pidof' { $global:LASTEXITCODE = 1; return }
    'dumpsys' { "versionName=synthetic versionCode=1`n"; return }
    'getprop' { '34'; return }
    'ls' { 'cfb1d36393fd67385e046b084b7cf7ed'; return }
    'sha256sum' {
        $hash = Get-StoryByteHash (Fixture-Bytes ([IO.Path]::GetFileName($ReadArguments[2])))
        "$hash  $($ReadArguments[2])"; return
    }
    'stat' { (Fixture-Bytes ([IO.Path]::GetFileName($ReadArguments[4]))).Length.ToString(); return }
    default { throw 'Unexpected fixture command; no arbitrary shell execution' }
}
