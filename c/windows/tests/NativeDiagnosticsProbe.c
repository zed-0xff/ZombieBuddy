/* Exercise real guard formatting/writes with controlled output failures. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
static HANDLE WINAPI captured_handle(DWORD kind);
static BOOL WINAPI captured_write(HANDLE file, LPCVOID bytes, DWORD size, LPDWORD written, LPOVERLAPPED overlap);
static void WINAPI captured_debug_w(LPCWSTR message);
static void WINAPI captured_debug_a(LPCSTR message);
#define GetStdHandle captured_handle
#define WriteFile captured_write
#define OutputDebugStringW captured_debug_w
#define OutputDebugStringA captured_debug_a
#include "../zbNative.c"
#undef GetStdHandle
#undef WriteFile
#undef OutputDebugStringW
#undef OutputDebugStringA

static char captured[16384];
static WCHAR debug[16384];
static DWORD capturedSize;
static int mode, debugCalls, ansiCalls;

static HANDLE WINAPI captured_handle(DWORD kind) {
    if (kind != STD_ERROR_HANDLE) return GetStdHandle(kind);
    return mode == 4 ? NULL : mode == 5 ? INVALID_HANDLE_VALUE : (HANDLE)(UINT_PTR)42;
}

static BOOL WINAPI captured_write(HANDLE file, LPCVOID bytes, DWORD size, LPDWORD written, LPOVERLAPPED overlap) {
    DWORD i, count = mode == 1 && size > 3 ? 3 : size;
    const char *text = bytes;
    (void)overlap;
    if (file != (HANDLE)(UINT_PTR)42) ExitProcess(81);
    if (mode == 2) { SetLastError(ERROR_ACCESS_DENIED); return FALSE; }
    if (mode == 3) { *written = 0; return TRUE; }
    if (capturedSize + count >= sizeof(captured)) ExitProcess(82);
    for (i = 0; i < count; i++) captured[capturedSize++] = text[i];
    captured[capturedSize] = 0; *written = count;
    return TRUE;
}

static void WINAPI captured_debug_w(LPCWSTR message) {
    if (lstrlenW(debug) + lstrlenW(message) >= 16384) ExitProcess(83);
    lstrcatW(debug, message); debugCalls++;
}

static void WINAPI captured_debug_a(LPCSTR message) { (void)message; ansiCalls++; }

static int contains(const char *text, const char *needle) {
    const char *at;
    for (; *text; text++) {
        for (at = needle; *at && text[at - needle] == *at; at++) {}
        if (!*at) return 1;
    }
    return 0;
}

static void reset_capture(int nextMode) {
    mode = nextMode; capturedSize = 0; captured[0] = 0;
    debug[0] = 0; debugCalls = ansiCalls = 0;
}

void mainCRTStartup(void) {
    int i;
    DWORD written;
    RuntimeIdentityFailure failure;
    const char *message = "Unicode path \xc3\x84\n";
    for (i = 0; i < 6; i++) {
        reset_capture(i); SetLastError(12345);
        guard_write(message);
        if (GetLastError() != 12345 || ansiCalls) ExitProcess(84);
        if (i < 2) {
            if (lstrcmpA(captured, message) || debugCalls) ExitProcess(85);
        } else if (lstrcmpW(debug, L"Unicode path \x00c4\n") || !debugCalls) ExitProcess(86);
    }
    reset_capture(0);
    failure.kind = IDENTITY_OPEN_FAILED; failure.error = ERROR_ACCESS_DENIED; failure.path = L"runtime.dll";
    if (guard_identity_fail(&failure) || !contains(captured, "[operation=CreateFileW] [win32=5]")
        || !contains(captured, "read permissions") || contains(captured, "remove only confirmed")) ExitProcess(87);
    reset_capture(0);
    failure.kind = IDENTITY_QUERY_FAILED; failure.error = ERROR_NOT_SUPPORTED;
    if (guard_identity_fail(&failure) || !contains(captured, "[operation=FileIdInfo] [win32=50]")
        || !contains(captured, "filesystem must support FileIdInfo")
        || !contains(captured, "doc/Installation.md#windows-native-loader-requirement")
        || contains(captured, "remove only confirmed")) ExitProcess(88);
    reset_capture(0);
    failure.kind = IDENTITY_MISMATCH; failure.error = 0;
    if (guard_identity_fail(&failure) || !contains(captured, "Unexpected already-loaded JVM DLL:")
        || !contains(captured, "[code=ZB_GUARD_MODULE_IDENTITY]")
        || !contains(captured, "remove only confirmed") || contains(captured, "[win32=")) ExitProcess(89);
    reset_capture(0);
    if (guard_fail("ZB_GUARD_JVM_LAYOUT", "Unsupported JVM directory:", NULL)
        || contains(captured, "remove only confirmed")) ExitProcess(90);
    {
        const char *pass = "PASS guard diagnostics: distinct causes/Win32 codes/advice; missing/invalid/failed/zero/partial stderr; Unicode debug fallback; last-error preservation\n";
        WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), pass, (DWORD)lstrlenA(pass), &written, NULL);
    }
    ExitProcess(0);
}
