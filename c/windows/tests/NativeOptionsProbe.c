/* Exercises the real loader buffer builder; no JVM or game is started. */
#include "../zbNative.c"

static char tail[AGENT_OPTIONS_MAX + 1];
static struct { char before; char value[AGENT_OPTIONS_MAX]; char after; } output;
static int callbacks;

static int record_callback(void* jvm, char* value, void* reserved) {
    if (jvm != &output || reserved != tail || lstrlenA(value) != AGENT_OPTIONS_MAX - 1) ExitProcess(6);
    callbacks++;
    return 23;
}

void mainCRTStartup(void) {
    int i;
    volatile char* fill = tail;
    int exact = AGENT_OPTIONS_MAX - lstrlenA(ZB_JAR) - 2;
    output.before = 'L'; output.after = 'R';
    for (i = 0; i < exact; i++) fill[i] = 'a';
    tail[exact] = 0;
    if (!build_agent_options(tail, output.value, sizeof(output.value))) ExitProcess(1);
    if (lstrlenA(output.value) != AGENT_OPTIONS_MAX - 1 || output.value[AGENT_OPTIONS_MAX - 1] != 0) ExitProcess(2);
    if (invoke_agent(record_callback, &output, tail, tail) != 23 || callbacks != 1) ExitProcess(7);
    tail[exact] = 'a'; tail[exact + 1] = 0;
    if (build_agent_options(tail, output.value, sizeof(output.value))) ExitProcess(3);
    if (invoke_agent(record_callback, &output, tail, tail) != -1 || callbacks != 1) ExitProcess(8);
    if (output.before != 'L' || output.after != 'R') ExitProcess(4);
    if (!build_agent_options(NULL, output.value, sizeof(output.value)) || lstrcmpA(output.value, ZB_JAR)) ExitProcess(5);
    guard_write("PASS native option buffer: exact capacity, overflow refusal, NUL, guard bytes and heap callback forwarding\n");
    ExitProcess(0);
}
