/* Restrict instrumentation dependencies to the active Windows JVM runtime. */
#include <tlhelp32.h>

#define RUNTIME_PATH_CAPACITY 32768
static WCHAR runtimeJavaPath[RUNTIME_PATH_CAPACITY];
static WCHAR runtimeInstrumentPath[RUNTIME_PATH_CAPACITY];
static WCHAR runtimeLoadedPath[RUNTIME_PATH_CAPACITY];
/* Keep our java.dll reference for the process lifetime. Agent unload does not
 * end the JVM's use of libjava/native state; process teardown releases it. */
static HMODULE runtimeJava = NULL;

/* Messages are UTF-8, including encoded paths. Debug output is a secondary
 * capture channel, not a persistent log when no debugger/collector is active. */
static void guard_debug(const char *message) {
    int size = MultiByteToWideChar(CP_UTF8, 0, message, -1, NULL, 0);
    WCHAR *wide = size > 0 ? HeapAlloc(GetProcessHeap(), 0, (SIZE_T)size * sizeof(WCHAR)) : NULL;
    if (wide) {
        if (MultiByteToWideChar(CP_UTF8, 0, message, -1, wide, size)) OutputDebugStringW(wide);
        else OutputDebugStringA(message);
        HeapFree(GetProcessHeap(), 0, wide);
    } else OutputDebugStringA(message);
}

static void guard_write(const char *message) {
    DWORD savedError = GetLastError(), written, offset = 0, size = (DWORD)lstrlenA(message);
    HANDLE output = GetStdHandle(STD_ERROR_HANDLE);
    if (output != NULL && output != INVALID_HANDLE_VALUE) {
        while (offset < size) {
            if (!WriteFile(output, message + offset, size - offset, &written, NULL) || !written) break;
            offset += written;
        }
    }
    if (offset < size) guard_debug(message);
    SetLastError(savedError);
}

static void guard_write_number(DWORD value) {
    char digits[11];
    unsigned int at = sizeof(digits) - 1;
    digits[at] = 0;
    do { digits[--at] = (char)('0' + value % 10); value /= 10; } while (value);
    guard_write(digits + at);
}

static void guard_write_path(const WCHAR *path) {
    int size = WideCharToMultiByte(CP_UTF8, 0, path, -1, NULL, 0, NULL, NULL);
    if (size > 0) {
        char *encoded = HeapAlloc(GetProcessHeap(), 0, (SIZE_T)size);
        if (encoded) {
            WideCharToMultiByte(CP_UTF8, 0, path, -1, encoded, size, NULL, NULL);
            guard_write(encoded);
            HeapFree(GetProcessHeap(), 0, encoded);
        }
    }
}

/* Stable machine-readable codes complement the existing human-readable text. */
static int guard_fail_detail(const char *code, const char *reason, const WCHAR *path,
        const char *operation, DWORD error, const char *advice) {
    guard_write("[ZB-NATIVE] JVM DLL guard: ");
    guard_write(reason);
    if (path) { guard_write(" "); guard_write_path(path); }
    guard_write(" [code="); guard_write(code); guard_write("]");
    if (operation) {
        guard_write(" [operation="); guard_write(operation); guard_write("] [win32=");
        guard_write_number(error); guard_write("]");
    }
    guard_write("\n[ZB-NATIVE] Agent initialization stopped. ");
    if (advice) guard_write(advice);
    else guard_write("Check the active JVM installation and access permissions. ");
    guard_write("Keep active runtime DLLs intact. See doc/Installation.md#windows-native-loader-requirement.\n");
    return 0;
}

static int guard_fail(const char *code, const char *reason, const WCHAR *path) {
    return guard_fail_detail(code, reason, path, NULL, 0, NULL);
}

static int guard_fail_system(const char *code, const char *reason, const WCHAR *path, DWORD error) {
    return guard_fail_detail(code, reason, path, "Win32", error, NULL);
}

enum RuntimeIdentityKind { IDENTITY_MATCH, IDENTITY_MISMATCH, IDENTITY_OPEN_FAILED, IDENTITY_QUERY_FAILED };
typedef struct {
    enum RuntimeIdentityKind kind;
    DWORD error;
    const WCHAR *path;
} RuntimeIdentityFailure;

