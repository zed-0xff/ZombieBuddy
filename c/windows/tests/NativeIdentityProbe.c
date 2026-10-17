/* Exercise the real identity comparator with controlled FileIdInfo responses.
 * Real file handles are opened/closed; this does not claim a ReFS volume test. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
static BOOL WINAPI probe_file_info(HANDLE file, FILE_INFO_BY_HANDLE_CLASS kind, LPVOID value, DWORD size);
static BOOL WINAPI probe_close(HANDLE file);
#define GetFileInformationByHandleEx probe_file_info
#define CloseHandle probe_close
#include "../zbNative.c"
#undef GetFileInformationByHandleEx
#undef CloseHandle

static int query, changedByte = -1, changedVolume, failedQuery;
static WCHAR executable[32768], missing[32768];
static RuntimeIdentityFailure failure;

static BOOL WINAPI probe_close(HANDLE file) {
    BOOL closed = CloseHandle(file);
    SetLastError(ERROR_BUSY); /* Cleanup must not replace the captured query/open error. */
    return closed;
}

static BOOL WINAPI probe_file_info(HANDLE file, FILE_INFO_BY_HANDLE_CLASS kind, LPVOID value, DWORD size) {
    FILE_ID_INFO *identity = value;
    unsigned int i;
    (void)file;
    if (kind != FileIdInfo || size != sizeof(*identity)) ExitProcess(20);
    query++;
    /* Unsupported FileIdInfo is a refusal, never permission to use path matching. */
    if (query == failedQuery) { SetLastError(ERROR_NOT_SUPPORTED); return FALSE; }
    identity->VolumeSerialNumber = 1;
    for (i = 0; i < sizeof(identity->FileId.Identifier); i++) identity->FileId.Identifier[i] = (BYTE)i;
    if (query == 2) {
        if (changedByte >= 0) identity->FileId.Identifier[changedByte] ^= 1;
        if (changedVolume) identity->VolumeSerialNumber |= ((ULONGLONG)1 << 63);
    }
    return TRUE;
}

void mainCRTStartup(void) {
    int i;
    DWORD before, after, length = GetModuleFileNameW(NULL, executable, 32768);
    if (!length || length + 48 >= 32768) ExitProcess(21);
    lstrcpyW(missing, executable); lstrcatW(missing, L".missing-638c0838-identity-fixture");
    if (GetFileAttributesW(missing) != INVALID_FILE_ATTRIBUTES) ExitProcess(22);
    if (!GetProcessHandleCount(GetCurrentProcess(), &before)) ExitProcess(23);
    if (!same_runtime_file(executable, executable, &failure) || query != 2 || failure.kind != IDENTITY_MATCH) ExitProcess(24);
    for (i = 0; i < 16; i++) {
        query = 0; changedByte = i;
        if (same_runtime_file(executable, executable, &failure) || query != 2 || failure.kind != IDENTITY_MISMATCH || failure.error) ExitProcess(25);
    }
    changedByte = -1; changedVolume = 1; query = 0;
    if (same_runtime_file(executable, executable, &failure) || query != 2 || failure.kind != IDENTITY_MISMATCH) ExitProcess(26);
    changedVolume = 0;
    for (i = 1; i <= 2; i++) {
        query = 0; failedQuery = i;
        if (same_runtime_file(executable, executable, &failure) || query != i
            || failure.kind != IDENTITY_QUERY_FAILED || failure.error != ERROR_NOT_SUPPORTED) ExitProcess(27);
    }
    failedQuery = 0; query = 0;
    if (same_runtime_file(missing, executable, &failure) || failure.kind != IDENTITY_OPEN_FAILED
        || failure.error != ERROR_FILE_NOT_FOUND || failure.path != missing) ExitProcess(28);
    if (same_runtime_file(executable, missing, &failure) || failure.kind != IDENTITY_OPEN_FAILED
        || failure.error != ERROR_FILE_NOT_FOUND || failure.path != missing || query) ExitProcess(30);
    if (!GetProcessHandleCount(GetCurrentProcess(), &after) || before != after) ExitProcess(29);
    guard_write("PASS file identity: full IDs, distinct mismatch/open/query failures, original Win32 errors retained across cleanup, handles closed\n");
    ExitProcess(0);
}
