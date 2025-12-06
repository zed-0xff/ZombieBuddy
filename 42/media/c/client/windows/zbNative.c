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

__declspec(dllexport) int Agent_OnLoad(void* jvm, char* tail, void* reserved) {
    if (hOrig == NULL) {
        init_instrument_dll();
    }
    if (!pAgent_OnLoad) {
        return -1;
    }

    return pAgent_OnLoad(jvm, tail == NULL ? "ZombieBuddy.jar" : tail, reserved);
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

