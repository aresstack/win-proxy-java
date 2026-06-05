param(
    [switch]$SkipTests,
    [string]$Version = ""
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Invoke-RequiredCommand([string]$Command, [string[]]$Arguments) {
    Write-Host "> $Command $($Arguments -join ' ')" -ForegroundColor DarkGray
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code $LASTEXITCODE: $Command $($Arguments -join ' ')"
    }
}

function Get-ProjectVersion() {
    $pomPath = Join-Path $PSScriptRoot "pom.xml"
    [xml]$pom = Get-Content $pomPath
    return $pom.project.version
}

function Assert-CleanWorkingTree() {
    $status = git status --porcelain
    if ($status) {
        Write-Host $status
        throw "Working tree is not clean. Commit all changes before releasing."
    }
}

function Assert-CommandExists([string]$Command) {
    $resolved = Get-Command $Command -ErrorAction SilentlyContinue
    if (-not $resolved) {
        throw "Required command not found on PATH: $Command"
    }
}

Set-Location $PSScriptRoot

Assert-CommandExists "git"
Assert-CommandExists "mvn"

if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = Get-ProjectVersion
}

if ([string]::IsNullOrWhiteSpace($Version)) {
    throw "Could not determine project version."
}

$tagName = "v$Version"

Write-Step "Preparing release $tagName"
Assert-CleanWorkingTree

$existingTag = git tag --list $tagName
if ($existingTag) {
    throw "Tag already exists: $tagName"
}

Write-Step "Building Maven artifacts"
$mavenArgs = @("clean", "verify")
if ($SkipTests) {
    $mavenArgs += "-DskipTests"
}
Invoke-RequiredCommand "mvn" $mavenArgs

Write-Step "Creating local tag $tagName"
Invoke-RequiredCommand "git" @("tag", "-a", $tagName, "-m", "Release $tagName")

Write-Step "Pushing tag"
Invoke-RequiredCommand "git" @("push", "origin", $tagName)

Write-Step "Release tag pushed"
Write-Host "Release $tagName is ready for Maven Central publishing." -ForegroundColor Green
