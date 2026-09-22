#include "../examples/torus_mesh.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>

int main(void) {
    kd_bench_mesh mesh = {0};
    assert(kd_bench_mesh_create(&mesh));
    assert(mesh.vertices != NULL && mesh.indices != NULL);
    assert(KD_BENCH_TRIANGLES_PER_FRAME == 96768u);
    for (unsigned i = 0; i < KD_BENCH_VERTICES; ++i) {
        const kd_bench_vertex p = mesh.vertices[i];
        const float normal = p.nx * p.nx + p.ny * p.ny + p.nz * p.nz;
        assert(fabsf(normal - 1.f) < 0.0001f);
    }
    for (unsigned i = 0; i < KD_BENCH_INDICES; ++i) {
        assert(mesh.indices[i] < KD_BENCH_VERTICES);
    }
    kd_bench_mesh_free(&mesh);
    assert(mesh.vertices == NULL && mesh.indices == NULL);
    puts("Shared torus mesh topology and normals passed.");
    return 0;
}
