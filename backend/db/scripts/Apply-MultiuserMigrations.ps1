<#
.SYNOPSIS
  Apply NetPulse migrations 007 + 008 atomically with secure admin binding.

.DESCRIPTION
  Reads NETPULSE_ADMIN_EMAIL and NETPULSE_ADMIN_PASSWORD_HASH from the environment
  (or -AdminEmail / -AdminPasswordHash parameters). Validates both BEFORE any
  database schema changes. Runs bind + 007 + 008 inside one PostgreSQL transaction
  so any failure rolls back all migration DDL/DML.

  Does not echo secret values, connection strings, or password hashes.
  Exits with a nonzero code on validation or psql failure.
  Requires PostgreSQL psql on PATH, or pass -PsqlPath.

  Prefer setting NETPULSE_ADMIN_PASSWORD_HASH in the environment so shells do not
  expand '$' inside BCrypt hashes. If passing -AdminPasswordHash interactively,
  use single quotes.

.EXAMPLE
  # Set NETPULSE_ADMIN_* and PG* from your secret store first (do not echo them).
  .\Apply-MultiuserMigrations.ps1
#>
[CmdletBinding()]
param(
    [string] $AdminEmail = $env:NETPULSE_ADMIN_EMAIL,
    [string] $AdminPasswordHash = $env:NETPULSE_ADMIN_PASSWORD_HASH,
    [string] $PsqlPath = "psql",
    [string] $MigrationsDir = "",
    # Test-only: after 007 succeeds inside the TX, raise and force rollback. Never use in production.
    [switch] $FailAfter007
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

function Exit-Failed {
    param([string] $Message, [int] $Code = 1)
    # Write to stderr without throwing (avoids credential-bearing stack traces).
    [Console]::Error.WriteLine($Message)
    exit $Code
}

$scriptRoot = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($scriptRoot)) {
    $scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
}
if ([string]::IsNullOrWhiteSpace($scriptRoot)) {
    Exit-Failed "Unable to resolve script directory."
}
if ([string]::IsNullOrWhiteSpace($MigrationsDir)) {
    $MigrationsDir = Join-Path $scriptRoot "..\migrations"
}

# --- Preflight: reject bad admin credentials before touching the schema ---
if ([string]::IsNullOrWhiteSpace($AdminEmail)) {
    Exit-Failed "Admin email missing. Set NETPULSE_ADMIN_EMAIL or pass -AdminEmail."
}

$trimmedEmail = $AdminEmail.Trim()
if ($trimmedEmail.IndexOf('@') -lt 1) {
    Exit-Failed "Admin email must look like an email address (contains @)."
}

if ([string]::IsNullOrWhiteSpace($AdminPasswordHash)) {
    Exit-Failed "Admin password hash missing. Set NETPULSE_ADMIN_PASSWORD_HASH or pass -AdminPasswordHash."
}

$trimmedHash = $AdminPasswordHash.Trim()
# Match Spring BCryptPasswordEncoder output shape without printing the value.
$bcryptPattern = '^\$2[aby]?\$\d{2}\$[./A-Za-z0-9]{53}$'
if ($trimmedHash -notmatch $bcryptPattern) {
    Exit-Failed "Admin password hash is not a valid BCrypt hash (expected `$2a$/`$2b$/`$2y$ with cost and 53-char salt+hash)."
}

$psqlCmd = Get-Command $PsqlPath -ErrorAction SilentlyContinue
if (-not $psqlCmd) {
    Exit-Failed "psql not found at '$PsqlPath'. Install PostgreSQL client tools or pass -PsqlPath."
}
$PsqlPath = $psqlCmd.Source

$bindSql = Join-Path $scriptRoot "007_bind_admin_session.sql"
$mig007 = [System.IO.Path]::GetFullPath((Join-Path $MigrationsDir "007_users_and_monitor_ownership.sql"))
$mig008 = [System.IO.Path]::GetFullPath((Join-Path $MigrationsDir "008_monitor_limits.sql"))

foreach ($p in @($bindSql, $mig007, $mig008)) {
    if (-not (Test-Path -LiteralPath $p)) { Exit-Failed "Missing file: $p" }
}

Write-Host "Applying 007 then 008 in one PostgreSQL transaction (session-bound admin)..."
Write-Host ("Admin email characters: {0}; BCrypt hash accepted (not printed)." -f $trimmedEmail.Length)

# Concatenate -v values so PowerShell does not re-parse '$' inside the BCrypt hash.
$adminEmailArg = "admin_email=" + $trimmedEmail
$adminHashArg = "admin_hash=" + $trimmedHash

# Absolute paths with forward slashes for psql \i inside the driver.
$bindSqlPg = ($bindSql -replace '\\', '/')
$mig007Pg = ($mig007 -replace '\\', '/')
$mig008Pg = ($mig008 -replace '\\', '/')

$driverPath = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), ("netpulse-mig-" + [guid]::NewGuid().ToString("n") + ".sql"))
try {
    $driverLines = New-Object System.Collections.Generic.List[string]
    [void]$driverLines.Add("BEGIN;")
    [void]$driverLines.Add("\i '$bindSqlPg'")
    [void]$driverLines.Add("\i '$mig007Pg'")
    if ($FailAfter007) {
        [void]$driverLines.Add("DO `$`$ BEGIN RAISE EXCEPTION 'intentional test failure after 007'; END `$`$;")
    }
    [void]$driverLines.Add("\i '$mig008Pg'")
    [void]$driverLines.Add("COMMIT;")

    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($driverPath, ($driverLines -join "`n") + "`n", $utf8NoBom)

    & $PsqlPath `
        -v ON_ERROR_STOP=1 `
        -v $adminEmailArg `
        -v $adminHashArg `
        -f $driverPath

    $psqlExit = $LASTEXITCODE
    if ($psqlExit -ne 0) {
        Exit-Failed "psql failed with exit code $psqlExit; transaction was not committed (no migration changes retained)." $psqlExit
    }
}
finally {
    if (Test-Path -LiteralPath $driverPath) {
        Remove-Item -LiteralPath $driverPath -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "Migrations 007 and 008 applied successfully."
exit 0
