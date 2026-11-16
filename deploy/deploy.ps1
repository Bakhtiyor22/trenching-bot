<#
.SYNOPSIS
Builds the jar from a clean commit and deploys it to the Oracle Cloud server, or rolls back.

.PARAMETER Server
An ssh host alias from ~/.ssh/config (for example trenching-oci).

.PARAMETER Rollback
Repoints current.jar to the previous release and restarts. Builds nothing.

.PARAMETER SkipTests
Builds with assemble instead of build. Use only when the same commit already passed build.

.EXAMPLE
.\deploy\deploy.ps1 -Server trenching-oci
.\deploy\deploy.ps1 -Server trenching-oci -Rollback
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._@-]*$')]
    [string]$Server,

    [switch]$Rollback,

    [switch]$SkipTests
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$LibsDir = Join-Path $RepoRoot 'build/libs'

# Runs on the server as root. Mode deploy: <sha> <uploaded jar>. Mode rollback: no arguments.
$RemoteScript = @'
#!/usr/bin/env bash
set -euo pipefail
trap 'rm -f -- "$0"' EXIT

readonly BASE=/opt/trenching-bot
readonly SERVICE=trenching-bot
readonly KEEP_RELEASES=5
mode="$1"

point_current() {
  ln -sfn "$1" "$BASE/current.jar.next"
  mv -T "$BASE/current.jar.next" "$BASE/current.jar"
}

wait_healthy() {
  local deadline=$((SECONDS + 180))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -fsS --max-time 5 http://127.0.0.1:8081/actuator/health 2>/dev/null | grep -Eq '"status" ?: ?"UP"'; then
      echo "Health is UP."
      return 0
    fi
    sleep 3
  done
  return 1
}

fail_with_journal() {
  echo "Health did not reach UP within 180 s. Last 80 journal lines:"
  journalctl -u "$SERVICE" -n 80 --no-pager || true
  exit 1
}

