[CmdletBinding()]
param(
    [string]$GameDir = 'E:\Steam\steamapps\common\ProjectZomboid',
    [string]$ZombieBuddyModDir,
    [string]$ConfigDir = (Join-Path $env:USERPROFILE '.zombie_buddy')
)
$ErrorActionPreference = 'Stop'
$gameRoot = (Resolve-Path -LiteralPath $GameDir).Path
if (Get-Process -Name ProjectZomboid64 -ErrorAction SilentlyContinue) { throw '请完整退出游戏及 Coop/服务器后替换。' }
$target = Join-Path $gameRoot 'ZombieBuddy.jar'
if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw '需先安装并配置原版 ZombieBuddy。' }
$payload = Join-Path $PSScriptRoot 'ZombieBuddy.jar'
if (-not (Test-Path -LiteralPath $payload)) { $payload = Join-Path $PSScriptRoot 'build/ZombieBuddy.jar' }
if (-not (Test-Path -LiteralPath $payload)) { $payload = Join-Path $PSScriptRoot 'Workshop/Contents/mods/ZombieBuddyOptimized/42.21/ZombieBuddy.jar' }
function Get-Sha256([string]$Path) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($Path)
    try { return [System.BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
    finally { $stream.Dispose(); $sha.Dispose() }
}
$hashFile = Join-Path (Split-Path -Parent $payload) 'ZombieBuddy.jar.sha256'
if ((Get-Sha256 $payload) -ne (Get-Content -LiteralPath $hashFile -Raw).Trim()) { throw '补丁 JAR 校验失败。' }
$authors = Join-Path $PSScriptRoot 'authors.json'
if ((Get-Sha256 $authors) -ne (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'authors.json.sha256') -Raw).Trim()) {
    throw '作者签名列表文件校验失败。'
}
if (-not $ZombieBuddyModDir) {
    $steamApps = Split-Path -Parent (Split-Path -Parent $gameRoot)
    $candidate = Join-Path $steamApps 'workshop/content/108600/3619862853/mods/ZombieBuddy'
    if (Test-Path -LiteralPath (Join-Path $candidate 'authors.json')) { $ZombieBuddyModDir = $candidate }
    else { throw '无法定位原 ZombieBuddy 工坊目录，请用 -ZombieBuddyModDir 指定包含 authors.json 和 libs 的目录。' }
}
$modRoot = (Resolve-Path -LiteralPath $ZombieBuddyModDir).Path
if (-not (Test-Path -LiteralPath (Join-Path $modRoot 'libs/ZombieBuddy.jar'))) {
    throw '-ZombieBuddyModDir 必须指向原 ZombieBuddy 模组根目录。'
}
$cacheRoot = [System.IO.Path]::GetFullPath($ConfigDir)
$backup = Join-Path $gameRoot ('ZombieBuddy-Optimized-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backup | Out-Null
$modAuthors = Join-Path $modRoot 'authors.json'
$cacheAuthors = Join-Path $cacheRoot 'authors.json'
if (Test-Path -LiteralPath $modAuthors) { Copy-Item -LiteralPath $modAuthors -Destination (Join-Path $backup 'authors-workshop.json') }
if (Test-Path -LiteralPath $cacheAuthors) { Copy-Item -LiteralPath $cacheAuthors -Destination (Join-Path $backup 'authors-cache.json') }
[System.IO.File]::WriteAllText((Join-Path $backup 'authors-paths.json'),
    (@{ workshop=$modAuthors; cache=$cacheAuthors } | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
foreach ($name in @('ZombieBuddy.jar', 'ZombieBuddy.jar.zbs', 'ZombieBuddy.jar.new', 'ProjectZomboid64.json')) {
    $file = Join-Path $gameRoot $name
    if (Test-Path -LiteralPath $file -PathType Leaf) { Copy-Item -LiteralPath $file -Destination $backup }
}
$jsonPath = Join-Path $gameRoot 'ProjectZomboid64.json'
if (Test-Path -LiteralPath $jsonPath) {
    $config = Get-Content -LiteralPath $jsonPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $oldAgent = '^-javaagent:(?:"[^"]*ZombieBuddySelectiveHooks\.jar"|[^=]*ZombieBuddySelectiveHooks\.jar)(?:=.*)?$'
    $oldArgs = @($config.vmArgs)
    $newArgs = @($oldArgs | Where-Object { $_ -notmatch $oldAgent })
    if ($oldArgs.Count -ne $newArgs.Count) {
        $config.vmArgs = $newArgs
        $text = $config | ConvertTo-Json -Depth 100
        [System.IO.File]::WriteAllText($jsonPath, $text + "`r`n", [System.Text.UTF8Encoding]::new($false))
    }
}
# Obsolete signatures cannot authenticate the replacement; staged JARs would overwrite it next launch.
foreach ($name in @('ZombieBuddy.jar.zbs', 'ZombieBuddy.jar.new')) {
    $file = Join-Path $gameRoot $name
    if (Test-Path -LiteralPath $file -PathType Leaf) { Remove-Item -LiteralPath $file -Force }
}
Copy-Item -LiteralPath $payload -Destination $target -Force
New-Item -ItemType Directory -Path $cacheRoot -Force | Out-Null
Copy-Item -LiteralPath $authors -Destination $modAuthors -Force
Copy-Item -LiteralPath $authors -Destination $cacheAuthors -Force
if ((Get-Sha256 $target) -ne (Get-Sha256 $payload)) { throw "替换校验失败，原文件在 $backup" }
Write-Host "替换完成。原文件备份：$backup"
Write-Host "作者列表已原样更新到：$modAuthors 和 $cacheAuthors"
Write-Host '保留原 ZombieBuddy 模组和启动方式；首次获准的 Java 模组会要求重启。'
Write-Host '若曾在 Steam/BAT 手工添加 ZombieBuddySelectiveHooks.jar，请移除该旧 Agent 参数。'