static int guard_identity_fail(const RuntimeIdentityFailure *failure) {
    if (failure->kind == IDENTITY_OPEN_FAILED)
        return guard_fail_detail("ZB_GUARD_MODULE_IDENTITY", "Cannot open JVM DLL for identity:",
            failure->path, "CreateFileW", failure->error,
            "Check file paths and read permissions; verify game files with Steam. Do not delete DLLs based on this read failure. ");
    if (failure->kind == IDENTITY_QUERY_FAILED)
        return guard_fail_detail("ZB_GUARD_MODULE_IDENTITY", "Cannot query JVM DLL identity:",
            failure->path, "FileIdInfo", failure->error,
            "The runtime filesystem must support FileIdInfo and permit identity queries. Check filesystem support and access permissions; do not delete DLLs based on this query failure. ");
    return guard_fail_detail("ZB_GUARD_MODULE_IDENTITY", "Unexpected already-loaded JVM DLL:",
        failure->path, NULL, 0,
        "Close the game, remove only confirmed leftover Java DLL copies outside the active runtime and verify game files with Steam. ");
}

/* Compare all 128 file-ID bits: ReFS does not guarantee unique 64-bit IDs.
 * Equivalent junction/hardlink paths are allowed; copied files are not. */
static int same_runtime_file(const WCHAR *left, const WCHAR *right, RuntimeIdentityFailure *failure) {
    FILE_ID_INFO a, b;
    unsigned int i;
    HANDLE first, second = INVALID_HANDLE_VALUE;
    failure->kind = IDENTITY_OPEN_FAILED; failure->error = 0; failure->path = left;
    first = CreateFileW(left, FILE_READ_ATTRIBUTES,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING, 0, NULL);
    if (first == INVALID_HANDLE_VALUE) { failure->error = GetLastError(); goto cleanup; }
    failure->path = right;
    second = CreateFileW(right, FILE_READ_ATTRIBUTES,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING, 0, NULL);
    if (second == INVALID_HANDLE_VALUE) { failure->error = GetLastError(); goto cleanup; }
    failure->kind = IDENTITY_QUERY_FAILED; failure->path = left;
    if (!GetFileInformationByHandleEx(first, FileIdInfo, &a, sizeof(a))) {
        failure->error = GetLastError(); goto cleanup;
    }
    failure->path = right;
    if (!GetFileInformationByHandleEx(second, FileIdInfo, &b, sizeof(b))) {
        failure->error = GetLastError(); goto cleanup;
    }
    failure->kind = IDENTITY_MISMATCH; failure->path = left;
    if (a.VolumeSerialNumber == b.VolumeSerialNumber) {
        for (i = 0; i < sizeof(a.FileId.Identifier); i++) {
            if (a.FileId.Identifier[i] != b.FileId.Identifier[i]) goto cleanup;
        }
        failure->kind = IDENTITY_MATCH;
    }
cleanup:
    /* Failure details are captured before cleanup can overwrite GetLastError. */
    if (first != INVALID_HANDLE_VALUE) CloseHandle(first);
    if (second != INVALID_HANDLE_VALUE) CloseHandle(second);
    return failure->kind == IDENTITY_MATCH;
}

