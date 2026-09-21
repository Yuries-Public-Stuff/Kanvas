#include "kotlin_display.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void set_limit(const char *value) {
#if defined(_WIN32)
    assert(_putenv_s("KD_FRAME_LOG_MAX_SECONDS", value) == 0);
#else
    assert(setenv("KD_FRAME_LOG_MAX_SECONDS", value, 1) == 0);
#endif
}

int main(void) {
    const char *path = "frame-telemetry-smoke.csv";
#if defined(_WIN32)
    assert(_putenv_s("KD_FRAME_LOG_PATH", path) == 0);
#else
    assert(setenv("KD_FRAME_LOG_PATH", path, 1) == 0);
#endif
    set_limit("bad");
    assert(kd_telemetry_open("vulkan") == KD_INVALID_ARGUMENT);
    set_limit("0.0025");
    assert(kd_telemetry_open("bad,name") == KD_INVALID_ARGUMENT);
    assert(kd_telemetry_open("vulkan") == KD_OK);
    assert(kd_telemetry_record(1000000, 0, 300000, 500000, 12, 960, 540) == KD_OK);
    assert(kd_telemetry_record(2000000, 1000000, 250000, 400000, 13, 960, 540) == KD_OK);
    assert(kd_telemetry_record(3000000, 1000000, 250000, 400000, 14, 960, 540) == KD_OK);
    assert(kd_telemetry_record(4000000, 1000000, 250000, 400000, 15, 960, 540) == KD_OK);
    assert(kd_telemetry_record(5000000, 1000000, 250000, 400000, 0, 960, 540) == KD_INVALID_ARGUMENT);
    kd_telemetry_close();
    FILE *file = fopen(path, "r");
    assert(file != NULL);
    char line[256];
    assert(fgets(line, sizeof(line), file) != NULL);
    assert(strcmp(line, "Engine,Frame,ElapsedNs,IntervalNs,SceneNs,RenderNs,Commands,Width,Height\n") == 0);
    assert(fgets(line, sizeof(line), file) != NULL);
    assert(strcmp(line, "vulkan,1,1000000,0,300000,500000,12,960,540\n") == 0);
    assert(fgets(line, sizeof(line), file) != NULL);
    assert(strcmp(line, "vulkan,2,2000000,1000000,250000,400000,13,960,540\n") == 0);
    assert(fgets(line, sizeof(line), file) == NULL);
    fclose(file);
    assert(remove(path) == 0);
    set_limit("");
    puts("Frame telemetry boundaries and CSV validation passed.");
    return 0;
}
