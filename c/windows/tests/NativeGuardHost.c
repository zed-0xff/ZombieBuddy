/* Isolated Windows JVM host: no Project Zomboid main or game installation edits. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <tlhelp32.h>
#include <jni.h>

/* Test-only override exercises JNI option conversion without changing the OS locale. */
#ifndef ZB_TEST_NATIVE_CODEPAGE
#define ZB_TEST_NATIVE_CODEPAGE CP_ACP
#endif

static WCHAR libraryPath[32768];
static WCHAR loadedJvmPath[32768];
static char agentOption[65536], classpathOption[65536];
static JavaVMOption options[6];
static int agentTailOffset;

static void log_message(const char *message) {
    DWORD written;
    WriteFile(GetStdHandle(STD_ERROR_HANDLE), message, (DWORD)lstrlenA(message), &written, NULL);
}

static int utf8(const WCHAR *source, char *target, int capacity) {
    return WideCharToMultiByte(CP_UTF8, 0, source, -1, target, capacity, NULL, NULL);
}

static int native_option(const WCHAR *source, char *target, int capacity) {
    return WideCharToMultiByte(ZB_TEST_NATIVE_CODEPAGE, 0, source, -1, target, capacity, NULL, NULL);
}

/* Prove fixture preconditions before invoking the guard, without creating a VM. */
static DWORD module_count(const WCHAR *name) {
    MODULEENTRY32W entry;
    DWORD result = 0, error;
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, GetCurrentProcessId());
    if (snapshot == INVALID_HANDLE_VALUE) ExitProcess(108);
    entry.dwSize = sizeof(entry);
    if (!Module32FirstW(snapshot, &entry)) ExitProcess(109);
    do { if (!lstrcmpiW(entry.szModule, name)) result++; } while (Module32NextW(snapshot, &entry));
    error = GetLastError();
    CloseHandle(snapshot);
    if (error != ERROR_NO_MORE_FILES) ExitProcess(110);
    return result;
}

