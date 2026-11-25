/* Real loader callbacks/guard and runtime modules, without creating a JVM.
 * Only callback exports, unload observation, pending-update inspection and
 * an unavailable-JVM failure are controlled inside this standalone test. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
static HMODULE WINAPI controlled_module(LPCWSTR name);
static FARPROC WINAPI controlled_export(HMODULE library, LPCSTR name);
static BOOL WINAPI observed_free(HMODULE library);
static DWORD WINAPI no_pending_update(LPCSTR path);
#define GetModuleHandleW controlled_module
#define GetProcAddress controlled_export
#define FreeLibrary observed_free
#define GetFileAttributesA no_pending_update
#include "../zbNative.c"
#undef GetModuleHandleW
#undef GetProcAddress
#undef FreeLibrary
#undef GetFileAttributesA

static WCHAR jvmPath[32768];
static int vmToken, reservedToken, missingJvm, missingExport;
static int loads, attaches, unloads, frees, event, unloadEvent, freeEvent;

static void require(int condition, UINT error) { if (!condition) ExitProcess(error); }
static void require_clear(void) {
    require(!hOrig && !pAgent_OnLoad && !pAgent_OnAttach && !pAgent_OnUnload, 51);
}
static int record_load(void *vm, char *options, void *reserved) {
    require(!missingJvm && vm == &vmToken && reserved == &reservedToken
        && !lstrcmpA(options, "ZombieBuddy.jar=probe"), 52);
    loads++; return 23;
}
static int record_attach(void *vm, char *options, void *reserved) {
    require(!missingJvm && vm == &vmToken && reserved == &reservedToken
        && !lstrcmpA(options, "ZombieBuddy.jar=probe"), 53);
    attaches++; return 29;
}
static void record_unload(void *vm) {
    require(vm == &vmToken && hOrig && pAgent_OnLoad && pAgent_OnAttach && pAgent_OnUnload, 54);
    unloads++; unloadEvent = ++event;
}
static void seed_stale_callbacks(void) {
    pAgent_OnLoad = record_load; pAgent_OnAttach = record_attach; pAgent_OnUnload = record_unload;
}
static HMODULE WINAPI controlled_module(LPCWSTR name) {
    if (missingJvm && !lstrcmpiW(name, L"jvm.dll")) return NULL;
    return GetModuleHandleW(name);
}
static FARPROC WINAPI controlled_export(HMODULE library, LPCSTR name) {
    union { FARPROC raw; int (*entry)(void*, char*, void*); void (*unload)(void*); } proc;
    require(library == hOrig && library != NULL, 55);
    if (!lstrcmpA(name, "Agent_OnLoad")) { proc.entry = record_load; return proc.raw; }
    if (!lstrcmpA(name, "Agent_OnAttach")) {
        if (missingExport) return NULL;
        proc.entry = record_attach; return proc.raw;
    }
    if (!lstrcmpA(name, "Agent_OnUnload")) { proc.unload = record_unload; return proc.raw; }
    return GetProcAddress(library, name);
}
static BOOL WINAPI observed_free(HMODULE library) {
    require(library != NULL && library != runtimeJava, 56);
    frees++; freeEvent = ++event;
    return FreeLibrary(library);
}
static DWORD WINAPI no_pending_update(LPCSTR path) {
    require(!lstrcmpA(path, "ZombieBuddy.jar.new"), 57);
    return INVALID_FILE_ATTRIBUTES;
}

void mainCRTStartup(void) {
    int count, cycle;
    HMODULE retainedJava = NULL;
    WCHAR **args = CommandLineToArgvW(GetCommandLineW(), &count);
    require(count == 2 && lstrlenW(args[1]) + 32 < 32768, 58);
    Agent_OnUnload(&vmToken); require_clear(); require(!unloads && !frees, 59);
    seed_stale_callbacks(); Agent_OnUnload(&vmToken); require_clear();
    require(!unloads && !frees, 60);
    missingJvm = 1;
    seed_stale_callbacks(); require(Agent_OnLoad(&vmToken, "probe", &reservedToken) == -1, 61); require_clear();
    seed_stale_callbacks(); require(Agent_OnAttach(&vmToken, "probe", &reservedToken) == -1, 62); require_clear();
    require(!loads && !attaches && !unloads && !frees, 63);
    guard_write("PASS callback cleanup: empty unload and failed initialization clear stale callbacks without invoking them\n");

    missingJvm = 0;
    lstrcpyW(jvmPath, args[1]); lstrcatW(jvmPath, L"\\server\\jvm.dll");
    require(LoadLibraryExW(jvmPath, NULL, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32) != NULL, 64);
    for (cycle = 1; cycle <= 3; cycle++) {
        require(Agent_OnLoad(&vmToken, "probe", &reservedToken) == 23, 65);
        require(Agent_OnAttach(&vmToken, "probe", &reservedToken) == 29, 66);
        if (!retainedJava) retainedJava = runtimeJava;
        require(retainedJava && runtimeJava == retainedJava, 67);
        unloadEvent = 0; freeEvent = 0;
        Agent_OnUnload(&vmToken); require_clear();
        require(loads == cycle && attaches == cycle && unloads == cycle && frees == cycle
            && unloadEvent > 0 && freeEvent > unloadEvent, 68);
        Agent_OnUnload(&vmToken); require_clear();
        require(unloads == cycle && frees == cycle && runtimeJava == retainedJava, 69);
    }
    missingJvm = 1;
    require(Agent_OnLoad(&vmToken, "probe", &reservedToken) == -1, 70); require_clear();
    require(Agent_OnAttach(&vmToken, "probe", &reservedToken) == -1, 71); require_clear();
    require(loads == 3 && attaches == 3 && unloads == 3 && frees == 3, 72);
    missingJvm = 0; missingExport = 1;
    require(Agent_OnLoad(&vmToken, "probe", &reservedToken) == -1, 73); require_clear();
    Agent_OnUnload(&vmToken); require_clear();
    require(loads == 3 && attaches == 3 && unloads == 3 && frees == 4, 74);
    missingExport = 0;
    require(Agent_OnAttach(&vmToken, "probe", &reservedToken) == 29, 75);
    pAgent_OnUnload = NULL;
    Agent_OnUnload(&vmToken); require_clear();
    require(attaches == 4 && unloads == 3 && frees == 5 && runtimeJava == retainedJava
        && GetModuleHandleW(L"java.dll") == retainedJava, 76);
    guard_write("PASS callback lifecycle: repeated load/attach/unload, failed retry, missing export, exact unload ordering and retained Java reference\n");
    ExitProcess(0);
}
