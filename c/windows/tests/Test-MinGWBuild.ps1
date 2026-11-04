[CmdletBinding()]
param(
    [string]$Compiler='x86_64-w64-mingw32-gcc',
    [string]$Make='make',
    [string]$Objdump='x86_64-w64-mingw32-objdump'
)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
$source=Join-Path $root 'c/windows'
$work=Join-Path $root 'build/native-mingw-guard'
if((Test-Path -LiteralPath $work) -and -not(Test-Path -LiteralPath (Join-Path $work '.generated-by-mingw-guard'))){throw 'Unknown MinGW staging; preserve before rebuilding'}
$compilerPath=(Get-Command $Compiler -CommandType Application -ErrorAction Stop).Source
$makePath=(Get-Command $Make -CommandType Application -ErrorAction Stop).Source
$objdumpPath=(Get-Command $Objdump -CommandType Application -ErrorAction Stop).Source
[IO.Directory]::CreateDirectory($work)|Out-Null
[IO.File]::WriteAllText((Join-Path $work '.generated-by-mingw-guard'),'Generated isolated build of the production Windows Makefile.')
foreach($name in @('Makefile','zbNative.c','zbNative.def','runtime_guard.h')) {
    Copy-Item -LiteralPath (Join-Path $source $name) -Destination (Join-Path $work $name) -Force
}
$previousPath=$env:PATH
Push-Location $work
try {
    $env:PATH=(Split-Path -Parent $compilerPath)+';'+$previousPath
    $compilerName=Split-Path -Leaf $compilerPath
    & $compilerPath --version | Select-Object -First 1
    & $makePath -B ('CC='+$compilerName)
    if($LASTEXITCODE -ne 0){throw 'Production MinGW Makefile build failed'}
    $plan=(& $makePath -n -W runtime_guard.h ('CC='+$compilerName)) -join "`n"
    if($LASTEXITCODE -ne 0 -or $plan -notmatch ' -c zbNative\.c'){throw 'Guard header changes must trigger recompilation'}
    $pe=(& $objdumpPath -p ./zbNative.dll) -join "`n"
    if($LASTEXITCODE -ne 0 -or $pe -notmatch 'file format pei-x86-64'){throw 'Expected x64 MinGW DLL'}
    $imports=@([regex]::Matches($pe,'DLL Name:\s*(\S+)')|ForEach-Object {$_.Groups[1].Value.ToUpperInvariant()})
    if(($imports|Sort-Object) -join ',' -ne 'KERNEL32.DLL,USER32.DLL'){throw 'Unexpected MinGW runtime imports'}
    $exports=@([regex]::Matches($pe,'(?m)^\s*\[\s*\d+\]\s+(?:\+base\[\s*\d+\]\s+[0-9a-fA-F]+\s+)?(\S+)\s*$')|ForEach-Object {$_.Groups[1].Value}|Sort-Object)
    if($exports -join ',' -ne 'Agent_OnAttach,Agent_OnLoad,Agent_OnUnload'){throw 'Unexpected MinGW agent exports'}
    [IO.File]::WriteAllText((Join-Path $work 'inspect-native.log'),$pe)
    Write-Output 'PASS production MinGW Makefile: header dependency, x64, CRT-free imports, original three agent exports'
    Write-Output ('Candidate DLL: '+(Join-Path $work 'zbNative.dll'))
} finally {
    $env:PATH=$previousPath
    Pop-Location
}
