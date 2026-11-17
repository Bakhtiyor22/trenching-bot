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

# The release logic lives in deploy/trenching-deploy.sh, installed on the server as this command (see deploy/README.md).
$RemoteDeployCommand = 'sudo /usr/local/bin/trenching-deploy'

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

# Runs the installed release script on the server, streams its output and returns its exit code.
function Invoke-Remote([string]$Arguments) {
    $remoteOutput = @()
    Invoke-Native 'ssh' @($Server, "$RemoteDeployCommand $Arguments") | Tee-Object -Variable remoteOutput | Out-Host
    $code = $LASTEXITCODE
    if ($code -eq 127 -or (@($remoteOutput) -join "`n") -match 'command not found') {
        Fail "trenching-deploy is not installed on $Server. Install it as in step 7 of deploy/README.md, then run again."
    }
    return $code
}

if ($Rollback) {
    Write-Host "Rolling back on $Server ..."
    $code = Invoke-Remote 'rollback'
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

    $sha = (Invoke-Native 'git' @('rev-parse', 'HEAD')) -join ''
    Assert-NativeSuccess 'git rev-parse'
    if ($sha -notmatch '^[0-9a-f]{40}$') {
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

$jarSha256 = (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash.ToLowerInvariant()
$remoteJar = "/tmp/trenching-$sha.jar"
Write-Host "Uploading the jar to $Server ..."
Invoke-Native 'scp' @($JarPath, "${Server}:$remoteJar") | Out-Host
Assert-NativeSuccess 'Uploading the jar'

Write-Host "Switching $Server to release $sha ..."
$code = Invoke-Remote "deploy $sha $jarSha256 --no-auto-rollback --file $remoteJar"
if ($code -ne 0) {
    Fail "Deploy of $sha failed (exit code $code). The previous release is still on disk. Run: .\deploy\deploy.ps1 -Server $Server -Rollback"
}
Write-Host "Deployed $sha." -ForegroundColor Green
