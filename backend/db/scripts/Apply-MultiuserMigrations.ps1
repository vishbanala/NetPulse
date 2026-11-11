<#
.SYNOPSIS
  Apply NetPulse migrations 007 + 008 in one psql session with secure admin binding.

.DESCRIPTION
  Reads NETPULSE_ADMIN_EMAIL and NETPULSE_ADMIN_PASSWORD_HASH from the environment
  (or -AdminEmail / -AdminPasswordHash parameters). Does not echo secret values,
  connection strings, or password hashes. Stops immediately on error (ON_ERROR_STOP).
  Requires PostgreSQL psql on PATH, or pass -PsqlPath.

.EXAMPLE
  # Set NETPULSE_ADMIN_* and PG* from your secret store first (do not echo them).
  .\Apply-MultiuserMigrations.ps1
#>
[CmdletBinding()]
param(
    [string] $AdminEmail = $env:NETPULSE_ADMIN_EMAIL,
    [string] $AdminPasswordHash = $env:NETPULSE_ADMIN_PASSWORD_HASH,
    [string] $PsqlPath = "psql",
    [string] $MigrationsDir = (Join-Path $PSScriptRoot "..\migrations")
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

if ([string]::IsNullOrWhiteSpace($AdminEmail)) {
    throw "Admin email missing. Set NETPULSE_ADMIN_EMAIL or pass -AdminEmail."
}
if ([string]::IsNullOrWhiteSpace($AdminPasswordHash) -or -not $AdminPasswordHash.StartsWith([string]'$2')) {
    throw "Admin password hash missing or not BCrypt. Set NETPULSE_ADMIN_PASSWORD_HASH or pass -AdminPasswordHash."
}

$psqlCmd = Get-Command $PsqlPath -ErrorAction SilentlyContinue
if (-not $psqlCmd) {
    throw "psql not found at '$PsqlPath'. Install PostgreSQL client tools or pass -PsqlPath."
}
$PsqlPath = $psqlCmd.Source

$bindSql = Join-Path $PSScriptRoot "007_bind_admin_session.sql"
$mig007 = [System.IO.Path]::GetFullPath((Join-Path $MigrationsDir "007_users_and_monitor_ownership.sql"))
$mig008 = [System.IO.Path]::GetFullPath((Join-Path $MigrationsDir "008_monitor_limits.sql"))

foreach ($p in @($bindSql, $mig007, $mig008)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "Missing file: $p" }
}

Write-Host "Applying 007 (session-bound admin) then 008 in a single psql session..."
Write-Host ("Admin email characters: {0}; BCrypt hash accepted (not printed)." -f $AdminEmail.Trim().Length)

# Concatenate -v values so PowerShell does not re-parse '$' inside the BCrypt hash.
$adminEmailArg = "admin_email=" + $AdminEmail.Trim()
$adminHashArg = "admin_hash=" + $AdminPasswordHash.Trim()

& $PsqlPath `
    -v ON_ERROR_STOP=1 `
    -v $adminEmailArg `
    -v $adminHashArg `
    -f $bindSql `
    -f $mig007 `
    -f $mig008

if ($LASTEXITCODE -ne 0) {
    throw "psql failed with exit code $LASTEXITCODE (no further migrations run)."
}

Write-Host "Migrations 007 and 008 applied successfully."