static int check_runtime_modules(void) {
    MODULEENTRY32W entry;
    DWORD enumerationError;
    DWORD javaCount = 0, instrumentCount = 0, jvmCount = 0;
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, GetCurrentProcessId());
    if (snapshot == INVALID_HANDLE_VALUE) return guard_fail_system("ZB_GUARD_SNAPSHOT", "Cannot inspect loaded JVM DLLs.", NULL, GetLastError());
    entry.dwSize = sizeof(entry);
    if (!Module32FirstW(snapshot, &entry)) {
        DWORD error = GetLastError();
        CloseHandle(snapshot);
        return guard_fail_system("ZB_GUARD_ENUMERATION", "Cannot enumerate loaded JVM DLLs.", NULL, error);
    }
    do {
        const WCHAR *expected = NULL;
        if (lstrcmpiW(entry.szModule, L"java.dll") == 0) { javaCount++; expected = runtimeJavaPath; }
        if (lstrcmpiW(entry.szModule, L"instrument.dll") == 0) { instrumentCount++; expected = runtimeInstrumentPath; }
        if (lstrcmpiW(entry.szModule, L"jvm.dll") == 0) jvmCount++;
        if (expected) {
            RuntimeIdentityFailure failure;
            DWORD size = GetModuleFileNameW(entry.hModule, runtimeLoadedPath, RUNTIME_PATH_CAPACITY);
            if (!size || size >= RUNTIME_PATH_CAPACITY) {
                DWORD error = size ? ERROR_INSUFFICIENT_BUFFER : GetLastError();
                CloseHandle(snapshot);
                return guard_fail_system("ZB_GUARD_MODULE_PATH", "Cannot resolve a loaded JVM DLL path.", NULL, error);
            }
            if (!same_runtime_file(runtimeLoadedPath, expected, &failure)) {
                CloseHandle(snapshot);
                return guard_identity_fail(&failure);
            }
        }
    } while (Module32NextW(snapshot, &entry));
    enumerationError = GetLastError();
    CloseHandle(snapshot);
    /* Only normal end-of-list proves that every loaded module was inspected. */
    if (enumerationError != ERROR_NO_MORE_FILES)
        return guard_fail_system("ZB_GUARD_ENUMERATION", "Loaded-module enumeration did not complete.", NULL, enumerationError);
    if (javaCount > 1 || instrumentCount > 1 || jvmCount != 1)
        return guard_fail("ZB_GUARD_MODULE_COUNT", "Multiple JVM DLL instances are already loaded.", NULL);
    return 1;
}

static HMODULE load_runtime_instrument(void) {
    WCHAR *last;
    DWORD length;
    HMODULE instrument;
    /* Anchor to the running JVM, not an installation/staging path. The module
     * enumeration below rejects ambiguous multiple JVMs before loading deps. */
    HMODULE jvm = GetModuleHandleW(L"jvm.dll");
    if (!jvm) { guard_fail_system("ZB_GUARD_JVM_MISSING", "Cannot locate the active jvm.dll.", NULL, GetLastError()); return NULL; }
    length = GetModuleFileNameW(jvm, runtimeJavaPath, RUNTIME_PATH_CAPACITY);
    if (!length || length >= RUNTIME_PATH_CAPACITY) {
        guard_fail_system("ZB_GUARD_JVM_PATH", "Cannot resolve the active JVM directory.", NULL,
            length ? ERROR_INSUFFICIENT_BUFFER : GetLastError()); return NULL;
    }
    /* Supported Windows runtime layout: bin/server/jvm.dll (or bin/client/jvm.dll). */
    last = runtimeJavaPath + length;
    while (last > runtimeJavaPath && *last != L'\\') last--;
    *last = 0;
    while (last > runtimeJavaPath && *last != L'\\') last--;
    if (lstrcmpiW(last + 1, L"server") && lstrcmpiW(last + 1, L"client")) {
        guard_fail("ZB_GUARD_JVM_LAYOUT", "Unsupported JVM directory:", runtimeJavaPath); return NULL;
    }
    *last = 0;
    if (lstrlenW(runtimeJavaPath) + 16 >= RUNTIME_PATH_CAPACITY) {
        guard_fail("ZB_GUARD_PATH_LENGTH", "Runtime DLL path is too long.", NULL); return NULL;
    }
    lstrcpyW(runtimeInstrumentPath, runtimeJavaPath);
    lstrcatW(runtimeJavaPath, L"\\java.dll");
    lstrcatW(runtimeInstrumentPath, L"\\instrument.dll");
    if (!check_runtime_modules()) return NULL;
    /* Load the canonical java.dll first. Its state will be initialized by this JVM. */
    runtimeJava = LoadLibraryExW(runtimeJavaPath, NULL,
        LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!runtimeJava) { guard_fail_system("ZB_GUARD_JAVA_LOAD", "Cannot load active runtime java.dll:", runtimeJavaPath, GetLastError()); return NULL; }
    if (!check_runtime_modules()) return NULL;
    instrument = LoadLibraryExW(runtimeInstrumentPath, NULL,
        LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!instrument) { guard_fail_system("ZB_GUARD_INSTRUMENT_LOAD", "Cannot load active runtime instrument.dll:", runtimeInstrumentPath, GetLastError()); return NULL; }
    if (!check_runtime_modules()) { FreeLibrary(instrument); return NULL; }
    return instrument;
}
