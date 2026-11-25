# Windows runtime-library regression tests

## Protection contract

The anchor is the already running JVM, resolved from its module handle, not a hardcoded game directory or the test staging path. Before loading Java/instrumentation dependencies, module enumeration must prove there is exactly one `jvm.dll`. A second mapped JVM is rejected even if it is byte-identical. Java/instrumentation modules must identify the physical files in that JVM's runtime directory; independent copies are conflicts. Junction/hardlink aliases of the same file are allowed when Windows reuses one mapped module. Multiple mapped instances remain conflicts, regardless of aliasing. This is an early dependency-selection guard, not authentication of the JVM installation or protection against concurrent hostile DLL injection.

File comparisons use `GetFileInformationByHandleEx(FileIdInfo)` with the full 128-bit file ID and 64-bit volume serial number, following [Microsoft's identity contract](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_id_info). The previous 64-bit file index is insufficient to establish uniqueness on ReFS. If opening a file or querying its identity fails, comparison fails closed; no path-string or 64-bit fallback is used. This adds an API requirement for FileIdInfo support on the runtime filesystem. Windows / Project Zomboid 42.21 is the tested target; a real ReFS installation was not tested.

Two explicit host regressions exercise the distinction: `guard-duplicate-jvm` proves two separate mapped JVM handles and zero Java/instrumentation modules, invokes the guard without creating a VM, and requires refusal with those dependency modules still absent. `guard-alias` preloads the real runtime `java.dll` via its original path, proves a different junction path reuses that module, and then starts the actual JVM/agent successfully. Together with the existing eight cases, there are ten native-host cases.

`NativeIdentityProbe.c` exercises the real comparator with controlled FileIdInfo responses and real open/closed handles. It checks differences in each of the 16 ID bytes, the upper volume bits, failed first/second queries, failed first/second opens and handle cleanup. These controlled responses test comparison width and failure handling, not actual ReFS behavior.

Identity failures retain the operation/path and original Windows error before cleanup. A physical mismatch remains distinct from an open/query failure; only the confirmed mismatch recommends removal of confirmed leftover copies. All retain the stable `ZB_GUARD_MODULE_IDENTITY` category, with `[operation=...] [win32=...]` added for API failures. Other failed Windows operations also include their captured error code.

`NativeDiagnosticsProbe.c` exercises actual guard formatting/writing with controlled stderr handles/writes and debug receivers. It covers normal/partial writes, missing/invalid handles, failed/zero-byte writes, Unicode debug fallback, preservation of the caller's last error and distinct mismatch/open/query advice. The identity probe deliberately changes the thread error during handle cleanup to prove the original failure survives. These capture seams are test-only and do not establish actual GUI/service log collection.

## Running the tests

The player-facing [Windows loader requirement](../../../doc/Installation.md#windows-native-loader-requirement) explains the mandatory FileIdInfo support and startup/attach refusal when identity queries fail. The tests simulate this failure; they do not establish compatibility with a particular unsupported filesystem.

### Test build mechanics

`NativeOptionsProbe.c`, `NativeIdentityProbe.c`, `NativeDiagnosticsProbe.c`, `NativeGuardOrderProbe.c` and `NativeCallbacksProbe.c` each build a separate executable from one translation unit that directly includes `zbNative.c`. Do not also link the production `zbNative.obj` into these executables or compile the static guard header as another translation unit. Their compile-time API wrappers apply only to the individual test executable and are absent from the production DLL.

`NativeGuardHost.c` is a separate host executable that loads the actual candidate DLL and starts the bundled JVM for `NativeGuardProbe.java`; those real-host cases do not use the C probe wrappers.

### Runtime DLL lifetime

The loader deliberately retains its explicit `java.dll` reference for the process lifetime to keep JVM-owned native state available independently of the agent lifetime. `Agent_OnUnload` releases the agent's instrumentation handle; it does not release `runtimeJava`. Process teardown releases the remaining reference. This documents the existing ownership policy and adds no unload operation.

### Cases and execution

`NativeCallbacksProbe.c` checks callback state without creating a JVM. It loads actual runtime modules and exercises the real exported entry points/initialization, while controlling callback exports, an unavailable-JVM failure and observation of library release. Pending-JAR inspection is forced to "no update" so the probe cannot apply an update. Cases cover empty/repeated unload, stale callback state with a missing handle, failed load/attach initialization, three successful load/attach/unload cycles, failed retry after unload, missing exports and unload without an unload callback. It checks forwarded arguments/return values, callback-before-free ordering and retention of the Java module reference. Exit `51` means callback/handle state was not cleared; `52`-`57` identify callback/API-seam assertions; `58` is invalid input; `59`-`76` identify scenario assertions. This probe failed on the pre-cleanup source at `51` and passes after the cleanup; it is not evidence of a regular JVM shutdown crash or production native-attach acceptance.

`NativeGuardOrderProbe.c` adds five fresh-process checks of the real guard with real JVM module enumeration and file handles. A successful control observes actual Java-then-instrumentation loading. A preloaded independent Java copy and `ERROR_NOT_SUPPORTED` on the first/second identity query must each stop before any guard-owned dependency load. A failure after the first Java load must stop before instrumentation loading. Compile-time wrappers count guard-owned `LoadLibraryExW` calls, validate their paths/search flags and delegate permitted loads to Windows; setup loads are separate. Only identity-query failure is injected. These are controlled ordering/error regressions, not a real unsupported-filesystem test.

The existing identity comparator test also explicitly reports `ERROR_NOT_SUPPORTED` for failed queries. No unsupported-identity fallback is added.

These isolated tests start the real bundled JVM/agent, never a game or server main. They contrast the original loader with the guarded native candidate in executable directories containing no leftovers, copied Java/instrumentation DLLs, invalid DLLs and Unicode/spaces paths. They also reject a preloaded foreign Java DLL and a missing canonical runtime dependency without fallback. The actual native encoding exception is reproduced naturally; it is not injected.

Requirements: Windows x64, MSVC x64 tools and Windows SDK, JDK 25, the installed Project Zomboid 42.21 runtime/game JAR, an unmodified official ZombieBuddy 2.3.4 release JAR and original native DLL (SHA-256 `C2AE9335E717EE24B2F4A40D1A3BF77F1519762A72A0459E766A2BBAFC077F6C`). The tests use only upstream agent APIs and bundled game JNA to verify loaded module identities.

```powershell
./c/windows/tests/Test-RuntimeGuard.ps1 -GameRoot 'E:\Steam\steamapps\common\ProjectZomboid' -AgentJar '<official ZombieBuddy.jar>' -OriginalDll '<original zbNative.dll>'
```

The test compiles the native candidate with MSVC (without adding a CRT dependency), or accepts `-CandidateDll` to run the real-host cases against an explicitly built DLL such as the MinGW output. The standalone C probes still compile with MSVC. Outputs are under ignored `build/native-runtime-guard/`. Initial staging creation is safe only for a new/generated directory; unknown existing staging is rejected. Runtime directory junctions are read-only. Do not recursively traverse those junctions during cleanup; remove the junction entries themselves.

### MinGW Makefile check

`Test-MinGWBuild.ps1` copies the production Makefile/source/header/DEF byte-for-byte into marked ignored `build/native-mingw-guard/` staging. It uses the Makefile's normal flags, verifies that the header triggers recompilation, and checks x64 PE format, KERNEL32/USER32-only imports and exactly the three original agent exports. It requires MinGW-w64 GCC, GNU Make and GNU Objdump; no system installation or automatic toolchain download is performed by the script. Supply paths explicitly for a portable toolchain:

```powershell
./c/windows/tests/Test-MinGWBuild.ps1 -Compiler '<toolchain>/bin/x86_64-w64-mingw32-gcc.exe' -Make '<toolchain>/bin/mingw32-make.exe' -Objdump '<toolchain>/bin/objdump.exe'
./c/windows/tests/Test-RuntimeGuard.ps1 -AgentJar '<official ZombieBuddy.jar>' -OriginalDll '<original zbNative.dll>' -CandidateDll './build/native-mingw-guard/zbNative.dll'
```

The production Makefile and real-host suite were exercised with MinGW-Builds GCC 16.2.0 / mingw-w64 v14 (x64 POSIX/SEH/UCRT) in addition to MSVC. This is a local compiler/runtime check, not a CI matrix or actual ReFS/game/native-attach acceptance. Compiler/header requirements and the distinction between the native API floor and supported current game OS are documented in the installation guide.

The guarded process must load exactly one `java.dll` and one `instrument.dll` from its active runtime. Localhost resolution, socket/HTTP-client creation and native agent option forwarding must succeed. Already loaded conflicting JVM modules cause an explicit guard message and startup refusal; they are never forcibly unloaded. No file is removed from or installed in the game directory.

The native option buffer is 4096 bytes including `ZombieBuddy.jar`, the optional separator/tail and the trailing NUL. It is allocated on the process heap and released after callback return, avoiding a larger stack frame in the CRT-free loader. A C regression checks exact capacity, overflow refusal, guard bytes, argument/return-value forwarding and that overflow does not invoke the callback. Additional real bundled-JVM cases verify intact delivery at exactly 4096 bytes and refusal at 4097 bytes. Imports remain KERNEL32/USER32 only; the three original agent exports remain unchanged. Conservative Java-side forwarding limits remain compatible with both old and expanded native loaders.

Every ordinary case passes its staged `jre64/bin` junction to the native host. The Unicode/spaces case uses an explicit `unicode` mode: before JVM creation, the host checks the real `jvm.dll` module path with `GetModuleFileNameW`, requires a non-ASCII character and verifies that the requested staged path was retained. After initialization the Java probe checks exactly one JVM, Java and instrumentation module, their file identities and their actual staged Unicode paths. File identity alone is not Unicode-path evidence because the junction and original runtime identify the same files. The separate missing-dependency case retains its deliberately incomplete runtime.

The Unicode case loads the candidate agent DLL from its build path, outside the Unicode stage, because JNI agent options use the Windows code page (for example, Japanese ACP 932 cannot represent `Ä`). Keep the checkout/build and classpath roots representable in that code page. The host executable and staged runtime remain under the Unicode path; all three runtime-module path assertions stay active.

The same case also runs a separately compiled test host with `ZB_TEST_NATIVE_CODEPAGE=932`. This uses the actual Windows conversion API for agent/classpath options while retaining the Unicode host/runtime and the unchanged path assertions. It covers CP932 option transport on the current Windows installation; it does not change the OS locale or claim a full Japanese-Windows test. The override exists only in the test host, not the production DLL.

These results do not establish full game/client/Coop/dedicated acceptance or native-attach behavior on an existing production JVM.

## Stable guard failure codes

Every guard refusal keeps its existing message prefix/text and appends `[code=ZB_GUARD_...]` on the same line. Successful startup adds no guard event. Codes describe the guard failure category, not a Windows `GetLastError` value. In particular, identity mismatch and an unreadable/unsupported identity share `ZB_GUARD_MODULE_IDENTITY`; that code alone does not prove a foreign copy.

| Code                       | Meaning                                          |
| -------------------------- | ------------------------------------------------ |
| `ZB_GUARD_SNAPSHOT`        | Cannot create the loaded-module snapshot.        |
| `ZB_GUARD_ENUMERATION`     | Cannot start or complete module enumeration.     |
| `ZB_GUARD_MODULE_PATH`     | Cannot resolve a loaded dependency path.         |
| `ZB_GUARD_MODULE_IDENTITY` | Dependency identity differs or cannot be read.   |
| `ZB_GUARD_MODULE_COUNT`    | Missing/duplicate JVM or duplicate dependencies. |
| `ZB_GUARD_JVM_MISSING`     | Cannot find the active JVM module.               |
| `ZB_GUARD_JVM_PATH`        | Cannot resolve the active JVM path.              |
| `ZB_GUARD_JVM_LAYOUT`      | JVM is outside the supported server/client form. |
| `ZB_GUARD_PATH_LENGTH`     | Runtime dependency path exceeds capacity.        |
| `ZB_GUARD_JAVA_LOAD`       | Active runtime Java library cannot be loaded.    |
| `ZB_GUARD_INSTRUMENT_LOAD` | Active instrumentation library cannot be loaded. |
| `ZB_GUARD_AGENT_EXPORTS`   | Instrumentation library lacks agent callbacks.   |

The order probe's failure cases assert the identity code in their captured output. Existing real-host failure cases still exercise copied modules, duplicates and missing canonical dependencies with the unchanged refusal behavior.
