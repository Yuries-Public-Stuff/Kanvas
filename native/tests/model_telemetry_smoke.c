#include "../examples/model_benchmark_telemetry.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    const char *path = "model-telemetry-smoke.csv";
#if defined(_WIN32)
    assert(_putenv_s("KD_MODEL_LOG_PATH", path) == 0);
    assert(_putenv_s("KD_MODEL_LOG_MAX_SECONDS", "0.0000003") == 0);
#else
    assert(setenv("KD_MODEL_LOG_PATH", path, 1) == 0);
    assert(setenv("KD_MODEL_LOG_MAX_SECONDS", "0.0000003", 1) == 0);
#endif
    kd_model_log log = {0};
    assert(kd_model_log_open(&log, "gdi"));
    assert(kd_model_log_record(&log, 100, 50, 96768, 36, 960, 540));
    assert(!kd_model_log_record(&log, 100, 50, 96768, 36, 960, 540));
    assert(kd_model_log_record(&log, 200, 60, 96768, 36, 960, 540));
    assert(kd_model_log_record(&log, 400, 40, 96768, 36, 960, 540));
    assert(log.file == NULL);
    FILE *file = fopen(path, "r");
    assert(file);
    char line[256];
    assert(fgets(line, sizeof(line), file));
    assert(strcmp(line, "Renderer,Workload,Frame,ElapsedNs,IntervalNs,CpuCallNs,Triangles,Instances,Width,Height\n") == 0);
    assert(fgets(line, sizeof(line), file));
    assert(strcmp(line, "gdi,torus-56x24-36,1,100,0,50,96768,36,960,540\n") == 0);
    assert(fgets(line, sizeof(line), file));
    assert(strcmp(line, "gdi,torus-56x24-36,2,200,100,60,96768,36,960,540\n") == 0);
    assert(fgets(line, sizeof(line), file) == NULL);
    fclose(file);
    assert(remove(path) == 0);
    puts("Model telemetry schema, monotonicity and bounded lifetime passed.");
    return 0;
}
