#ifndef KD_BENCH_TORUS_MESH_H
#define KD_BENCH_TORUS_MESH_H

#include <math.h>
#include <stdint.h>
#include <stdlib.h>

#define KD_BENCH_MAJOR 56u
#define KD_BENCH_MINOR 24u
#define KD_BENCH_VERTICES (KD_BENCH_MAJOR * KD_BENCH_MINOR)
#define KD_BENCH_TRIANGLES (KD_BENCH_MAJOR * KD_BENCH_MINOR * 2u)
#define KD_BENCH_INDICES (KD_BENCH_TRIANGLES * 3u)
#define KD_BENCH_INSTANCES 36u
#define KD_BENCH_TRIANGLES_PER_FRAME (KD_BENCH_TRIANGLES * KD_BENCH_INSTANCES)
#define KD_BENCH_PI 3.14159265358979323846

typedef struct kd_bench_vertex {
    float x, y, z;
    float nx, ny, nz;
} kd_bench_vertex;

typedef struct kd_bench_mesh {
    kd_bench_vertex *vertices;
    uint16_t *indices;
} kd_bench_mesh;

static void kd_bench_mesh_free(kd_bench_mesh *mesh) {
    if (!mesh) return;
    free(mesh->vertices);
    free(mesh->indices);
    mesh->vertices = NULL;
    mesh->indices = NULL;
}

static int kd_bench_mesh_create(kd_bench_mesh *mesh) {
    if (!mesh) return 0;
    mesh->vertices = NULL;
    mesh->indices = NULL;
    mesh->vertices = (kd_bench_vertex *)calloc(KD_BENCH_VERTICES, sizeof(*mesh->vertices));
    mesh->indices = (uint16_t *)calloc(KD_BENCH_INDICES, sizeof(*mesh->indices));
    if (!mesh->vertices || !mesh->indices) {
        kd_bench_mesh_free(mesh);
        return 0;
    }
    for (uint32_t u = 0; u < KD_BENCH_MAJOR; ++u) {
        const double angle = 2.0 * KD_BENCH_PI * (double)u / KD_BENCH_MAJOR;
        const double cu = cos(angle), su = sin(angle);
        for (uint32_t v = 0; v < KD_BENCH_MINOR; ++v) {
            const double ring = 2.0 * KD_BENCH_PI * (double)v / KD_BENCH_MINOR;
            const double cv = cos(ring), sv = sin(ring);
            const double r = 0.69 + 0.27 * cv;
            kd_bench_vertex *point = &mesh->vertices[u * KD_BENCH_MINOR + v];
            point->x = (float)(r * cu);
            point->y = (float)(r * su);
            point->z = (float)(0.27 * sv);
            point->nx = (float)(cu * cv);
            point->ny = (float)(su * cv);
            point->nz = (float)sv;
            const uint16_t a = (uint16_t)(u * KD_BENCH_MINOR + v);
            const uint16_t b = (uint16_t)(((u + 1) % KD_BENCH_MAJOR) * KD_BENCH_MINOR + v);
            const uint16_t c = (uint16_t)(((u + 1) % KD_BENCH_MAJOR) * KD_BENCH_MINOR + (v + 1) % KD_BENCH_MINOR);
            const uint16_t d = (uint16_t)(u * KD_BENCH_MINOR + (v + 1) % KD_BENCH_MINOR);
            const uint32_t offset = (u * KD_BENCH_MINOR + v) * 6u;
            mesh->indices[offset + 0] = a;
            mesh->indices[offset + 1] = b;
            mesh->indices[offset + 2] = c;
            mesh->indices[offset + 3] = a;
            mesh->indices[offset + 4] = c;
            mesh->indices[offset + 5] = d;
        }
    }
    return 1;
}

#endif
