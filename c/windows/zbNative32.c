#define WIN32_LEAN_AND_MEAN
#include <windows.h>

HMODULE hOrig = NULL;

#define ZB_JAR "ZombieBuddy.jar"
#define AGENT_OPTIONS_MAX 2048

// Minimal DllMain to prevent CRT initialization
BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    return TRUE;
}

int  (__stdcall *pAgent_OnAttach)(void*, char*, void*) = NULL;
int  (__stdcall *pAgent_OnLoad)(void*, char*, void*)   = NULL;
void (__stdcall *pAgent_OnUnload)(void*)               = NULL;

void write_msg(const char* msg) {
    WriteConsoleA(GetStdHandle(STD_OUTPUT_HANDLE), msg, lstrlenA(msg), NULL, NULL);
}

// ---- UNICA DIFERENCIA REAL vs zbNative.c (64-bit) ----
// El JRE de 32 bits que trae Project Zomboid vive en ".\jre\bin",
// no en ".\jre64\bin" (esa es la carpeta que usa el build de 64 bits).
void init_instrument_dll() {
    if (hOrig) return; // already loaded

    SetDllDirectoryA(".\\jre\\bin");
    hOrig = LoadLibraryA("instrument.dll");
    SetDllDirectoryA(NULL);

    if (!hOrig) {
        write_msg("[zbNative32] Failed to load instrument.dll\n");
        return;
    }

    *(void**)&pAgent_OnAttach = GetProcAddress(hOrig, "_Agent_OnAttach@12");
    *(void**)&pAgent_OnLoad   = GetProcAddress(hOrig, "_Agent_OnLoad@12");
    *(void**)&pAgent_OnUnload = GetProcAddress(hOrig, "_Agent_OnUnload@4");

    if (!pAgent_OnLoad || !pAgent_OnAttach || !pAgent_OnUnload) {
        write_msg("[zbNative32] Failed to resolve Agent_On* entry points in instrument.dll\n");
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
    write_msg("[zbNative32] Pending update detected, applying...\n");

    // Rename .new file to JAR file, replacing existing file if it exists
    if (MoveFileExA(newJarPath, jarPath, MOVEFILE_REPLACE_EXISTING)) {
        write_msg("[zbNative32] Successfully applied update\n");
    } else {
        write_msg("[zbNative32] Error: Failed to apply update\n");
    }
}

int build_agent_options(const char* tail, char* out, int outSize) {
    int jarLen = lstrlenA(ZB_JAR);
    int tailLen = tail == NULL ? 0 : lstrlenA(tail);
    int needsArgs = tailLen > 0;
    int totalLen = jarLen + (needsArgs ? 1 + tailLen : 0);

    if (totalLen + 1 > outSize) {
        write_msg("[zbNative32] Error: agent options are too long\n");
        return 0;
    }

    lstrcpyA(out, ZB_JAR);
    if (needsArgs) {
        out[jarLen] = '=';
        lstrcpyA(out + jarLen + 1, tail);
    }
    return 1;
}

__declspec(dllexport) int __stdcall Agent_OnLoad(void* jvm, char* tail, void* reserved) {
    if (hOrig == NULL) {
        init_instrument_dll();
    }
    if (!pAgent_OnLoad) {
        return -1;
    }

    // Check for pending update before loading the agent
    check_and_apply_update(ZB_JAR);

    char agentOptions[AGENT_OPTIONS_MAX];
    if (!build_agent_options(tail, agentOptions, sizeof(agentOptions))) {
        return -1;
    }

    return pAgent_OnLoad(jvm, agentOptions, reserved);
}

__declspec(dllexport) int __stdcall Agent_OnAttach(void* jvm, char* args, void* reserved) {
    if (hOrig == NULL) {
        init_instrument_dll();
    }
    if (!pAgent_OnAttach) {
        return -1;
    }

    char agentOptions[AGENT_OPTIONS_MAX];
    if (!build_agent_options(args, agentOptions, sizeof(agentOptions))) {
        return -1;
    }

    return pAgent_OnAttach(jvm, agentOptions, reserved);
}

__declspec(dllexport) void __stdcall Agent_OnUnload(void* jvm) {
    if (hOrig == NULL) {
        return;
    }
    if (pAgent_OnUnload) {
        pAgent_OnUnload(jvm);
    }

    FreeLibrary(hOrig);
    hOrig = NULL;
}
