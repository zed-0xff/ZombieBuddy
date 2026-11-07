/* Real module enumeration/file identity and real guard, without creating a VM.
 * Only guard-owned LoadLibraryExW calls and identity query failures are observed
 * or controlled. Setup loads use the real API outside the observation seam. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <tlhelp32.h>
static HMODULE WINAPI observed_load(LPCWSTR path, HANDLE file, DWORD flags);
static BOOL WINAPI controlled_identity(HANDLE file, FILE_INFO_BY_HANDLE_CLASS kind, LPVOID value, DWORD size);
#define LoadLibraryExW observed_load
#define GetFileInformationByHandleEx controlled_identity
#include "../zbNative.c"
#undef LoadLibraryExW
#undef GetFileInformationByHandleEx

static WCHAR jvmPath[32768], javaPath[32768], instrumentPath[32768];
static int guardLoads, queries, failedQuery, allowedLoads;

static HMODULE WINAPI observed_load(LPCWSTR path, HANDLE file, DWORD flags) {
    guardLoads++;
    if (guardLoads > allowedLoads || file != NULL
        || flags != (LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32)) ExitProcess(40);
    if (lstrcmpiW(path, guardLoads == 1 ? javaPath : instrumentPath)) ExitProcess(41);
    return LoadLibraryExW(path, file, flags);
}

static BOOL WINAPI controlled_identity(HANDLE file, FILE_INFO_BY_HANDLE_CLASS kind, LPVOID value, DWORD size) {
    queries++;
    /* A filesystem without FileIdInfo must not enable string/64-bit fallback. */
    if (queries == failedQuery) { SetLastError(ERROR_NOT_SUPPORTED); return FALSE; }
    return GetFileInformationByHandleEx(file, kind, value, size);
}

static DWORD module_count(const WCHAR *name) {
    MODULEENTRY32W entry;
    DWORD count = 0, error;
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, GetCurrentProcessId());
    if (snapshot == INVALID_HANDLE_VALUE) ExitProcess(42);
    entry.dwSize = sizeof(entry);
    if (!Module32FirstW(snapshot, &entry)) ExitProcess(43);
    do { if (!lstrcmpiW(entry.szModule, name)) count++; } while (Module32NextW(snapshot, &entry));
    error = GetLastError(); CloseHandle(snapshot);
    if (error != ERROR_NO_MORE_FILES) ExitProcess(44);
    return count;
}

void mainCRTStartup(void) {
    int count, control, foreign, afterJava;
    WCHAR **args = CommandLineToArgvW(GetCommandLineW(), &count);
    HMODULE instrument;
    if (count != 4 || lstrlenW(args[1]) + 32 >= 32768) ExitProcess(45);
    control = !lstrcmpW(args[3], L"control");
    foreign = !lstrcmpW(args[3], L"foreign");
    afterJava = !lstrcmpW(args[3], L"after-java");
    if (!control && !foreign && !afterJava && lstrcmpW(args[3], L"unsupported-first")
        && lstrcmpW(args[3], L"unsupported-second")) ExitProcess(46);
    lstrcpyW(jvmPath, args[1]); lstrcatW(jvmPath, L"\\server\\jvm.dll");
    lstrcpyW(javaPath, args[1]); lstrcatW(javaPath, L"\\java.dll");
    lstrcpyW(instrumentPath, args[1]); lstrcatW(instrumentPath, L"\\instrument.dll");
    if (!LoadLibraryExW(jvmPath, NULL, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32)) ExitProcess(47);
    if (!control && !afterJava) {
        if (!LoadLibraryExW(foreign ? args[2] : javaPath, NULL,
            LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32)) ExitProcess(48);
    }
    if (module_count(L"jvm.dll") != 1 || module_count(L"instrument.dll")
        || module_count(L"java.dll") != (DWORD)(!control && !afterJava)) ExitProcess(49);
    allowedLoads = control ? 2 : (afterJava ? 1 : 0);
    if (!control && !foreign) failedQuery = !lstrcmpW(args[3], L"unsupported-second") ? 2 : 1;
    instrument = load_runtime_instrument();
    if (control) {
        if (!instrument || guardLoads != 2 || module_count(L"java.dll") != 1
            || module_count(L"instrument.dll") != 1) ExitProcess(50);
        guard_write("PASS guard order control: actual Java then instrumentation loads observed\n");
    } else {
        if (instrument || guardLoads != allowedLoads || module_count(L"instrument.dll")
            || module_count(L"java.dll") != 1) ExitProcess(51);
        if (!foreign && queries != failedQuery) ExitProcess(52);
        guard_write(afterJava
            ? "PASS guard order: ERROR_NOT_SUPPORTED after Java load prevents instrumentation load\n"
            : "PASS guard order: precheck refuses conflict/unsupported identity before any guard-owned dependency load\n");
    }
    ExitProcess(0);
}
