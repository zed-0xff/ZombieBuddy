[CmdletBinding()]
param(
    [string]$GameDir='E:\Steam\steamapps\common\ProjectZomboid',
    [string]$OutputDir=(Join-Path $env:LOCALAPPDATA 'ZombieBuddyOptimizedBuild'),
    [switch]$SkipTests
)
$ErrorActionPreference='Stop'
$output = [IO.Path]::GetFullPath($OutputDir)
$GameDir = (Resolve-Path -LiteralPath $GameDir).Path
$project = Join-Path $PSScriptRoot 'upstream/java'
if (-not (Test-Path -LiteralPath $project)) { $project = Join-Path (Split-Path -Parent $PSScriptRoot) 'java' }
$wrapper = Join-Path $project 'gradlew.bat'
$gameJar = Join-Path $GameDir 'projectzomboid.jar'
if (-not (Test-Path -LiteralPath $gameJar -PathType Leaf)) { throw 'GameDir must contain projectzomboid.jar' }
$localOutput = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Path $localOutput -Force | Out-Null
foreach ($name in @('test-report.json','localization-report.json','build-context.json','verification.json','regression-inputs.json','localization-inputs.json','distribution-report.json')) {
    $old = Join-Path $localOutput $name
    if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old }
}
$task = 'test'
if ($SkipTests) { $task = 'shadowJar' }
& $wrapper --no-daemon -p $project $task "-PgameClasspath=$gameJar" "-PoptimizedBuildDir=$output"
if ($LASTEXITCODE -ne 0) { throw 'Upstream build/tests failed' }
$artifact = Join-Path $output 'libs/ZombieBuddy.jar'
Copy-Item -LiteralPath $artifact -Destination (Join-Path $localOutput 'ZombieBuddy.jar') -Force
$digest = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $localOutput 'ZombieBuddy.jar.sha256'), $digest + "`n", [Text.Encoding]::ASCII)
if (-not $SkipTests) {
    & python (Join-Path $PSScriptRoot 'tools/test.py') --game-dir $GameDir --jar $artifact
    if ($LASTEXITCODE -ne 0) { throw 'Regression tests failed' }
    & python (Join-Path $PSScriptRoot 'tools/test-localization.py') --game-dir $GameDir
    if ($LASTEXITCODE -ne 0) { throw 'Localization tests failed' }
    & python (Join-Path $PSScriptRoot 'tools/replacement-test.py')
    if ($LASTEXITCODE -ne 0) { throw 'Replacement tests failed' }
}
$context = @{ jar_sha256=$digest; game_dir=$GameDir; gradle_output=$output; tests_run=(-not $SkipTests) }
[IO.File]::WriteAllText((Join-Path $localOutput 'build-context.json'), ($context | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Write-Host 'JAR ready in build/ZombieBuddy.jar. Packaging requires successful tests.'
