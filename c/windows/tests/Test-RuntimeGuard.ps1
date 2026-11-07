[CmdletBinding()]
param(
    [string]$GameRoot='E:\Steam\steamapps\common\ProjectZomboid',
    [string]$JdkRoot='C:\Program Files\Java\jdk-25',
    [string]$AgentJar,
    [string]$OriginalDll,
    [string]$CandidateDll
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
$source=Join-Path $root 'c/windows'
$work=Join-Path $root 'build/native-runtime-guard'
if((Test-Path -LiteralPath $work) -and -not(Test-Path -LiteralPath (Join-Path $work '.generated-by-native-guard'))){throw 'Unknown native staging; preserve before rebuilding'}
[IO.Directory]::CreateDirectory($work)|Out-Null
[IO.File]::WriteAllText((Join-Path $work '.generated-by-native-guard'),'Generated native guard candidate and read-only runtime junctions; never recursively traverse junctions for cleanup.')
$vswhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$vs=& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if($LASTEXITCODE -ne 0 -or -not $vs){throw 'MSVC x64 tools not found'}
$msvc=Get-ChildItem -LiteralPath (Join-Path $vs 'VC/Tools/MSVC') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$sdkRoot=Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10'
$sdk=Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'Lib') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$include=Join-Path $sdkRoot ('Include/'+$sdk.Name)
$lib=Join-Path $sdkRoot ('Lib/'+$sdk.Name+'/um/x64')
$cl=Join-Path $msvc.FullName 'bin/Hostx64/x64/cl.exe'
$link=Join-Path $msvc.FullName 'bin/Hostx64/x64/link.exe'
$utf8=[Text.UTF8Encoding]::new($false)
function Run([string]$exe,[string[]]$arguments,[string]$label) {
    $previous=$ErrorActionPreference; $ErrorActionPreference='Continue'
    $output=& $exe @arguments 2>&1
    $code=$LASTEXITCODE; $ErrorActionPreference=$previous
    $lines=@($output|ForEach-Object {"$_"})
    [IO.File]::WriteAllLines((Join-Path $work ($label+'.log')),$lines,$utf8)
    if($code -ne 0){$lines|Write-Output;throw "$label failed: $code"}
    $lines|Where-Object {$_ -match '^(PASS|REPRO|MODULE=)'}|Write-Output
}
$flags=@('/nologo','/c','/O1','/GS-','/Zl','/W4','/WX',('/I'+(Join-Path $msvc.FullName 'include')),('/I'+(Join-Path $include 'um')),('/I'+(Join-Path $include 'shared')),('/I'+(Join-Path $include 'ucrt')))
if($CandidateDll) {
    $candidate=(Resolve-Path -LiteralPath $CandidateDll).Path
} else {
    Run $cl ($flags+@(('/Fo'+(Join-Path $work 'zbNative.obj')),(Join-Path $source 'zbNative.c'))) 'compile-native'
    $candidate=Join-Path $work 'zbNative.dll'
    Run $link @('/nologo','/DLL','/NODEFAULTLIB','/ENTRY:DllMain','/MACHINE:X64','/DYNAMICBASE','/NXCOMPAT','/Brepro',('/DEF:'+(Join-Path $source 'zbNative.def')),('/OUT:'+$candidate),('/LIBPATH:'+$lib),(Join-Path $work 'zbNative.obj'),'kernel32.lib','user32.lib') 'link-native'
}
Run (Join-Path $msvc.FullName 'bin/Hostx64/x64/dumpbin.exe') @('/headers','/imports','/exports',$candidate) 'inspect-native'
$pe=[IO.File]::ReadAllText((Join-Path $work 'inspect-native.log'))
if($pe -notmatch '8664 machine \(x64\)'){throw 'Native candidate must be Windows x64'}
$imports=@([regex]::Matches($pe,'(?m)^\s+(\S+\.dll)\s*$')|ForEach-Object {$_.Groups[1].Value.ToUpperInvariant()})
if(($imports|Sort-Object) -join ',' -ne 'KERNEL32.DLL,USER32.DLL'){throw 'Unexpected native runtime dependency'}
foreach($entry in @('Agent_OnAttach','Agent_OnLoad','Agent_OnUnload')) {
    if($pe -notmatch ('(?m)^\s+\d+\s+\w+\s+\w+\s+'+[regex]::Escape($entry)+'\s*$')){throw "Missing native agent export $entry"}
}
if([IO.File]::ReadAllText((Join-Path $source 'zbNative.c')) -notmatch '(?m)^#define AGENT_OPTIONS_MAX 4096\r?$'){throw 'Native boundary fixtures require a 4096-byte loader'}
Run $cl ($flags+@(('/Fo'+(Join-Path $work 'NativeOptionsProbe.obj')),(Join-Path $source 'tests/NativeOptionsProbe.c'))) 'compile-native-options'
$optionsExe=Join-Path $work 'NativeOptionsProbe.exe'
Run $link @('/nologo','/NODEFAULTLIB','/ENTRY:mainCRTStartup','/SUBSYSTEM:CONSOLE','/MACHINE:X64',('/OUT:'+$optionsExe),('/LIBPATH:'+$lib),(Join-Path $work 'NativeOptionsProbe.obj'),'kernel32.lib','user32.lib') 'link-native-options'
Run $optionsExe @() 'native-options'
Run $cl ($flags+@(('/Fo'+(Join-Path $work 'NativeIdentityProbe.obj')),(Join-Path $source 'tests/NativeIdentityProbe.c'))) 'compile-native-identity'
$identityExe=Join-Path $work 'NativeIdentityProbe.exe'
Run $link @('/nologo','/NODEFAULTLIB','/ENTRY:mainCRTStartup','/SUBSYSTEM:CONSOLE','/MACHINE:X64',('/OUT:'+$identityExe),('/LIBPATH:'+$lib),(Join-Path $work 'NativeIdentityProbe.obj'),'kernel32.lib','user32.lib') 'link-native-identity'
Run $identityExe @() 'native-identity'
Run $cl ($flags+@(('/Fo'+(Join-Path $work 'NativeDiagnosticsProbe.obj')),(Join-Path $source 'tests/NativeDiagnosticsProbe.c'))) 'compile-native-diagnostics'
$diagnosticsExe=Join-Path $work 'NativeDiagnosticsProbe.exe'
Run $link @('/nologo','/NODEFAULTLIB','/ENTRY:mainCRTStartup','/SUBSYSTEM:CONSOLE','/MACHINE:X64',('/OUT:'+$diagnosticsExe),('/LIBPATH:'+$lib),(Join-Path $work 'NativeDiagnosticsProbe.obj'),'kernel32.lib','user32.lib') 'link-native-diagnostics'
Run $diagnosticsExe @() 'native-diagnostics'
Run $cl ($flags+@(('/Fo'+(Join-Path $work 'NativeGuardOrderProbe.obj')),(Join-Path $source 'tests/NativeGuardOrderProbe.c'))) 'compile-native-order'
$orderExe=Join-Path $work 'NativeGuardOrderProbe.exe'
Run $link @('/nologo','/NODEFAULTLIB','/ENTRY:mainCRTStartup','/SUBSYSTEM:CONSOLE','/MACHINE:X64',('/OUT:'+$orderExe),('/LIBPATH:'+$lib),(Join-Path $work 'NativeGuardOrderProbe.obj'),'kernel32.lib','user32.lib','shell32.lib') 'link-native-order'
Run $cl ($flags+@(('/Fo'+(Join-Path $work 'NativeCallbacksProbe.obj')),(Join-Path $source 'tests/NativeCallbacksProbe.c'))) 'compile-native-callbacks'
$callbacksExe=Join-Path $work 'NativeCallbacksProbe.exe'
Run $link @('/nologo','/NODEFAULTLIB','/ENTRY:mainCRTStartup','/SUBSYSTEM:CONSOLE','/MACHINE:X64',('/OUT:'+$callbacksExe),('/LIBPATH:'+$lib),(Join-Path $work 'NativeCallbacksProbe.obj'),'kernel32.lib','user32.lib','shell32.lib') 'link-native-callbacks'
[IO.Directory]::CreateDirectory((Join-Path $work 'classes'))|Out-Null
$classes=Join-Path $work 'classes'
if(-not $AgentJar){throw 'Provide -AgentJar from an unmodified ZombieBuddy release'}; $jar=(Resolve-Path -LiteralPath $AgentJar).Path
$cp=$classes+';'+$jar+';'+(Join-Path $GameRoot 'projectzomboid.jar')
Run (Join-Path $JdkRoot 'bin/javac.exe') @('--release','17','-encoding','UTF-8','-cp',$cp,'-d',$classes,(Join-Path $root 'c/windows/tests/NativeGuardProbe.java')) 'compile-probe'
Run $cl ($flags+@(('/I'+(Join-Path $JdkRoot 'include')),('/I'+(Join-Path $JdkRoot 'include/win32')),('/Fo'+(Join-Path $work 'NativeGuardHost.obj')),(Join-Path $root 'c/windows/tests/NativeGuardHost.c'))) 'compile-host'
$hostExe=Join-Path $work 'NativeGuardHost.exe'
Run $link @('/nologo','/NODEFAULTLIB','/ENTRY:mainCRTStartup','/SUBSYSTEM:CONSOLE','/MACHINE:X64','/DYNAMICBASE','/NXCOMPAT',('/OUT:'+$hostExe),('/LIBPATH:'+$lib),(Join-Path $work 'NativeGuardHost.obj'),'kernel32.lib','shell32.lib') 'link-host'
Run $cl ($flags+@('/DZB_TEST_NATIVE_CODEPAGE=932',('/I'+(Join-Path $JdkRoot 'include')),('/I'+(Join-Path $JdkRoot 'include/win32')),('/Fo'+(Join-Path $work 'NativeGuardHost932.obj')),(Join-Path $root 'c/windows/tests/NativeGuardHost.c'))) 'compile-host932'
$host932=Join-Path $work 'NativeGuardHost932.exe'
Run $link @('/nologo','/NODEFAULTLIB','/ENTRY:mainCRTStartup','/SUBSYSTEM:CONSOLE','/MACHINE:X64','/DYNAMICBASE','/NXCOMPAT',('/OUT:'+$host932),('/LIBPATH:'+$lib),(Join-Path $work 'NativeGuardHost932.obj'),'kernel32.lib','shell32.lib') 'link-host932'
if(-not $OriginalDll){$OriginalDll=Join-Path $GameRoot 'zbNative.dll'}; $original=(Resolve-Path -LiteralPath $OriginalDll).Path
if((Get-FileHash -LiteralPath $original).Hash -ne 'C2AE9335E717EE24B2F4A40D1A3BF77F1519762A72A0459E766A2BBAFC077F6C'){throw 'Expected the known original native baseline'}
$runtime=Join-Path $GameRoot 'jre64/bin'
foreach($case in @('original-clean','original-leftovers','guard-clean','guard-leftovers','guard-preloaded','guard-invalid-leftovers','guard-duplicate-jvm','guard-alias',('guard Unicode '+[char]0x00C4+' spaces'))) {
    $stage=Join-Path $work ('cases/'+$case)
    [IO.Directory]::CreateDirectory($stage)|Out-Null
    # Reference the entire real runtime read-only; never write through this junction.
    $runtimeLink=Join-Path $stage 'jre64'
    if(-not(Test-Path -LiteralPath $runtimeLink)){New-Item -ItemType Junction -Path $runtimeLink -Target (Join-Path $GameRoot 'jre64')|Out-Null}
    $linkItem=Get-Item -LiteralPath $runtimeLink
    if($linkItem.LinkType -ne 'Junction' -or [IO.Path]::GetFullPath($linkItem.Target[0]) -ne [IO.Path]::GetFullPath((Join-Path $GameRoot 'jre64'))){throw 'Unexpected runtime reference; preserve before rebuilding'}
    Copy-Item -LiteralPath $hostExe -Destination (Join-Path $stage 'NativeGuardHost.exe') -Force
    Copy-Item -LiteralPath $jar -Destination (Join-Path $stage 'ZombieBuddy.jar') -Force
    $dll=if($case.StartsWith('original')){$original}else{$candidate}
    Copy-Item -LiteralPath $dll -Destination (Join-Path $stage 'zbNative.dll') -Force
    if($case -match 'leftovers|preloaded|Unicode') {
        foreach($name in @('java.dll','instrument.dll')){Copy-Item -LiteralPath (Join-Path $runtime $name) -Destination (Join-Path $stage $name) -Force}
    }
    if($case -eq 'guard-invalid-leftovers') {
        foreach($name in @('java.dll','instrument.dll')){[IO.File]::WriteAllText((Join-Path $stage $name),'Deliberately invalid test-only DLL',$utf8)}
    }
    if($case -eq 'guard-duplicate-jvm') {
        [IO.Directory]::CreateDirectory((Join-Path $stage 'foreign/server'))|Out-Null
        Copy-Item -LiteralPath (Join-Path $runtime 'server/jvm.dll') -Destination (Join-Path $stage 'foreign/server/jvm.dll') -Force
    }
    Push-Location $stage
    try {
        $mode=if($case -eq 'original-leftovers'){'reproduce'}else{'verify'}

        $caseRuntime=Join-Path $runtimeLink 'bin'
        if($case -match 'Unicode'){$mode='unicode'}
        # JNI agent options use the Windows code page. Keep the agent path out of
        # the Unicode stage; the host and all three runtime DLLs still use it.
        $caseAgent=if($mode -eq 'unicode'){$candidate}else{Join-Path $stage 'zbNative.dll'}
        $arguments=@($caseRuntime,$caseAgent,$cp,$mode)
        if($case -eq 'guard-preloaded'){$arguments+='preload'}
        if($case -eq 'guard-duplicate-jvm'){$arguments+=@('duplicate-jvm',(Join-Path $stage 'foreign/server/jvm.dll'))}
        if($case -eq 'guard-alias'){$arguments+=@('alias',$runtime)}
        if($case -eq 'guard-duplicate-jvm') {
            $previous=$ErrorActionPreference; $ErrorActionPreference='Continue'
            $output=& (Join-Path $stage 'NativeGuardHost.exe') @arguments 2>&1
            $code=$LASTEXITCODE; $ErrorActionPreference=$previous
            $text=@($output|ForEach-Object {"$_"}) -join "`n"
            [IO.File]::WriteAllText((Join-Path $work ($case+'.log')),$text,$utf8)
            if($code -ne 1 -or $text -notmatch 'PASS duplicate JVM fixture:' -or $text -notmatch '\[ZB-NATIVE\] JVM DLL guard: Multiple JVM DLL instances are already loaded\.'){throw 'Two loaded JVMs were not rejected before dependency loading'}
            Write-Output 'PASS duplicate JVM refusal: two mapped copies rejected; no Java/instrument modules loaded'
            continue
        }
        if($case -eq 'guard-preloaded') {
            $previous=$ErrorActionPreference; $ErrorActionPreference='Continue'
            $output=& (Join-Path $stage 'NativeGuardHost.exe') @arguments 2>&1
            $code=$LASTEXITCODE; $ErrorActionPreference=$previous
            $text=@($output|ForEach-Object {"$_"}) -join "`n"
            [IO.File]::WriteAllText((Join-Path $work ($case+'.log')),$text,$utf8)
            if($code -eq 0 -or $text -notmatch '\[ZB-NATIVE\] JVM DLL guard: Unexpected already-loaded JVM DLL:'){throw 'Preloaded foreign DLL was not explicitly rejected'}
            Write-Output 'PASS preloaded foreign java.dll: explicit refusal before agent main'
        } else {
            Run (Join-Path $stage 'NativeGuardHost.exe') $arguments $case
            $text=[IO.File]::ReadAllText((Join-Path $work ($case+'.log')))
            if($mode -eq 'reproduce') {
                if($text -notmatch '^REPRO original loader: real native encoding failure' -and $text -notmatch '(?m)^REPRO original loader: real native encoding failure'){throw 'Original failure was not reproduced'}
            } elseif($text -notmatch '(?m)^PASS native guard:') {throw 'Native verification marker missing'}
            if($case -eq 'guard-alias' -and $text -notmatch 'PASS alias fixture:'){throw 'Alias reuse fixture was not proved'}
            if($mode -eq 'unicode') {
                Copy-Item -LiteralPath $host932 -Destination (Join-Path $stage 'NativeGuardHost932.exe') -Force
                Run (Join-Path $stage 'NativeGuardHost932.exe') $arguments 'unicode-ms932'
                $encodedText=[IO.File]::ReadAllText((Join-Path $work 'unicode-ms932.log'))
                if($encodedText -notmatch 'PASS Unicode runtime paths:' -or $encodedText -notmatch '(?m)^PASS native guard:'){throw 'CP932 option conversion did not retain Unicode runtime coverage'}
                Write-Output 'PASS CP932 agent option conversion with staged Unicode host/runtime paths'
            }
        }
    } finally {Pop-Location}
}
# Observe the real guard's own load calls in five fresh processes. Setup loads
# are separate; failures must stop before Java/instrumentation or before instrument.
$orderRuntime=Join-Path $work 'cases/guard-clean/jre64/bin'
$foreignJava=Join-Path $work 'cases/guard-preloaded/java.dll'
Run $callbacksExe @($orderRuntime) 'native-callbacks'
foreach($mode in @('control','foreign','unsupported-first','unsupported-second','after-java')) {
    $label='order-'+$mode
    Run $orderExe @($orderRuntime,$foreignJava,$mode) $label
    $orderText=[IO.File]::ReadAllText((Join-Path $work ($label+'.log')))
    if($orderText -notmatch '(?m)^PASS guard order'){throw 'Guard order assertion missing'}
    if($mode -ne 'control' -and $orderText -notmatch '\[code=ZB_GUARD_MODULE_IDENTITY\]'){throw 'Stable identity refusal code missing'}
}
# Exercise both sides of the native byte boundary through the real bundled JVM.
$boundaryStage=Join-Path $work 'cases/guard-clean'
Push-Location $boundaryStage
try {
    $boundaryRuntime=Join-Path $boundaryStage 'jre64/bin'
    Run (Join-Path $boundaryStage 'NativeGuardHost.exe') @($boundaryRuntime,(Join-Path $boundaryStage 'zbNative.dll'),$cp,'options-exact') 'options-exact'
    $previous=$ErrorActionPreference; $ErrorActionPreference='Continue'
    $output=& (Join-Path $boundaryStage 'NativeGuardHost.exe') $boundaryRuntime (Join-Path $boundaryStage 'zbNative.dll') $cp 'options-overflow' 2>&1
    $code=$LASTEXITCODE; $ErrorActionPreference=$previous
    $text=@($output|ForEach-Object {"$_"}) -join "`n"
    [IO.File]::WriteAllText((Join-Path $work 'options-overflow.log'),$text,$utf8)
    if($code -eq 0 -or $text -match 'PASS native guard:'){throw '4097-byte native payload was not refused'}
    Write-Output 'PASS actual native option boundary: 4096 bytes intact; 4097 refused before agent main'
} finally {Pop-Location}
# Missing canonical instrument.dll must not fall back to a root copy.
$missing=Join-Path $work 'missing-runtime-dependency'
[IO.Directory]::CreateDirectory((Join-Path $missing 'runtime/server'))|Out-Null
Copy-Item -LiteralPath (Join-Path $runtime 'server/jvm.dll') -Destination (Join-Path $missing 'runtime/server/jvm.dll') -Force
Copy-Item -LiteralPath (Join-Path $runtime 'java.dll') -Destination (Join-Path $missing 'runtime/java.dll') -Force
Copy-Item -LiteralPath (Join-Path $runtime 'instrument.dll') -Destination (Join-Path $missing 'instrument.dll') -Force
Copy-Item -LiteralPath $candidate -Destination (Join-Path $missing 'zbNative.dll') -Force
Copy-Item -LiteralPath $hostExe -Destination (Join-Path $missing 'NativeGuardHost.exe') -Force
$previous=$ErrorActionPreference; $ErrorActionPreference='Continue'
$output=& (Join-Path $missing 'NativeGuardHost.exe') (Join-Path $missing 'runtime') (Join-Path $missing 'zbNative.dll') $cp 'verify' 'guard-only' 2>&1
$code=$LASTEXITCODE; $ErrorActionPreference=$previous
$text=@($output|ForEach-Object {"$_"}) -join "`n"
[IO.File]::WriteAllText((Join-Path $work 'missing-runtime-dependency.log'),$text,$utf8)
if($code -ne 1 -or $text -notmatch '\[ZB-NATIVE\] JVM DLL guard: Cannot load active runtime instrument.dll:'){throw 'Missing canonical dependency did not explicitly fail without fallback'}
Write-Output 'PASS missing runtime instrument.dll: no fallback to root copy'
Write-Output 'PASS isolated native runtime guard regression tests; no game/server main.'
