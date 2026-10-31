param([Parameter(Mandatory=$true)][string]$Fixture,[Parameter(Mandatory=$true)][string]$RuntimePath)
$ErrorActionPreference = 'Stop'
$runtime = (Resolve-Path -LiteralPath $RuntimePath).Path
New-Item -ItemType Directory -Path $Fixture -Force | Out-Null
$link = Join-Path $Fixture 'jre64'
if (-not (Test-Path -LiteralPath $link)) {
    New-Item -ItemType Junction -Path $link -Target $runtime | Out-Null
} elseif ((Get-Item -LiteralPath $link).Target -ne $runtime) {
    throw 'Unexpected runtime junction target'
}
