#include "kotlin_display.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>

static FILE *telemetry_file;
static unsigned long long frame_number;
static uint64_t stop_after_ns;
static char engine_name[32];

kd_status kd_telemetry_open(const char *engine) {
    if (!engine || !*engine || strlen(engine) >= sizeof(engine_name)) return KD_INVALID_ARGUMENT;
    for (const char *character = engine; *character; ++character)
        if (!(*character >= 'a' && *character <= 'z') && !(*character >= '0' && *character <= '9'))
            return KD_INVALID_ARGUMENT;
    kd_telemetry_close();
    stop_after_ns = 0;
    const char *limit = getenv("KD_FRAME_LOG_MAX_SECONDS");
    if (limit && *limit) {
        char *end = NULL;
        errno = 0;
        double seconds = strtod(limit, &end);
        if (errno || end == limit || *end || !(seconds > 0.0) || seconds > 3600.0)
            return KD_INVALID_ARGUMENT;
        stop_after_ns = (uint64_t)(seconds * 1000000000.0);
    }
    const char *path = getenv("KD_FRAME_LOG_PATH");
    if (!path || !*path) return KD_OK;
    telemetry_file = fopen(path, "w");
    if (!telemetry_file) return KD_WINDOW_UNAVAILABLE;
    strcpy(engine_name, engine);
    if (fprintf(telemetry_file,
                "Engine,Frame,ElapsedNs,IntervalNs,SceneNs,RenderNs,Commands,Width,Height\n") < 0) {
        kd_telemetry_close();
        return KD_WINDOW_UNAVAILABLE;
    }
    frame_number = 0;
    fflush(telemetry_file);
    return KD_OK;
}

kd_status kd_telemetry_record(uint64_t elapsed_ns, uint64_t interval_ns,
                              uint64_t scene_ns, uint64_t render_ns,
                              uint32_t command_count, uint32_t width, uint32_t height) {
    if (!width || !height || !command_count || command_count > KD_DRAW_MAX_COMMANDS)
        return KD_INVALID_ARGUMENT;
    if (!telemetry_file) return KD_OK;
    if (stop_after_ns && elapsed_ns > stop_after_ns) {
        kd_telemetry_close();
        return KD_OK;
    }
    ++frame_number;
    if (fprintf(telemetry_file, "%s,%llu,%llu,%llu,%llu,%llu,%u,%u,%u\n",
                engine_name, frame_number, (unsigned long long)elapsed_ns,
                (unsigned long long)interval_ns, (unsigned long long)scene_ns,
                (unsigned long long)render_ns, command_count, width, height) < 0)
        return KD_WINDOW_UNAVAILABLE;
    if ((frame_number & 31u) == 0u && fflush(telemetry_file) != 0)
        return KD_WINDOW_UNAVAILABLE;
    return KD_OK;
}

void kd_telemetry_close(void) {
    if (telemetry_file) {
        fflush(telemetry_file);
        fclose(telemetry_file);
        telemetry_file = NULL;
    }
}
