#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include "runtime_guard.h"

HMODULE hOrig = NULL;

#define ZB_JAR "ZombieBuddy.jar"
// Total payload capacity, including the JAR prefix and trailing NUL.
#define AGENT_OPTIONS_MAX 4096

// Minimal DllMain to prevent CRT initialization
BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL;
    (void)fdwReason;
    (void)lpvReserved;
    return TRUE;
}

int  (*pAgent_OnAttach)(void*, char*, void*) = NULL;
int  (*pAgent_OnLoad)(void*, char*, void*)   = NULL;
void (*pAgent_OnUnload)(void*)               = NULL;

static void clear_agent_callbacks(void) {
    pAgent_OnAttach = NULL;
    pAgent_OnLoad = NULL;
    pAgent_OnUnload = NULL;
}

void write_msg(const char* msg) {
    WriteConsoleA(GetStdHandle(STD_OUTPUT_HANDLE), msg, lstrlenA(msg), NULL, NULL);
}

void init_instrument_dll() {
    union {
        FARPROC raw;
        int (*entry)(void*, char*, void*);
        void (*unload)(void*);
    } proc;
    if (hOrig) return; // already loaded

    /* A failed retry must never reuse callbacks from an earlier library load. */
    clear_agent_callbacks();
    hOrig = load_runtime_instrument();

    if (!hOrig) {
        write_msg("[zbNative] Failed to load instrument.dll\n");
        return;
    }

    proc.raw = GetProcAddress(hOrig, "Agent_OnAttach");
    pAgent_OnAttach = proc.entry;
    proc.raw = GetProcAddress(hOrig, "Agent_OnLoad");
    pAgent_OnLoad = proc.entry;
    proc.raw = GetProcAddress(hOrig, "Agent_OnUnload");
    pAgent_OnUnload = proc.unload;
    if (!pAgent_OnAttach || !pAgent_OnLoad || !pAgent_OnUnload) {
        guard_fail("ZB_GUARD_AGENT_EXPORTS", "Active runtime instrument.dll lacks agent exports.", runtimeInstrumentPath);
        FreeLibrary(hOrig);
        hOrig = NULL;
        clear_agent_callbacks();
    }
}

void check_and_apply_update(const char* jarPath) {
    char newJarPath[1024];
    wsprintf(newJarPath, "%s.new", jarPath);

    // Check if .new file exists
    DWORD attrs = GetFileAttributesA(newJarPath);
    if (attrs == INVALID_FILE_ATTRIBUTES || (attrs & FILE_ATTRIBUTE_DIRECTORY)) {
        return; // No update pending
    }

    // Update is pending - apply it
    write_msg("[zbNative] Pending update detected, applying...\n");

    // Rename .new file to JAR file, replacing existing file if it exists
    if (MoveFileExA(newJarPath, jarPath, MOVEFILE_REPLACE_EXISTING)) {
        write_msg("[zbNative] Successfully applied update\n");
    } else {
        write_msg("[zbNative] Error: Failed to apply update\n");
    }
}

int build_agent_options(const char* tail, char* out, int outSize) {
    int jarLen = lstrlenA(ZB_JAR);
    int tailLen = tail == NULL ? 0 : lstrlenA(tail);
    int needsArgs = tailLen > 0;
    int totalLen = jarLen + (needsArgs ? 1 + tailLen : 0);

    if (totalLen + 1 > outSize) {
        write_msg("[zbNative] Error: agent options are too long\n");
        return 0;
    }

    lstrcpyA(out, ZB_JAR);
    if (needsArgs) {
        out[jarLen] = '=';
        lstrcpyA(out + jarLen + 1, tail);
    }
    return 1;
}

/* Keep the larger option buffer off the stack in this CRT-free loader. */
int invoke_agent(int (*entry)(void*, char*, void*), void* jvm, char* tail, void* reserved) {
    int result = -1;
    char* agentOptions = HeapAlloc(GetProcessHeap(), 0, AGENT_OPTIONS_MAX);
    if (!agentOptions) {
        write_msg("[zbNative] Error: cannot allocate agent options\n");
        return -1;
    }
    if (build_agent_options(tail, agentOptions, AGENT_OPTIONS_MAX))
        result = entry(jvm, agentOptions, reserved);
    HeapFree(GetProcessHeap(), 0, agentOptions);
    return result;
}

__declspec(dllexport) int Agent_OnLoad(void* jvm, char* tail, void* reserved) {
    if (hOrig == NULL) {
        init_instrument_dll();
    }
    if (!pAgent_OnLoad) {
        return -1;
    }

    // Check for pending update before loading the agent
    check_and_apply_update(ZB_JAR);

    return invoke_agent(pAgent_OnLoad, jvm, tail, reserved);
}

__declspec(dllexport) int Agent_OnAttach(void* jvm, char* args, void* reserved) {
    if (hOrig == NULL) {
        init_instrument_dll();
    }
    if (!pAgent_OnAttach) {
        return -1;
    }

    return invoke_agent(pAgent_OnAttach, jvm, args, reserved);
}

__declspec(dllexport) void Agent_OnUnload(void* jvm) {
    if (hOrig == NULL) {
        clear_agent_callbacks();
        return;
    }
    if (pAgent_OnUnload) {
        pAgent_OnUnload(jvm);
    }

    /* Release our instrumentation handle; runtimeJava stays held until process exit. */
    FreeLibrary(hOrig);
    hOrig = NULL;
    clear_agent_callbacks();
}