prune_releases() {
  local current previous
  current="$(readlink -f "$BASE/current.jar")"
  previous="$(cat "$BASE/previous" 2>/dev/null || true)"
  ls -1t "$BASE"/releases/*.jar | tail -n "+$((KEEP_RELEASES + 1))" | while read -r jar; do
    if [ "$jar" != "$current" ] && [ "$jar" != "$previous" ]; then
      rm -f -- "$jar"
      echo "Removed old release $jar."
    fi
  done
}

case "$mode" in
  deploy)
    sha="$2"
    uploaded="$3"
    release="$BASE/releases/$sha.jar"
    install -m 0644 -o trenching -g trenching "$uploaded" "$release"
    rm -f -- "$uploaded"
    current="$(readlink -e "$BASE/current.jar" 2>/dev/null || true)"
    if [ -n "$current" ] && [ "$current" != "$release" ]; then
      printf '%s\n' "$current" > "$BASE/previous"
    fi
    point_current "$release"
    systemctl restart "$SERVICE"
    wait_healthy || fail_with_journal
    prune_releases
    echo "Deployed $sha."
    ;;
  rollback)
    if [ ! -s "$BASE/previous" ]; then
      echo "No previous release to roll back to." >&2
      exit 1
    fi
    target="$(cat "$BASE/previous")"
    case "$target" in
      "$BASE"/releases/*.jar) ;;
      *) echo "Unexpected previous release path: $target" >&2; exit 1 ;;
    esac
    if [ ! -f "$target" ]; then
      echo "Previous release $target no longer exists." >&2
      exit 1
    fi
    current="$(readlink -e "$BASE/current.jar" 2>/dev/null || true)"
    point_current "$target"
    if [ -n "$current" ]; then
      printf '%s\n' "$current" > "$BASE/previous"
    fi
    systemctl restart "$SERVICE"
    wait_healthy || fail_with_journal
    echo "Rolled back to $target."
    ;;
  *)
    echo "Unknown mode: $mode" >&2
    exit 2
    ;;
esac
'@

function Fail([string]$Message) {
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

# Windows PowerShell 5.1 can turn a native command's stderr into a terminating error under 'Stop'. Run native commands
# with 'Continue' and judge them by exit code only (Assert-NativeSuccess), with stderr merged into the output.
function Invoke-Native([string]$File, [string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $File @Arguments 2>&1 | ForEach-Object { "$_" }
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

function Assert-NativeSuccess([string]$What) {
    if ($LASTEXITCODE -ne 0) {
        Fail "$What failed (exit code $LASTEXITCODE)."
    }
}

function Invoke-RemoteScript([string]$Arguments) {
    $token = [guid]::NewGuid().ToString('N').Substring(0, 12)
    $localScript = Join-Path ([System.IO.Path]::GetTempPath()) "trenching-deploy-$token.sh"
    $remoteScript = "/tmp/trenching-deploy-$token.sh"
    try {
        $unixText = $RemoteScript -replace "`r`n", "`n"
        [System.IO.File]::WriteAllText($localScript, $unixText, (New-Object System.Text.UTF8Encoding($false)))
        Invoke-Native 'scp' @($localScript, "${Server}:$remoteScript") | Out-Host
        Assert-NativeSuccess 'Uploading the remote script'
    }
    finally {
        Remove-Item -LiteralPath $localScript -Force -ErrorAction SilentlyContinue
    }
    Invoke-Native 'ssh' @($Server, "sudo bash $remoteScript $Arguments") | Out-Host
    return $LASTEXITCODE
}

if ($Rollback) {
    Write-Host "Rolling back on $Server ..."
    $code = Invoke-RemoteScript 'rollback'
    if ($code -ne 0) {
        Fail "Rollback failed (exit code $code). Check: ssh $Server 'journalctl -u trenching-bot -n 80 --no-pager'"
    }
    Write-Host 'Rollback finished.' -ForegroundColor Green
    exit 0
}

if (-not $env:JAVA_HOME) {
    Fail 'JAVA_HOME is not set. Point it at a JDK 21, then run again.'
}

Push-Location $RepoRoot
try {
    $dirty = Invoke-Native 'git' @('status', '--porcelain', '--untracked-files=no')
    Assert-NativeSuccess 'git status'
    if ($dirty) {
        Write-Host ($dirty -join [Environment]::NewLine)
        Fail 'Tracked files are modified or staged. Commit or stash them first: the jar bakes in the committed configuration.'
    }

    $untracked = Invoke-Native 'git' @('ls-files', '--others', '--exclude-standard', '--', 'src', 'frontend/src')
    Assert-NativeSuccess 'git ls-files'
    if ($untracked) {
        Write-Host ($untracked -join [Environment]::NewLine)
        Fail 'Untracked files exist under src/ or frontend/src/. Commit or remove them first.'
    }

    $sha = (Invoke-Native 'git' @('rev-parse', '--short', 'HEAD')) -join ''
    Assert-NativeSuccess 'git rev-parse'
    if ($sha -notmatch '^[0-9a-f]{7,40}$') {
        Fail "Unexpected commit id: $sha"
    }

    $buildTask = if ($SkipTests) { 'assemble' } else { 'build' }
    Write-Host "Building $sha with gradlew $buildTask -PwithConsole ..."
    Invoke-Native (Join-Path $RepoRoot 'gradlew.bat') @($buildTask, '-PwithConsole') | Out-Host
    Assert-NativeSuccess 'The Gradle build'
}
finally {
    Pop-Location
}

$jars = @(Get-ChildItem -LiteralPath $LibsDir -Filter '*.jar' | Where-Object { $_.Name -notlike '*-plain.jar' })
if ($jars.Count -ne 1) {
    Fail "Expected exactly one boot jar in $LibsDir, found $($jars.Count). Remove stale jars, then run again."
}
$JarPath = $jars[0].FullName

$remoteJar = "/tmp/trenching-$sha.jar"
Write-Host "Uploading the jar to $Server ..."
Invoke-Native 'scp' @($JarPath, "${Server}:$remoteJar") | Out-Host
Assert-NativeSuccess 'Uploading the jar'

Write-Host "Switching $Server to release $sha ..."
$code = Invoke-RemoteScript "deploy $sha $remoteJar"
if ($code -ne 0) {
    Fail "Deploy of $sha failed (exit code $code). The previous release is still on disk. Run: .\deploy\deploy.ps1 -Server $Server -Rollback"
}
Write-Host "Deployed $sha." -ForegroundColor Green
