<#
.SYNOPSIS
  Isolated atomicity tests for Apply-MultiuserMigrations.ps1 against a production dump.

.DESCRIPTION
  Creates disposable Docker Postgres clones from a custom-format dump (never modifies
  the dump or production). Runs failure cases and one success case.

  Does not print credentials or connection secrets.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $DumpPath,

    [string] $DockerImage = "postgres:17",
    [string] $BaseContainerName = "netpulse-mig-atomicity",
    [string] $AdminEmail = "migration-test-admin@localhost.invalid"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$applyScript = Join-Path $repoRoot "backend\db\scripts\Apply-MultiuserMigrations.ps1"

if (-not (Test-Path -LiteralPath $DumpPath)) { throw "Dump not found: $DumpPath" }
if (-not (Test-Path -LiteralPath $applyScript)) { throw "Apply script missing: $applyScript" }

function Get-DisposableBcryptHash {
    $work = Join-Path $env:TEMP ("np-bcrypt-" + [guid]::NewGuid().ToString("n"))
    New-Item -ItemType Directory -Path $work | Out-Null
    try {
        $py = @(
            'import bcrypt, secrets',
            'h = bcrypt.hashpw(secrets.token_urlsafe(24).encode(), bcrypt.gensalt(rounds=10)).decode()',
            "open('/out/hash.txt','w').write(h)",
            "open('/out/meta.txt','w').write('len=%d prefix=%s' % (len(h), h[:4]))"
        ) -join "`n"
        Set-Content -LiteralPath (Join-Path $work "gen.py") -Value $py -Encoding ASCII
        docker run --rm -v "${work}:/out" python:3.12-slim `
            bash -lc "pip install -q bcrypt >/dev/null 2>&1 && python /out/gen.py"
        if ($LASTEXITCODE -ne 0) { throw "docker bcrypt generation failed." }
        $meta = Get-Content -LiteralPath (Join-Path $work "meta.txt") -ErrorAction SilentlyContinue
        if ($meta) { Write-Host "bcrypt_meta=$meta" }
        $hash = (Get-Content -LiteralPath (Join-Path $work "hash.txt") -Raw).Trim()
        if ([string]::IsNullOrWhiteSpace($hash)) { throw "BCrypt generation returned empty hash." }
        return $hash
    }
    finally {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Remove-TestContainer {
    param([string] $Name)
    docker rm -f $Name 2>$null | Out-Null
}

function New-RestoreContainer {
    param([string] $Name, [int] $HostPort)

    Remove-TestContainer $Name
    docker run -d --name $Name `
        -e POSTGRES_PASSWORD=localtest `
        -e POSTGRES_USER=postgres `
        -e POSTGRES_DB=postgres `
        -p "${HostPort}:5432" `
        $DockerImage | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to start container $Name" }

    for ($i = 0; $i -lt 60; $i++) {
        docker exec $Name pg_isready -U postgres -d postgres 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
        if ($i -eq 59) { throw "Container $Name not ready" }
    }

    docker cp $DumpPath "${Name}:/backup.dump"
    if ($LASTEXITCODE -ne 0) { throw "docker cp dump failed for $Name" }

    docker exec -e PGPASSWORD=localtest $Name `
        pg_restore -U postgres --dbname=postgres --no-owner --no-acl --exit-on-error /backup.dump
    if ($LASTEXITCODE -ne 0) { throw "pg_restore failed for $Name" }
}

function Get-LegacyProbe {
    param([string] $Name)

    $sql = @"
SELECT
  (SELECT COUNT(*) FROM monitors) AS monitors,
  (SELECT COUNT(*) FROM monitoring_checks) AS checks,
  (SELECT COUNT(*) FROM incidents) AS incidents,
  (SELECT COUNT(*) FROM monitor_schedules) AS schedules,
  EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema='public' AND table_name='users'
  ) AS has_users,
  EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='monitors' AND column_name='user_id'
  ) AS has_user_id,
  EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema='public' AND table_name='email_verification_tokens'
  ) AS has_email_tokens,
  EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema='public' AND table_name='password_reset_tokens'
  ) AS has_reset_tokens;
"@
    $out = docker exec -e PGPASSWORD=localtest $Name `
        psql -U postgres -d postgres -At -F ',' -c $sql
    if ($LASTEXITCODE -ne 0) { throw "probe query failed on $Name" }
    $parts = (($out | Select-Object -First 1).ToString()).Split(',')
    return [pscustomobject]@{
        Monitors       = [int]$parts[0]
        Checks         = [int]$parts[1]
        Incidents      = [int]$parts[2]
        Schedules      = [int]$parts[3]
        HasUsers       = ($parts[4] -eq 't')
        HasUserId      = ($parts[5] -eq 't')
        HasEmailTokens = ($parts[6] -eq 't')
        HasResetTokens = ($parts[7] -eq 't')
    }
}

function Assert-Unmigrated {
    param([string] $Name, [string] $Case)

    $p = Get-LegacyProbe $Name
    $ok = ($p.Monitors -eq 3 -and $p.Checks -eq 30 -and $p.Incidents -eq 1 -and $p.Schedules -eq 3 `
            -and -not $p.HasUsers -and -not $p.HasUserId -and -not $p.HasEmailTokens -and -not $p.HasResetTokens)
    if (-not $ok) {
        throw "[$Case] expected clean legacy schema; got: $($p | ConvertTo-Json -Compress)"
    }
    Write-Host "[$Case] PASS - legacy schema intact (3/30/1/3, no users/user_id)."
}

function Invoke-ApplyChild {
    param(
        [int] $HostPort,
        [AllowNull()][string] $Email,
        [AllowNull()][string] $Hash,
        [switch] $FailAfter007,
        [switch] $OmitEmail,
        [switch] $OmitHash
    )

    # Pass credentials only via env so BCrypt '$' is never shell-expanded on the command line.
    $argList = New-Object System.Collections.Generic.List[string]
    [void]$argList.Add("-NoProfile")
    [void]$argList.Add("-ExecutionPolicy")
    [void]$argList.Add("Bypass")
    [void]$argList.Add("-File")
    [void]$argList.Add($applyScript)
    if ($FailAfter007) {
        [void]$argList.Add("-FailAfter007")
    }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = (Get-Command powershell).Source
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    if ($OmitEmail) {
        $psi.EnvironmentVariables["NETPULSE_ADMIN_EMAIL"] = ""
    } else {
        $psi.EnvironmentVariables["NETPULSE_ADMIN_EMAIL"] = $(if ($null -eq $Email) { "" } else { $Email })
    }
    if ($OmitHash) {
        $psi.EnvironmentVariables["NETPULSE_ADMIN_PASSWORD_HASH"] = ""
    } else {
        $psi.EnvironmentVariables["NETPULSE_ADMIN_PASSWORD_HASH"] = $(if ($null -eq $Hash) { "" } else { $Hash })
    }
    $psi.EnvironmentVariables["PGHOST"] = "127.0.0.1"
    $psi.EnvironmentVariables["PGPORT"] = "$HostPort"
    $psi.EnvironmentVariables["PGUSER"] = "postgres"
    $psi.EnvironmentVariables["PGDATABASE"] = "postgres"
    $psi.EnvironmentVariables["PGPASSWORD"] = "localtest"
    $psi.EnvironmentVariables["PGSSLMODE"] = "disable"

    $quoted = foreach ($a in $argList) {
        if ($a -match '[\s"]') { '"' + ($a -replace '"', '\"') + '"' } else { $a }
    }
    $psi.Arguments = [string]::Join(" ", $quoted)

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    [void]$proc.Start()
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()

    foreach ($line in ($stdout -split "`r?`n")) {
        if ($line -match 'Applying 007|Migrations 007|Admin email characters|Admin email missing|password hash|psql failed|BCrypt|email must look') {
            Write-Host $line
        }
    }
    foreach ($line in ($stderr -split "`r?`n")) {
        if (-not [string]::IsNullOrWhiteSpace($line) -and $line -notmatch '\$2[aby]?\$') {
            Write-Host $line
        }
    }
    return $proc.ExitCode
}

function New-CaseContainer {
    param([string] $Suffix, [int] $Offset)
    $name = "$BaseContainerName-$Suffix"
    $port = 55440 + $Offset
    New-RestoreContainer -Name $name -HostPort $port
    return @{ Name = $name; Port = $port }
}

$results = New-Object System.Collections.Generic.List[string]
$validHash = $null
$successContainer = $null

try {
    Write-Host "=== Failure: missing admin email ==="
    $c = New-CaseContainer "missing-email" 1
    Assert-Unmigrated $c.Name "pre:missing-email"
    $code = Invoke-ApplyChild -HostPort $c.Port -OmitEmail -Hash ('$2a$10$' + ('A' * 53))
    if ($code -eq 0) { throw "missing-email expected nonzero exit" }
    Assert-Unmigrated $c.Name "missing-email"
    [void]$results.Add("missing-email: FAIL_AS_EXPECTED exit=$code clean=yes")
    Remove-TestContainer $c.Name

    Write-Host "=== Failure: empty hash ==="
    $c = New-CaseContainer "empty-hash" 2
    Assert-Unmigrated $c.Name "pre:empty-hash"
    $code = Invoke-ApplyChild -HostPort $c.Port -Email $AdminEmail -OmitHash
    if ($code -eq 0) { throw "empty-hash expected nonzero exit" }
    Assert-Unmigrated $c.Name "empty-hash"
    [void]$results.Add("empty-hash: FAIL_AS_EXPECTED exit=$code clean=yes")
    Remove-TestContainer $c.Name

    Write-Host "=== Failure: invalid BCrypt hash ==="
    $c = New-CaseContainer "bad-hash" 3
    Assert-Unmigrated $c.Name "pre:bad-hash"
    $code = Invoke-ApplyChild -HostPort $c.Port -Email $AdminEmail -Hash '$2a$10$not-a-real-bcrypt-payload'
    if ($code -eq 0) { throw "bad-hash expected nonzero exit" }
    Assert-Unmigrated $c.Name "bad-hash"
    [void]$results.Add("bad-hash: FAIL_AS_EXPECTED exit=$code clean=yes")
    Remove-TestContainer $c.Name

    Write-Host "=== Failure: intentional SQL error after 007 begins ==="
    $validHash = Get-DisposableBcryptHash
    $c = New-CaseContainer "fail-after-007" 4
    Assert-Unmigrated $c.Name "pre:fail-after-007"
    $code = Invoke-ApplyChild -HostPort $c.Port -Email $AdminEmail -Hash $validHash -FailAfter007
    if ($code -eq 0) { throw "fail-after-007 expected nonzero exit" }
    Assert-Unmigrated $c.Name "fail-after-007"
    [void]$results.Add("fail-after-007: FAIL_AS_EXPECTED exit=$code clean=yes")
    Remove-TestContainer $c.Name

    Write-Host "=== Success: full 007+008 on fresh restore ==="
    $c = New-CaseContainer "success" 5
    Assert-Unmigrated $c.Name "pre:success"
    $code = Invoke-ApplyChild -HostPort $c.Port -Email $AdminEmail -Hash $validHash
    if ($code -ne 0) { throw "success case expected exit 0, got $code" }

    $probe = Get-LegacyProbe $c.Name
    if (-not ($probe.HasUsers -and $probe.HasUserId)) {
        throw "success case missing users/user_id"
    }
    if ($probe.Monitors -ne 3 -or $probe.Checks -ne 30 -or $probe.Incidents -ne 1 -or $probe.Schedules -ne 3) {
        throw "success case count mismatch: $($probe | ConvertTo-Json -Compress)"
    }

    $ownSql = @"
SELECT COUNT(*) FILTER (WHERE user_id IS NULL) AS null_owners,
       COUNT(*) AS monitors,
       (SELECT COUNT(*) FROM users) AS users,
       (SELECT role FROM users LIMIT 1) AS role,
       COUNT(*) FILTER (WHERE EXISTS (
         SELECT 1 FROM users u WHERE u.id = monitors.user_id AND u.role = 'ADMIN'
       )) AS admin_owned,
       COUNT(*) FILTER (WHERE interval_seconds < 300) AS below_300,
       EXISTS (SELECT 1 FROM pg_constraint WHERE conname='monitors_user_id_fkey') AS has_fk
FROM monitors;
"@
    $own = docker exec -e PGPASSWORD=localtest $c.Name `
        psql -U postgres -d postgres -At -F ',' -c $ownSql
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($own)) {
        throw "success ownership query failed: $own"
    }
    $op = (($own | Select-Object -First 1).ToString()).Split(',')
    if ($op[0] -ne '0' -or $op[1] -ne '3' -or $op[2] -ne '1' -or $op[3] -ne 'ADMIN' -or $op[4] -ne '3' -or $op[5] -ne '0' -or $op[6] -ne 't') {
        throw "success ownership/constraints mismatch: $own"
    }

    Write-Host "[success] PASS - 3/30/1/3 preserved; all monitors owned by ADMIN; FK present; intervals >= 300."
    [void]$results.Add("success: PASS exit=0 counts=3/30/1/3 owner=ADMIN fk=yes")
    $successContainer = $c
    Write-Host "Success container left running: $($c.Name) on port $($c.Port)"
}
catch {
    Write-Host "TEST HARNESS ERROR: $_"
    exit 1
}

Write-Host ""
Write-Host "=== Summary ==="
foreach ($r in $results) { Write-Host $r }
if ($null -eq $successContainer) { exit 1 }
exit 0
