[CmdletBinding()]
param([string]$GameDir='E:\Steam\steamapps\common\ProjectZomboid',[switch]$SkipTests)
$ErrorActionPreference='Stop'
$output = Join-Path $env:LOCALAPPDATA 'ZombieBuddyOptimizedBuild'
$project = Join-Path $PSScriptRoot 'upstream/java'
if (-not (Test-Path -LiteralPath $project)) { $project = Join-Path (Split-Path -Parent $PSScriptRoot) 'java' }
$wrapper = Join-Path $project 'gradlew.bat'
$gameJar = Join-Path $GameDir 'projectzomboid.jar'
$task = 'test'
if ($SkipTests) { $task = 'shadowJar' }
& $wrapper --no-daemon -p $project $task "-PgameClasspath=$gameJar" "-PoptimizedBuildDir=$output"
if ($LASTEXITCODE -ne 0) { throw 'Upstream build/tests failed' }
$artifact = Join-Path $output 'libs/ZombieBuddy.jar'
if (-not $SkipTests) {
    & python (Join-Path $PSScriptRoot 'tools/test.py') --game-dir $GameDir --jar $artifact
    if ($LASTEXITCODE -ne 0) { throw 'Regression tests failed' }
    & python (Join-Path $PSScriptRoot 'tools/test-localization.py')
    if ($LASTEXITCODE -ne 0) { throw 'Localization tests failed' }
} else {
    $localOutput = Join-Path $PSScriptRoot 'build'
    New-Item -ItemType Directory -Path $localOutput -Force | Out-Null
    Copy-Item -LiteralPath $artifact -Destination (Join-Path $localOutput 'ZombieBuddy.jar') -Force
}
Write-Host 'JAR ready in build/ZombieBuddy.jar. Packaging requires successful tests.'
