<#
.SYNOPSIS
    Releases a new version to Maven Central via GitHub Actions.
.PARAMETER Version
    The version to release, e.g. "0.1.0-beta.1", "0.2.0", "1.0.0"
.EXAMPLE
    .\release.ps1 0.1.0-beta.1
#>
param(
    [Parameter(Mandatory=$true, Position=0)]
    [ValidatePattern('^\d+\.\d+\.\d+')]
    [string]$Version
)

$ErrorActionPreference = 'Stop'
$tag = "v$Version"

if (-not (Test-Path 'pom.xml')) {
    Write-Error "pom.xml not found. Run this script from the repository root."
}

$existingTag = git tag -l $tag
if ($existingTag) {
    Write-Error "Tag $tag already exists. Choose a different version."
}

# --- Update version in pom.xml ---
Write-Host "[1/5] Updating pom.xml ..." -ForegroundColor Cyan
$pomBytes  = [System.IO.File]::ReadAllBytes("$PWD\pom.xml")
$pomText   = [System.Text.Encoding]::UTF8.GetString($pomBytes)
$pomNew    = $pomText -replace '(<version>)[^<]+(</version>([\s\S]*?)<packaging>)', "`${1}$Version`${2}"
if ($pomNew -ne $pomText) {
    [System.IO.File]::WriteAllBytes("$PWD\pom.xml", [System.Text.Encoding]::UTF8.GetBytes($pomNew))
    Write-Host "       pom.xml -> $Version" -ForegroundColor Green
} else {
    Write-Host "       pom.xml already at $Version" -ForegroundColor Yellow
}

# --- Update version in README.md ---
Write-Host "[2/5] Updating README.md ..." -ForegroundColor Cyan
$readmeBytes = [System.IO.File]::ReadAllBytes("$PWD\README.md")
$readmeText  = [System.Text.Encoding]::UTF8.GetString($readmeBytes)
$readmeNew   = $readmeText -replace '(<version>)[^<]+(</version>)', "`${1}$Version`${2}"
$readmeNew   = $readmeNew  -replace "(implementation\s+'com\.aresstack:win-proxy-java:)[^']+'", "`${1}$Version'"
if ($readmeNew -ne $readmeText) {
    [System.IO.File]::WriteAllBytes("$PWD\README.md", [System.Text.Encoding]::UTF8.GetBytes($readmeNew))
    Write-Host "       README.md -> $Version" -ForegroundColor Green
} else {
    Write-Host "       README.md already at $Version" -ForegroundColor Yellow
}

# --- Commit ---
Write-Host "[3/5] Committing ..." -ForegroundColor Cyan
git add pom.xml README.md
$diff = git diff --cached --name-only
if ($diff) {
    git commit -m "release $Version"
} else {
    Write-Host "       No changes — skipping commit." -ForegroundColor Yellow
}

# --- Tag ---
Write-Host "[4/5] Creating tag $tag ..." -ForegroundColor Cyan
git tag $tag

# --- Push ---
Write-Host "[5/5] Pushing to origin ..." -ForegroundColor Cyan
git push origin HEAD --tags

Write-Host ""
Write-Host "Done! Tag $tag pushed." -ForegroundColor Green
Write-Host "GitHub Actions workflow will now build and publish to Maven Central."
Write-Host "Monitor: https://github.com/aresstack/win-proxy-java/actions" -ForegroundColor Yellow