void mainCRTStartup(void) {
    typedef jint (JNICALL *CreateVm)(JavaVM **, void **, void *);
    union { FARPROC raw; jint (JNICALL *agent)(void*, char*, void*); } agentProc;
    int count;
    WCHAR **args = CommandLineToArgvW(GetCommandLineW(), &count);
    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    JavaVMInitArgs vmArgs;
    HMODULE library;
    CreateVm create;
    jclass probe, strings;
    jmethodID mainMethod;
    jobjectArray mainArgs;
    if (count < 5) { log_message("Host requires runtime bin, agent DLL, classpath, mode.\n"); ExitProcess(90); }
    if (lstrlenW(args[1]) + 32 >= 32768) ExitProcess(91);
    lstrcpyW(libraryPath, args[1]); lstrcatW(libraryPath, L"\\server\\jvm.dll");
    library = LoadLibraryExW(libraryPath, NULL, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!library) { log_message("Host cannot load JVM.\n"); ExitProcess(92); }
    {
        DWORD size = GetModuleFileNameW(library, loadedJvmPath, 32768);
        if (!size || size >= 32768) ExitProcess(105);
        if (lstrcmpW(args[4], L"unicode") == 0) {
            DWORD i;
            int hasUnicode = 0;
            for (i = 0; i < size; i++) if (loadedJvmPath[i] > 127) hasUnicode = 1;
            if (!hasUnicode || lstrcmpiW(loadedJvmPath, libraryPath) != 0) {
                log_message("Unicode runtime path proof failed before JVM creation.\n");
                ExitProcess(106);
            }
            log_message("PASS Unicode JVM module path: staged path retained before JVM creation\n");
        }
        if (!utf8(loadedJvmPath, agentOption, sizeof(agentOption))) ExitProcess(107);
        log_message("JVM_MODULE="); log_message(agentOption); log_message("\n");
    }
    create = (CreateVm)GetProcAddress(library, "JNI_CreateJavaVM");
    if (!create) ExitProcess(93);
    if (count > 6 && lstrcmpW(args[5], L"duplicate-jvm") == 0) {
        HMODULE duplicate = LoadLibraryExW(args[6], NULL,
            LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
        if (!duplicate || duplicate == library || module_count(L"jvm.dll") != 2
            || module_count(L"java.dll") || module_count(L"instrument.dll")) ExitProcess(111);
        log_message("PASS duplicate JVM fixture: two distinct mapped JVMs, no Java/instrument modules\n");
    }
    if (count > 5 && (!lstrcmpW(args[5], L"guard-only") || !lstrcmpW(args[5], L"duplicate-jvm"))) {
        HMODULE agent = LoadLibraryExW(args[2], NULL, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
        if (!agent) ExitProcess(103);
        agentProc.raw = GetProcAddress(agent, "Agent_OnLoad");
        if (!agentProc.raw) ExitProcess(104);
        /* Missing dependency / ambiguous JVM must fail before dereferencing NULL. */
        jint result = agentProc.agent(NULL, NULL, NULL);
        if (!lstrcmpW(args[5], L"duplicate-jvm")
            && (module_count(L"java.dll") || module_count(L"instrument.dll"))) ExitProcess(112);
        ExitProcess(result == -1 ? 1 : 0);
    }
    if (count > 6 && lstrcmpW(args[5], L"alias") == 0) {
        HMODULE aliased, requested;
        DWORD length;
        lstrcpyW(libraryPath, args[6]); lstrcatW(libraryPath, L"\\java.dll");
        aliased = LoadLibraryExW(libraryPath, NULL,
            LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
        length = GetModuleFileNameW(aliased, loadedJvmPath, 32768);
        if (!aliased || !length || length >= 32768 || lstrcmpiW(loadedJvmPath, libraryPath)) ExitProcess(113);
        lstrcpyW(libraryPath, args[1]); lstrcatW(libraryPath, L"\\java.dll");
        if (!lstrcmpiW(loadedJvmPath, libraryPath)) ExitProcess(115);
        requested = LoadLibraryExW(libraryPath, NULL,
            LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
        if (requested != aliased || module_count(L"java.dll") != 1 || module_count(L"jvm.dll") != 1) ExitProcess(114);
        log_message("PASS alias fixture: different path, same reused Java module, one JVM\n");
    }
    if (count > 5 && lstrcmpW(args[5], L"preload") == 0) {
        DWORD length = GetModuleFileNameW(NULL, libraryPath, 32768);
        while (length && libraryPath[length] != L'\\') length--;
        libraryPath[length] = 0; lstrcatW(libraryPath, L"\\java.dll");
        if (!LoadLibraryExW(libraryPath, NULL, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32)) ExitProcess(94);
    }
    lstrcpyA(agentOption, "-agentpath:");
    if (!native_option(args[2], agentOption + lstrlenA(agentOption), 65000)) ExitProcess(95);
    agentTailOffset = lstrlenA(agentOption) + 1;
    lstrcatA(agentOption, "=experimental,policy=deny-new,allow_unsigned_mods=false,frontend=console,config_dir=./config");
    if (lstrcmpW(args[4], L"options-exact") == 0 || lstrcmpW(args[4], L"options-overflow") == 0) {
        int cursor;
        int target;
        volatile char* fill = agentOption;
        lstrcatA(agentOption, ",padding=");
        target = agentTailOffset + 4096 - lstrlenA("ZombieBuddy.jar") - 2;
        if (lstrcmpW(args[4], L"options-overflow") == 0) target++;
        cursor = lstrlenA(agentOption);
        while (cursor < target) fill[cursor++] = 'a';
        agentOption[cursor] = 0;
    }
    lstrcpyA(classpathOption, "-Djava.class.path=");
    if (!native_option(args[3], classpathOption + lstrlenA(classpathOption), 65000)) ExitProcess(96);
    options[0].optionString = "-Xms32m";
    options[1].optionString = "-Xmx128m";
    options[2].optionString = "-XX:-CreateCoredumpOnCrash";
    options[3].optionString = agentOption;
    options[4].optionString = classpathOption;
    options[5].optionString = "--enable-native-access=ALL-UNNAMED";
    vmArgs.version = JNI_VERSION_1_8; vmArgs.nOptions = 6; vmArgs.options = options; vmArgs.ignoreUnrecognized = JNI_FALSE;
    if (create(&vm, (void**)&env, &vmArgs) != JNI_OK) ExitProcess(97);
    probe = (*env)->FindClass(env, "NativeGuardProbe");
    strings = (*env)->FindClass(env, "java/lang/String");
    if (!probe || !strings) { (*env)->ExceptionDescribe(env); ExitProcess(98); }
    mainMethod = (*env)->GetStaticMethodID(env, probe, "main", "([Ljava/lang/String;)V");
    mainArgs = (*env)->NewObjectArray(env, 2, strings, NULL);
    if (!mainMethod || !mainArgs) { (*env)->ExceptionDescribe(env); ExitProcess(99); }
    if (!utf8(args[1], classpathOption, 65536)) ExitProcess(100);
    (*env)->SetObjectArrayElement(env, mainArgs, 0, (*env)->NewStringUTF(env, classpathOption));
    if (!utf8(args[4], classpathOption, 65536)) ExitProcess(101);
    (*env)->SetObjectArrayElement(env, mainArgs, 1, (*env)->NewStringUTF(env, classpathOption));
    (*env)->CallStaticVoidMethod(env, probe, mainMethod, mainArgs);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); ExitProcess(102); }
    (*vm)->DestroyJavaVM(vm);
    ExitProcess(0);
}
