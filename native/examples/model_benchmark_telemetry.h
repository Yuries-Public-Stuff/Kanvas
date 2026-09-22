#ifndef KD_MODEL_BENCH_TELEMETRY_H
#define KD_MODEL_BENCH_TELEMETRY_H

#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <math.h>

typedef struct kd_model_log {
    FILE *file;
    const char *renderer;
    unsigned long long frame;
    uint64_t previous_ns;
    uint64_t limit_ns;
} kd_model_log;

static void kd_model_log_close(kd_model_log *log) {
    if (!log || !log->file) return;
    fflush(log->file);
    fclose(log->file);
    log->file = NULL;
}

static int kd_model_log_open(kd_model_log *log, const char *renderer) {
    if (!log || !renderer) return 0;
    log->file = NULL;
    log->renderer = renderer;
    log->frame = 0;
    log->previous_ns = 0;
    log->limit_ns = 0;
    const char *limit = getenv("KD_MODEL_LOG_MAX_SECONDS");
    if (limit && *limit) {
        char *end;
        const double seconds = strtod(limit, &end);
        if (end == limit || *end || !isfinite(seconds) || seconds <= 0. || seconds > 86400.) return 0;
        log->limit_ns = (uint64_t)(seconds * 1000000000.0);
    }
    const char *path = getenv("KD_MODEL_LOG_PATH");
    if (!path || !*path) return 1;
    log->file = fopen(path, "w");
    if (!log->file) return 0;
    if (fprintf(log->file, "Renderer,Workload,Frame,ElapsedNs,IntervalNs,CpuCallNs,Triangles,Instances,Width,Height\n") < 0 || fflush(log->file) != 0) {
        kd_model_log_close(log);
        return 0;
    }
    return 1;
}

static int kd_model_log_record(kd_model_log *log, uint64_t elapsed_ns, uint64_t cpu_ns,
                               uint32_t triangles, uint32_t instances, uint32_t width, uint32_t height) {
    if (!log || !width || !height || !triangles || !instances) return 0;
    if (!log->file) return 1;
    if (log->limit_ns && elapsed_ns > log->limit_ns) {
        kd_model_log_close(log);
        return 1;
    }
    if (log->frame && elapsed_ns <= log->previous_ns) return 0;
    const uint64_t interval = log->frame == 0 ? 0 : elapsed_ns - log->previous_ns;
    ++log->frame;
    log->previous_ns = elapsed_ns;
    if (fprintf(log->file, "%s,torus-56x24-36,%llu,%llu,%llu,%llu,%u,%u,%u,%u\n",
                log->renderer, log->frame, (unsigned long long)elapsed_ns,
                (unsigned long long)interval, (unsigned long long)cpu_ns,
                triangles, instances, width, height) < 0) return 0;
    if ((log->frame & 63ull) == 0ull && fflush(log->file) != 0) return 0;
    return 1;
}

#endif
