# Windows helper when GNU Make is not on PATH.
# Requires: MSYS2 UCRT64 gcc + libcurl development package.

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Build = Join-Path $Root "build"
$GccCandidates = @(
    "C:\msys64\ucrt64\bin\gcc.exe",
    "gcc.exe"
)

$Gcc = $null
foreach ($c in $GccCandidates) {
    if ($c -eq "gcc.exe") {
        $cmd = Get-Command gcc.exe -ErrorAction SilentlyContinue
        if ($cmd) { $Gcc = $cmd.Source; break }
    } elseif (Test-Path $c) {
        $Gcc = $c
        break
    }
}

if (-not $Gcc) {
    Write-Error "gcc not found. Install MSYS2 UCRT64 toolchain first."
}

$CurlHeader = "C:\msys64\ucrt64\include\curl\curl.h"
if (-not (Test-Path $CurlHeader)) {
    Write-Error @"
libcurl headers not found at $CurlHeader

Install from an MSYS2 UCRT64 shell (ask before running if you want me to do it):
  pacman -S --needed mingw-w64-ucrt-x86_64-curl make
"@
}

New-Item -ItemType Directory -Force -Path $Build | Out-Null

$Include = @("-I`"$Root\include`"", "-I`"$Root\src`"")
$Cflags = @("-std=c11", "-Wall", "-Wextra", "-O2") + $Include
$Libs = @("-lcurl", "-lpthread", "-lws2_32")

Write-Host "Using compiler: $Gcc"

& $Gcc @Cflags -c (Join-Path $Root "src\url_guard.c") -o (Join-Path $Build "url_guard.o")
& $Gcc @Cflags -c (Join-Path $Root "src\monitor.c") -o (Join-Path $Build "monitor.o")
& $Gcc @Cflags -c (Join-Path $Root "src\main.c") -o (Join-Path $Build "main.o")
& $Gcc @Cflags -c (Join-Path $Root "tests\test_monitor.c") -o (Join-Path $Build "test_monitor.o")

& $Gcc `
    (Join-Path $Build "main.o") `
    (Join-Path $Build "monitor.o") `
    (Join-Path $Build "url_guard.o") `
    -o (Join-Path $Build "netpulse-monitor.exe") `
    @Libs

& $Gcc `
    (Join-Path $Build "test_monitor.o") `
    (Join-Path $Build "monitor.o") `
    (Join-Path $Build "url_guard.o") `
    -o (Join-Path $Build "test_monitor.exe") `
    @Libs

Write-Host "Built:"
Write-Host "  $Build\netpulse-monitor.exe"
Write-Host "  $Build\test_monitor.exe"
