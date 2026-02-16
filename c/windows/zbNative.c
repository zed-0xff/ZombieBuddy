#define WIN32_LEAN_AND_MEAN
#include <windows.h>

HMODULE hOrig = NULL;

// Minimal DllMain to prevent CRT initialization
BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    return TRUE;
}

int  (*pAgent_OnAttach)(void*, char*, void*) = NULL;
int  (*pAgent_OnLoad)(void*, char*, void*)   = NULL;
void (*pAgent_OnUnload)(void*)               = NULL;

void init_instrument_dll() {
    if (hOrig) return; // already loaded

    SetDllDirectoryA(".\\jre64\\bin");
    hOrig = LoadLibraryA("instrument.dll");
    SetDllDirectoryA(NULL);

    if (!hOrig) {
        WriteConsoleA(GetStdHandle(STD_OUTPUT_HANDLE), "[zbNative] Failed to load instrument.dll\n", 28, NULL, NULL);
        return;
    }

    *(void**)&pAgent_OnAttach = GetProcAddress(hOrig, "Agent_OnAttach");
    *(void**)&pAgent_OnLoad   = GetProcAddress(hOrig, "Agent_OnLoad");
    *(void**)&pAgent_OnUnload = GetProcAddress(hOrig, "Agent_OnUnload");
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
    WriteConsoleA(GetStdHandle(STD_OUTPUT_HANDLE), "[zbNative] Pending update detected, applying...\n", 50, NULL, NULL);

    // Rename .new file to JAR file, replacing existing file if it exists
    if (MoveFileExA(newJarPath, jarPath, MOVEFILE_REPLACE_EXISTING)) {
        WriteConsoleA(GetStdHandle(STD_OUTPUT_HANDLE), "[zbNative] Successfully applied update\n", 40, NULL, NULL);
    } else {
        WriteConsoleA(GetStdHandle(STD_OUTPUT_HANDLE), "[zbNative] Error: Failed to apply update\n", 42, NULL, NULL);
    }
}

__declspec(dllexport) int Agent_OnLoad(void* jvm, char* tail, void* reserved) {
    if (hOrig == NULL) {
        init_instrument_dll();
    }
    if (!pAgent_OnLoad) {
        return -1;
    }

    // Check for pending update before loading the agent
    const char* jarPath = tail == NULL ? "ZombieBuddy.jar" : tail;
    check_and_apply_update(jarPath);

    return pAgent_OnLoad(jvm, (char*)jarPath, reserved);
}

__declspec(dllexport) int Agent_OnAttach(void* jvm, char* args, void* reserved) {
    if (hOrig == NULL) {
        init_instrument_dll();
    }
    if (!pAgent_OnAttach) {
        return -1;
    }

    return pAgent_OnAttach(jvm, args == NULL ? "ZombieBuddy.jar" : args, reserved);
}

__declspec(dllexport) void Agent_OnUnload(void* jvm) {
    if (hOrig == NULL) {
        return;
    }
    if (pAgent_OnUnload) {
        pAgent_OnUnload(jvm);
    }

    FreeLibrary(hOrig);
    hOrig = NULL;
}

