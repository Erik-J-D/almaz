/* inpaint.c — the Repair brush: fill a hole from its surroundings.
 *
 * Texture: PatchMatch (Barnes et al. 2009) in Wexler et al.'s (2007)
 * coarse-to-fine EM. At each pyramid level, every patch overlapping the hole
 * finds its nearest patch lying wholly outside it (random init or the coarser
 * level's field, then propagation and random search), and each hole pixel
 * becomes the similarity-weighted vote of the patches covering it.
 *
 * Each patch's cost grows with how many targets already use it (Kaspar et
 * al. 2015), so fine texture isn't filled by copying one patch over and over.
 *
 * Lighting: darktable's healing solve (common/heal.c, after T. Georgiev,
 * "Photoshop Healing Brush: a Tool for Seamless Cloning"): red-black
 * Gauss-Seidel with over-relaxation over the hole, held at the surrounding
 * pixels, gives the smooth surface meeting its edge. The fill is that
 * surface plus the fine detail of each pixel's best match (see finish).
 *
 * Images are planar RGB floats (0..1). Deterministic for a seed.
 * License: GPL-2.0-or-later. */
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "almazimg.h"

#define P 7          /* patch side */
#define R (P / 2)    /* patch radius */
#define MAXLEV 12
/* channels matched and rebuilt: RGB, then texture features (Newson et al.
 * 2014, "Video Inpainting of Complex Scenes"): the local mean of |dI/dx| and
 * |dI/dy|, at full size carried down the pyramid (channels 3, 4) and from
 * each level's own pixels (5, 6). Without them a hole's blurry first
 * estimate matches the smoothest patches best, and a textured hole fills
 * with flat colour. Both scales matter: fine grain beside flat colour
 * differs only at full size; real texture beside a noisy blurred background
 * only once the noise averages away. */
#define NCH 7
#define FEAT_W 4.0f   /* the features' weight in patch distances */
#define FEAT_R 2      /* their averaging radius */

#define MIN(a, b) ((a) < (b) ? (a) : (b))
#define MAX(a, b) ((a) > (b) ? (a) : (b))

typedef struct {
  int w, h;
  float *img;      /* planar, NCH channels: the current estimate */
  uint8_t *hole;
  int *ox, *oy;    /* nearest-neighbour field: source centre = target + offset */
  float *d;        /* its patch distance */
  int *occ;        /* how many targets each source centre serves */
  float lambda;    /* the cost of each of those (Kaspar et al.'s occurrence penalty) */
} level_t;

static inline uint32_t rnd(uint32_t *s) {
  uint32_t x = *s;
  x ^= x << 13;
  x ^= x >> 17;
  x ^= x << 5;
  return *s = x;
}

/* The texture features of the known pixels: the mean |dI/dx| and |dI/dy|
 * of the luma over a (2 FEAT_R + 1)^2 window, counting only steps between
 * known pixels, weighted by FEAT_W, into channels c and c + 1. */
static void features(level_t *l, int c) {
  const int w = l->w, h = l->h;
  const size_t n = (size_t)w * h;
  float *gx = (float *)calloc(n, sizeof(float)), *gy = (float *)calloc(n, sizeof(float));
  uint8_t *kx = (uint8_t *)calloc(n, 1), *ky = (uint8_t *)calloc(n, 1);
  if (!gx || !gy || !kx || !ky) goto done;
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++) {
      const size_t i = (size_t)y * w + x;
      const float L = (l->img[i] + l->img[n + i] + l->img[2 * n + i]) / 3;
      if (x + 1 < w && !l->hole[i] && !l->hole[i + 1]) {
        gx[i] = fabsf((l->img[i + 1] + l->img[n + i + 1] + l->img[2 * n + i + 1]) / 3 - L);
        kx[i] = 1;
      }
      if (y + 1 < h && !l->hole[i] && !l->hole[i + w]) {
        gy[i] = fabsf((l->img[i + w] + l->img[n + i + w] + l->img[2 * n + i + w]) / 3 - L);
        ky[i] = 1;
      }
    }
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++) {
      const size_t i = (size_t)y * w + x;
      if (l->hole[i]) continue;
      float sx = 0, sy = 0;
      int cx = 0, cy = 0;
      for (int dy = -FEAT_R; dy <= FEAT_R; dy++)
        for (int dx = -FEAT_R; dx <= FEAT_R; dx++) {
          const int px = x + dx, py = y + dy;
          if (px < 0 || py < 0 || px >= w || py >= h) continue;
          const size_t j = (size_t)py * w + px;
          if (kx[j]) sx += gx[j], cx++;
          if (ky[j]) sy += gy[j], cy++;
        }
      l->img[c * n + i] = FEAT_W * (cx ? sx / cx : 0);
      l->img[(c + 1) * n + i] = FEAT_W * (cy ? sy / cy : 0);
    }
done:
  free(gx), free(gy), free(kx), free(ky);
}

/* ---- threads ---- */
/* Work is split into a fixed number of bands of rows, one thread each. */
#define BANDS 16
typedef void (*band_fn)(void *ctx, int band);
typedef struct { band_fn fn; void *ctx; int band; } band_job;
static void *band_run(void *p) {
  band_job *j = (band_job *)p;
  j->fn(j->ctx, j->band);
  return NULL;
}
static void par_bands(band_fn fn, void *ctx) {
  pthread_t th[BANDS];
  band_job jobs[BANDS];
  int started[BANDS] = {0};
  for (int i = 0; i < BANDS; i++) {
    jobs[i] = (band_job){fn, ctx, i};
    started[i] = i > 0 && pthread_create(&th[i], NULL, band_run, &jobs[i]) == 0;
    if (i > 0 && !started[i]) band_run(&jobs[i]);
  }
  band_run(&jobs[0]);
  for (int i = 1; i < BANDS; i++)
    if (started[i]) pthread_join(th[i], NULL);
}
static inline int band_y0(int h, int b) { return (int)((long)h * b / BANDS); }

static void level_free(level_t *l) {
  free(l->img);
  free(l->hole);
  free(l->ox);
  free(l->oy);
  free(l->d);
  free(l->occ);
}

/* Half size: known pixels average their known children; a pixel is a hole
 * only when all its children are. */
static int downsample(const level_t *a, level_t *b) {
  b->w = (a->w + 1) / 2;
  b->h = (a->h + 1) / 2;
  const size_t n = (size_t)b->w * b->h, an = (size_t)a->w * a->h;
  b->img = (float *)calloc(NCH * n, sizeof(float));
  b->hole = (uint8_t *)calloc(n, 1);
  if (!b->img || !b->hole) return 0;
  for (int y = 0; y < b->h; y++)
    for (int x = 0; x < b->w; x++) {
      float s[NCH] = {0};
      int k = 0, c = 0;
      for (int dy = 0; dy < 2; dy++)
        for (int dx = 0; dx < 2; dx++) {
          const int sx = MIN(2 * x + dx, a->w - 1), sy = MIN(2 * y + dy, a->h - 1);
          const size_t i = (size_t)sy * a->w + sx;
          c++;
          if (a->hole[i]) continue;
          for (int ch = 0; ch < NCH; ch++) s[ch] += a->img[ch * an + i];
          k++;
        }
      const size_t o = (size_t)y * b->w + x;
      if (k == 0) {
        b->hole[o] = 1;
      } else {
        for (int ch = 0; ch < NCH; ch++) b->img[ch * n + o] = s[ch] / k;
      }
      (void)c;
    }
  return 1;
}

/* The coarsest guess: peel the hole from its edge inwards, each pixel the
 * mean of its already-known 8-neighbours. */
static void onion_fill(level_t *l) {
  const int w = l->w, h = l->h;
  const size_t n = (size_t)w * h;
  uint8_t *known = (uint8_t *)malloc(n), *next = (uint8_t *)malloc(n);
  if (!known || !next) goto done;
  for (size_t i = 0; i < n; i++) known[i] = !l->hole[i];
  for (int changed = 1; changed;) {
    changed = 0;
    memcpy(next, known, n);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        const size_t i = (size_t)y * w + x;
        if (known[i]) continue;
        float s[NCH] = {0};
        int k = 0;
        for (int dy = -1; dy <= 1; dy++)
          for (int dx = -1; dx <= 1; dx++) {
            const int nx = x + dx, ny = y + dy;
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
            const size_t j = (size_t)ny * w + nx;
            if (!known[j]) continue;
            for (int ch = 0; ch < NCH; ch++) s[ch] += l->img[ch * n + j];
            k++;
          }
        if (k) {
          for (int ch = 0; ch < NCH; ch++) l->img[ch * n + i] = s[ch] / k;
          next[i] = 1;
          changed = 1;
        }
      }
    memcpy(known, next, n);
  }
done:
  free(known);
  free(next);
}

/* valid[c]: a patch centred at c lies inside the image and outside the hole
 * (via a summed-area table of the hole). */
static uint8_t *valid_sources(const level_t *l, int *count) {
  const int w = l->w, h = l->h;
  uint32_t *sat = (uint32_t *)calloc((size_t)(w + 1) * (h + 1), sizeof(uint32_t));
  uint8_t *v = (uint8_t *)calloc((size_t)w * h, 1);
  *count = 0;
  if (!sat || !v) {
    free(sat);
    free(v);
    return NULL;
  }
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++)
      sat[(size_t)(y + 1) * (w + 1) + x + 1] = l->hole[(size_t)y * w + x] + sat[(size_t)y * (w + 1) + x + 1] +
                                               sat[(size_t)(y + 1) * (w + 1) + x] - sat[(size_t)y * (w + 1) + x];
  for (int y = R; y < h - R; y++)
    for (int x = R; x < w - R; x++) {
      const int x0 = x - R, y0 = y - R, x1 = x + R + 1, y1 = y + R + 1;
      const uint32_t s = sat[(size_t)y1 * (w + 1) + x1] - sat[(size_t)y0 * (w + 1) + x1] -
                         sat[(size_t)y1 * (w + 1) + x0] + sat[(size_t)y0 * (w + 1) + x0];
      if (s == 0) {
        v[(size_t)y * w + x] = 1;
        (*count)++;
      }
    }
  free(sat);
  return v;
}

/* SSD between the patches at target (tx, ty) and source (sx, sy), over the
 * part of the target patch inside the image, per pixel; stops past `best`. */
static float patch_dist(const level_t *l, int tx, int ty, int sx, int sy, float best) {
  const int w = l->w, h = l->h;
  const size_t n = (size_t)w * h;
  float s = 0;
  int k = 0;
  for (int dy = -R; dy <= R; dy++) {
    const int ty2 = ty + dy;
    if (ty2 < 0 || ty2 >= h) continue;
    for (int dx = -R; dx <= R; dx++) {
      const int tx2 = tx + dx;
      if (tx2 < 0 || tx2 >= w) continue;
      const size_t a = (size_t)ty2 * w + tx2, b = (size_t)(sy + dy) * w + sx + dx;
      for (int ch = 0; ch < NCH; ch++) {
        const float e = l->img[ch * n + a] - l->img[ch * n + b];
        s += e * e;
      }
      k++;
    }
    if (best >= 0 && s > best * P * P) return s / MAX(k, 1);
  }
  return s / MAX(k, 1);
}

static void random_source(const int *srcs, int nsrc, uint32_t *seed, int tx, int ty, int w, int *ox, int *oy) {
  const int c = srcs[rnd(seed) % (uint32_t)nsrc];
  *ox = c % w - tx;
  *oy = c / w - ty;
}

/* Is the source centre (x, y) a valid patch centre? */
static inline int ok_src(const uint8_t *valid, int w, int h, int x, int y) {
  return x >= 0 && y >= 0 && x < w && y < h && valid[(size_t)y * w + x];
}

/* Take source (tx + ox, ty + oy) for target t if it's closer, counting what
 * each source already serves: on fine texture a blurry estimate matches the
 * least-textured patch best, and without the penalty one patch gets copied
 * all over the hole (Kaspar et al., "Self Tuning Texture Optimization").
 * The counts change as the bands run (atomically): held fixed for a pass,
 * every band found the same cheap patch at once and the fill went flat.
 * So the fill depends a little on thread timing: two runs differ in detail,
 * not in quality. */
static void try_src(level_t *l, const uint8_t *valid, size_t t, int tx, int ty, int ox, int oy) {
  const int sx = tx + ox, sy = ty + oy;
  if (!ok_src(valid, l->w, l->h, sx, sy)) return;
  if (ox == l->ox[t] && oy == l->oy[t]) return;
  const size_t s = (size_t)sy * l->w + sx, cur = (size_t)(ty + l->oy[t]) * l->w + tx + l->ox[t];
  const float cur_cost = l->d[t] + l->lambda * (l->occ[cur] - 1);
  const float d = patch_dist(l, tx, ty, sx, sy, cur_cost);
  if (d + l->lambda * l->occ[s] < cur_cost) {
    __atomic_sub_fetch(&l->occ[cur], 1, __ATOMIC_RELAXED);
    __atomic_add_fetch(&l->occ[s], 1, __ATOMIC_RELAXED);
    l->d[t] = d;
    l->ox[t] = ox;
    l->oy[t] = oy;
  }
}

static void count_occ(level_t *l, const uint8_t *target) {
  const size_t n = (size_t)l->w * l->h;
  memset(l->occ, 0, n * sizeof(int));
  for (size_t t = 0; t < n; t++)
    if (target[t]) l->occ[(size_t)((int)(t / l->w) + l->oy[t]) * l->w + (int)(t % l->w) + l->ox[t]]++;
}

/* PatchMatch over the targets (patches overlapping the hole), a band of
 * rows per thread. Propagation from a row in another band reads that band's
 * field as it was when the pass began. */
typedef struct {
  level_t *l;
  const uint8_t *valid, *target;
  const int *sox, *soy; /* the field at the pass's start */
  int fwd;
  uint32_t seeds[BANDS];
} pm_ctx;

static void pm_band(void *p, int band) {
  pm_ctx *c = (pm_ctx *)p;
  level_t *l = c->l;
  const int w = l->w, h = l->h, y0 = band_y0(h, band), y1 = band_y0(h, band + 1);
  const int fwd = c->fwd, step = fwd ? 1 : -1;
  uint32_t seed = c->seeds[band];
  for (int yy = y0; yy < y1; yy++) {
    const int y = fwd ? yy : y1 - 1 - (yy - y0);
    for (int xx = 0; xx < w; xx++) {
      const int x = fwd ? xx : w - 1 - xx;
      const size_t t = (size_t)y * w + x;
      if (!c->target[t]) continue;
      /* propagation: the neighbours' offsets, from the side already done */
      const int px = x - step, py = y - step;
      if (px >= 0 && px < w && c->target[(size_t)y * w + px])
        try_src(l, c->valid, t, x, y, l->ox[(size_t)y * w + px], l->oy[(size_t)y * w + px]);
      if (py >= 0 && py < h && c->target[(size_t)py * w + x]) {
        const size_t q = (size_t)py * w + x;
        const int mine = py >= y0 && py < y1;
        try_src(l, c->valid, t, x, y, mine ? l->ox[q] : c->sox[q], mine ? l->oy[q] : c->soy[q]);
      }
      /* random search around the best, halving the radius */
      for (int rad = MAX(w, h); rad >= 1; rad /= 2) {
        const int ox = l->ox[t] + (int)(rnd(&seed) % (uint32_t)(2 * rad + 1)) - rad;
        const int oy = l->oy[t] + (int)(rnd(&seed) % (uint32_t)(2 * rad + 1)) - rad;
        try_src(l, c->valid, t, x, y, ox, oy);
      }
    }
  }
}

static void patchmatch(level_t *l, const uint8_t *valid, const uint8_t *target, int iters, uint32_t *seed) {
  const size_t n = (size_t)l->w * l->h;
  int *sox = (int *)malloc(n * sizeof(int)), *soy = (int *)malloc(n * sizeof(int));
  if (!sox || !soy) {
    free(sox), free(soy);
    return;
  }
  for (int it = 0; it < iters; it++) {
    memcpy(sox, l->ox, n * sizeof(int));
    memcpy(soy, l->oy, n * sizeof(int));
    count_occ(l, target);
    pm_ctx c = {l, valid, target, sox, soy, !(it & 1), {0}};
    for (int b = 0; b < BANDS; b++) c.seeds[b] = rnd(seed) | 1u;
    par_bands(pm_band, &c);
  }
  free(sox), free(soy);
}

/* Each hole pixel: the vote of the patches covering it, weighted by how well
 * they matched (Wexler: exp(-d / 2 sigma^2), sigma from the matches). Each
 * pixel gathers from the patches over it (a band of rows per thread); the
 * sources lie outside the hole, so nothing read is being written. */
typedef struct {
  level_t *l;
  const uint8_t *target;
  float s2;
  float *res; /* NCH per pixel */
} vote_ctx;

static void vote_band(void *p, int band) {
  vote_ctx *c = (vote_ctx *)p;
  level_t *l = c->l;
  const int w = l->w, h = l->h;
  const size_t n = (size_t)w * h;
  for (int y = band_y0(h, band); y < band_y0(h, band + 1); y++)
    for (int x = 0; x < w; x++) {
      const size_t p0 = (size_t)y * w + x;
      if (!l->hole[p0]) continue;
      float acc[NCH] = {0}, ws = 0;
      for (int dy = -R; dy <= R; dy++)
        for (int dx = -R; dx <= R; dx++) {
          /* the patch centred at q = p - (dx, dy) covers p at (dx, dy) */
          const int qx = x - dx, qy = y - dy;
          if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue;
          const size_t q = (size_t)qy * w + qx;
          if (!c->target[q]) continue;
          const float wt = expf(-l->d[q] / (2.0f * c->s2));
          const size_t s = (size_t)(qy + l->oy[q] + dy) * w + qx + l->ox[q] + dx;
          for (int ch = 0; ch < NCH; ch++) acc[ch] += wt * l->img[ch * n + s];
          ws += wt;
        }
      if (ws > 0)
        for (int ch = 0; ch < NCH; ch++) c->res[NCH * p0 + ch] = acc[ch] / ws;
      else
        for (int ch = 0; ch < NCH; ch++) c->res[NCH * p0 + ch] = l->img[ch * n + p0];
    }
}

static void vote(level_t *l, const uint8_t *target) {
  const size_t n = (size_t)l->w * l->h;
  double dsum = 0;
  size_t dn = 0;
  for (size_t i = 0; i < n; i++)
    if (target[i]) dsum += l->d[i], dn++;
  vote_ctx c = {l, target, (float)(dn ? dsum / dn : 1.0) + 1e-6f, (float *)malloc(NCH * n * sizeof(float))};
  if (!c.res) return;
  par_bands(vote_band, &c);
  for (size_t p = 0; p < n; p++)
    if (l->hole[p])
      for (int ch = 0; ch < NCH; ch++) l->img[ch * n + p] = c.res[NCH * p + ch];
  free(c.res);
}

/* The distances of the targets' matches to the current estimate. */
typedef struct { level_t *l; const uint8_t *target; } dist_ctx;
static void dist_band(void *p, int band) {
  dist_ctx *c = (dist_ctx *)p;
  level_t *l = c->l;
  const int w = l->w;
  for (int y = band_y0(l->h, band); y < band_y0(l->h, band + 1); y++)
    for (int x = 0; x < w; x++) {
      const size_t t = (size_t)y * w + x;
      if (c->target[t]) l->d[t] = patch_dist(l, x, y, x + l->ox[t], y + l->oy[t], -1);
    }
}

/* The targets: patch centres within R of a hole pixel. */
static uint8_t *targets(const level_t *l) {
  const int w = l->w, h = l->h;
  uint8_t *t = (uint8_t *)calloc((size_t)w * h, 1);
  if (!t) return NULL;
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++)
      if (l->hole[(size_t)y * w + x])
        for (int dy = -R; dy <= R; dy++)
          for (int dx = -R; dx <= R; dx++) {
            const int px = x + dx, py = y + dy;
            if (px >= 0 && py >= 0 && px < w && py < h) t[(size_t)py * w + px] = 1;
          }
  return t;
}

/* darktable's healing solve (dt_heal: red-black Gauss-Seidel with its
 * over-relaxation factor and exit test), here with a blank source: the
 * membrane m inside the hole with sum over 4-neighbours q of
 * (m(p) - m(q)) = 0 and m held at the image outside it, the smoothest
 * surface meeting the hole's edge, which continues gradients and lighting
 * across it. `m` comes in with a first guess in the hole. */
static void membrane(const level_t *l, float *m, int max_iter) {
  const int w = l->w, h = l->h;
  const size_t n = (size_t)w * h;
  size_t nmask = 0;
  for (size_t i = 0; i < n; i++) nmask += l->hole[i];
  if (!nmask) return;
  const float wr = (2.0f - 1.0f / (0.1575f * sqrtf((float)nmask) + 0.8f)) * 0.25f;
  const float eps = 0.1f / 255.0f, err_exit = eps * eps * wr * wr;
  static const int nb[4][2] = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
  for (int iter = 0; iter < max_iter; iter++) {
    float err = 0;
    for (int parity = 0; parity < 2; parity++)
      for (int y = 0; y < h; y++)
        for (int x = (y + parity) & 1; x < w; x += 2) {
          const size_t p = (size_t)y * w + x;
          if (!l->hole[p]) continue;
          for (int ch = 0; ch < 3; ch++) {
            float *mc = m + ch * n;
            float a = 0, sum = 0;
            for (int k = 0; k < 4; k++) {
              const int qx = x + nb[k][0], qy = y + nb[k][1];
              if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue;
              sum += mc[(size_t)qy * w + qx];
              a += 1;
            }
            const float diff = wr * (a * mc[p] - sum);
            mc[p] -= diff;
            err += diff * diff;
          }
        }
    if (err < err_exit) break;
  }
}

/* A box mean of radius r along rows (dx 1) or columns, edges clamped: a
 * running sum, so its cost doesn't grow with r. */
static void box_pass(const float *src, float *dst, int w, int h, int r, int horizontal) {
  const int len = horizontal ? w : h, lines = horizontal ? h : w;
  const size_t step = horizontal ? 1 : (size_t)w, stride = horizontal ? (size_t)w : 1;
  const float inv = 1.0f / (2 * r + 1);
  for (int line = 0; line < lines; line++) {
    const float *s = src + line * stride;
    float *d = dst + line * stride;
    float acc = 0;
    for (int i = -r; i <= r; i++) acc += s[(size_t)MIN(MAX(i, 0), len - 1) * step];
    for (int i = 0; i < len; i++) {
      d[(size_t)i * step] = acc * inv;
      acc += s[(size_t)MIN(i + r + 1, len - 1) * step] - s[(size_t)MAX(i - r, 0) * step];
    }
  }
}

/* A gaussian blur (sigma) of one plane, approximated by three box blurs in
 * each direction (their widths chosen to match its variance). */
static void blur(const float *src, float *dst, float *tmp, int w, int h, float sigma) {
  const int r = MAX(1, (int)lroundf((sqrtf(12.0f * sigma * sigma / 3.0f + 1.0f) - 1.0f) / 2.0f));
  memcpy(dst, src, sizeof(float) * (size_t)w * h);
  for (int k = 0; k < 3; k++) {
    box_pass(dst, tmp, w, h, r, 1);
    box_pass(tmp, dst, w, h, r, 0);
  }
}

/* The membrane over level 0's hole, solved coarse to fine on the pyramid:
 * each level starts from the coarser solution, so a few sweeps finish it
 * (from scratch, a large hole needs thousands). Into lv[0].img's RGB. */
static void membrane_pyramid(level_t *lv, int nl) {
  float *prev = NULL;
  int pw = 0, ph = 0;
  for (int L = nl - 1; L >= 0; L--) {
    level_t *l = &lv[L];
    const int w = l->w, h = l->h;
    const size_t n = (size_t)w * h;
    float *m = (float *)malloc(3 * n * sizeof(float));
    if (!m) break;
    memcpy(m, l->img, 3 * n * sizeof(float));
    if (prev) {
      const size_t pn = (size_t)pw * ph;
      for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
          const size_t i = (size_t)y * w + x;
          if (!l->hole[i]) continue;
          const float fx = fminf(fmaxf((x + 0.5f) / 2 - 0.5f, 0), pw - 1), fy = fminf(fmaxf((y + 0.5f) / 2 - 0.5f, 0), ph - 1);
          const int x0 = (int)fx, y0 = (int)fy, x1 = MIN(x0 + 1, pw - 1), y1 = MIN(y0 + 1, ph - 1);
          const float ax = fx - x0, ay = fy - y0;
          for (int ch = 0; ch < 3; ch++) {
            const float *c = prev + ch * pn;
            m[ch * n + i] = (c[(size_t)y0 * pw + x0] * (1 - ax) + c[(size_t)y0 * pw + x1] * ax) * (1 - ay) +
                            (c[(size_t)y1 * pw + x0] * (1 - ax) + c[(size_t)y1 * pw + x1] * ax) * ay;
          }
        }
    }
    membrane(l, m, L == nl - 1 ? 1000 : 60);
    free(prev);
    prev = m, pw = w, ph = h;
  }
  if (prev && pw == lv[0].w && ph == lv[0].h) {
    const size_t n = (size_t)pw * ph;
    for (size_t i = 0; i < n; i++)
      if (lv[0].hole[i])
        for (int ch = 0; ch < 3; ch++) lv[0].img[ch * n + i] = prev[ch * n + i];
  }
  free(prev);
}

/* The scale splitting the fill: structure finer than about an eighth of the
 * hole comes from the matched patches, broader light from the membrane (a
 * small spot keeps just the grain; a large one its bumps and folds too). */
static float detail_sigma(const level_t *l) {
  int x0 = l->w, y0 = l->h, x1 = 0, y1 = 0;
  for (int y = 0; y < l->h; y++)
    for (int x = 0; x < l->w; x++)
      if (l->hole[(size_t)y * l->w + x]) x0 = MIN(x0, x), y0 = MIN(y0, y), x1 = MAX(x1, x), y1 = MAX(y1, y);
  return fminf(fmaxf((float)MAX(x1 - x0, y1 - y0) / 8.0f, 2.5f), 48.0f);
}

/* The full-size fill: light from the membrane over the hole's edge, detail
 * from each pixel's best match: the image's detail above detail_sigma at
 * that source (taken where it lies, so pieces from different places add no
 * steps between them; a gradient carries on smoothly). The estimate (the
 * vote, the image outside the hole) is what gets high-passed, so the object
 * being removed doesn't leak in. */
static void finish(level_t *lv, int nl) {
  level_t *l = &lv[0];
  const int w = l->w, h = l->h;
  const size_t n = (size_t)w * h;
  float *est = (float *)malloc(3 * n * sizeof(float)), *lo = (float *)malloc(n * sizeof(float)),
        *tmp = (float *)malloc(n * sizeof(float));
  if (!est || !lo || !tmp) goto done;
  memcpy(est, l->img, 3 * n * sizeof(float));
  membrane_pyramid(lv, nl);
  const float sigma = detail_sigma(l);
  for (int ch = 0; ch < 3; ch++) {
    const float *e = est + ch * n;
    blur(e, lo, tmp, w, h, sigma);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        const size_t p = (size_t)y * w + x;
        if (!l->hole[p]) continue;
        const size_t q = (size_t)(y + l->oy[p]) * w + x + l->ox[p];
        l->img[ch * n + p] += e[q] - lo[q];
      }
  }
done:
  free(est);
  free(lo);
  free(tmp);
}

int almz_inpaint(const float *src, int w, int h, const uint8_t *hole, unsigned seed, float *out) {
  const size_t n = (size_t)w * h;
  memcpy(out, src, 3 * n * sizeof(float));
  size_t holes = 0;
  for (size_t i = 0; i < n; i++) holes += hole[i] != 0;
  if (!holes) return 0;
  if (w < P + 2 || h < P + 2) return -1;

  level_t lv[MAXLEV];
  memset(lv, 0, sizeof lv);
  int nl = 1, rc = -1;
  lv[0].w = w;
  lv[0].h = h;
  lv[0].img = (float *)calloc(NCH * n, sizeof(float));
  lv[0].hole = (uint8_t *)malloc(n);
  if (!lv[0].img || !lv[0].hole) goto done;
  memcpy(lv[0].img, src, 3 * n * sizeof(float));
  for (size_t i = 0; i < n; i++) lv[0].hole[i] = hole[i] != 0;
  features(&lv[0], 3);
  features(&lv[0], 5);

  /* down until the hole is about a patch across (or the image too small) */
  for (;;) {
    const level_t *l = &lv[nl - 1];
    int x0 = l->w, y0 = l->h, x1 = -1, y1 = -1;
    for (int y = 0; y < l->h; y++)
      for (int x = 0; x < l->w; x++)
        if (l->hole[(size_t)y * l->w + x]) x0 = MIN(x0, x), y0 = MIN(y0, y), x1 = MAX(x1, x), y1 = MAX(y1, y);
    if (x1 < 0 || MAX(x1 - x0, y1 - y0) < P || nl == MAXLEV || MIN(l->w, l->h) < 4 * P) break;
    if (!downsample(l, &lv[nl])) goto done;
    /* this level's own features (the full-size ones came down averaged) */
    features(&lv[nl], 5);
    nl++;
  }
  onion_fill(&lv[nl - 1]);

  uint32_t rs = seed ? seed : 0x9e3779b9u;
  for (int L = nl - 1; L >= 0; L--) {
    level_t *l = &lv[L];
    const int lw = l->w, lh = l->h;
    const size_t ln = (size_t)lw * lh;
    if (L < nl - 1) { /* the coarser result, bilinear, into this level's hole */
      const level_t *c = &lv[L + 1];
      const size_t cn = (size_t)c->w * c->h;
      for (int y = 0; y < lh; y++)
        for (int x = 0; x < lw; x++) {
          const size_t i = (size_t)y * lw + x;
          if (!l->hole[i]) continue;
          const float fx = fminf(fmaxf((x + 0.5f) / 2 - 0.5f, 0), c->w - 1), fy = fminf(fmaxf((y + 0.5f) / 2 - 0.5f, 0), c->h - 1);
          const int x0 = (int)fx, y0 = (int)fy, x1 = MIN(x0 + 1, c->w - 1), y1 = MIN(y0 + 1, c->h - 1);
          const float ax = fx - x0, ay = fy - y0;
          for (int ch = 0; ch < NCH; ch++) {
            const float *ci = c->img + ch * cn;
            l->img[ch * ln + i] = (ci[(size_t)y0 * c->w + x0] * (1 - ax) + ci[(size_t)y0 * c->w + x1] * ax) * (1 - ay) +
                                  (ci[(size_t)y1 * c->w + x0] * (1 - ax) + ci[(size_t)y1 * c->w + x1] * ax) * ay;
          }
        }
    }
    int nsrc = 0;
    uint8_t *valid = valid_sources(l, &nsrc);
    uint8_t *tg = targets(l);
    int *srcs = nsrc ? (int *)malloc(sizeof(int) * nsrc) : NULL;
    l->ox = (int *)calloc(ln, sizeof(int));
    l->oy = (int *)calloc(ln, sizeof(int));
    l->d = (float *)malloc(ln * sizeof(float));
    l->occ = (int *)calloc(ln, sizeof(int));
    if (!valid || !tg || !l->ox || !l->oy || !l->d || !l->occ || (nsrc && !srcs)) {
      free(valid), free(tg), free(srcs);
      goto done;
    }
    if (nsrc) {
      int k = 0;
      for (size_t i = 0; i < ln; i++)
        if (valid[i]) srcs[k++] = (int)i;
      /* the field: the coarser one, doubled, where it still lands on a valid
       * source; random elsewhere */
      for (int y = 0; y < lh; y++)
        for (int x = 0; x < lw; x++) {
          const size_t t = (size_t)y * lw + x;
          if (!tg[t]) continue;
          int ox = 0, oy = 0, have = 0;
          if (L < nl - 1 && lv[L + 1].ox) {
            const level_t *c = &lv[L + 1];
            const size_t ct = (size_t)MIN(y / 2, c->h - 1) * c->w + MIN(x / 2, c->w - 1);
            ox = 2 * c->ox[ct], oy = 2 * c->oy[ct];
            have = ok_src(valid, lw, lh, x + ox, y + oy);
          }
          if (!have) random_source(srcs, nsrc, &rs, x, y, lw, &ox, &oy);
          l->ox[t] = ox, l->oy[t] = oy;
          l->d[t] = patch_dist(l, x, y, x + ox, y + oy, -1);
          l->occ[(size_t)(y + oy) * lw + x + ox]++;
        }
      {
        /* a use costs a fraction of a typical match's distance */
        double sum = 0;
        size_t k = 0;
        for (size_t t = 0; t < ln; t++)
          if (tg[t]) sum += l->d[t], k++;
        l->lambda = k ? (float)(0.5 * sum / k) : 0.0f;
      }
      const int em = L == 0 ? 2 : 4;
      for (int e = 0; e < em; e++) {
        patchmatch(l, valid, tg, 4, &rs);
        vote(l, tg);
        /* distances to the new estimate */
        dist_ctx dc = {l, tg};
        par_bands(dist_band, &dc);
      }
      if (L == 0) finish(lv, nl);
    }
    free(valid), free(tg), free(srcs);
  }
  for (size_t i = 0; i < n; i++)
    if (lv[0].hole[i])
      for (int ch = 0; ch < 3; ch++) out[ch * n + i] = fminf(fmaxf(lv[0].img[ch * n + i], 0.0f), 1.0f);
  rc = 0;
done:
  for (int i = 0; i < MAXLEV; i++) level_free(&lv[i]);
  return rc;
}

/* ---- masks: regions, holes, snapshots ------------------------------------- */
static inline float mask_val(const AlmzMask *m, float mx, float my) {
  mx = fminf(fmaxf(mx, 0), m->w - 1);
  my = fminf(fmaxf(my, 0), m->h - 1);
  const int x = (int)mx, y = (int)my, x2 = MIN(x + 1, m->w - 1), y2 = MIN(y + 1, m->h - 1);
  const float fx = mx - x, fy = my - y;
  const float *s = m->soft;
  return (s[(size_t)y * m->w + x] * (1 - fx) + s[(size_t)y * m->w + x2] * fx) * (1 - fy) +
         (s[(size_t)y2 * m->w + x] * (1 - fx) + s[(size_t)y2 * m->w + x2] * fx) * fy;
}

void almz_mask_hole(const AlmzMask *m, int x0, int y0, int w, int h, float thr, uint8_t *out) {
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++)
      out[(size_t)y * w + x] =
          mask_val(m, (x0 + x + 0.5f) / m->div - 0.5f, (y0 + y + 0.5f) / m->div - 0.5f) > thr;
}

static int overlaps(const int32_t *a, const int32_t *b) {
  return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
}

int almz_mask_regions(const AlmzMask *m, float thr, int margin, int32_t *rects, int max) {
  const int w = m->w, h = m->h;
  uint8_t *seen = (uint8_t *)calloc((size_t)w * h, 1);
  int *stack = (int *)malloc(sizeof(int) * (size_t)w * h);
  int n = 0;
  if (!seen || !stack) goto done;
  for (int i0 = 0; i0 < w * h && n < max; i0++) {
    if (seen[i0] || m->soft[i0] <= thr) continue;
    int x0 = w, y0 = h, x1 = -1, y1 = -1, sp = 0;
    stack[sp++] = i0;
    seen[i0] = 1;
    while (sp) {
      const int i = stack[--sp], x = i % w, y = i / w;
      x0 = MIN(x0, x), y0 = MIN(y0, y), x1 = MAX(x1, x), y1 = MAX(y1, y);
      for (int dy = -1; dy <= 1; dy++)
        for (int dx = -1; dx <= 1; dx++) {
          const int nx = x + dx, ny = y + dy;
          if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
          const int j = ny * w + nx;
          if (!seen[j] && m->soft[j] > thr) seen[j] = 1, stack[sp++] = j;
        }
    }
    /* mask cells -> image px, grown by the margin, clamped */
    const int ix0 = MAX(0, x0 * m->div - margin), iy0 = MAX(0, y0 * m->div - margin);
    const int ix1 = MIN(m->iw, (x1 + 1) * m->div + margin), iy1 = MIN(m->ih, (y1 + 1) * m->div + margin);
    int32_t *r = rects + 4 * n++;
    r[0] = ix0, r[1] = iy0, r[2] = ix1 - ix0, r[3] = iy1 - iy0;
  }
  /* merge overlapping rects until none overlap */
  for (int merged = 1; merged;) {
    merged = 0;
    for (int i = 0; i < n && !merged; i++)
      for (int j = i + 1; j < n && !merged; j++) {
        int32_t *a = rects + 4 * i, *b = rects + 4 * j;
        if (!overlaps(a, b)) continue;
        const int x0 = MIN(a[0], b[0]), y0 = MIN(a[1], b[1]);
        const int x1 = MAX(a[0] + a[2], b[0] + b[2]), y1 = MAX(a[1] + a[3], b[1] + b[3]);
        a[0] = x0, a[1] = y0, a[2] = x1 - x0, a[3] = y1 - y0;
        memmove(b, b + 4, sizeof(int32_t) * 4 * (n - j - 1));
        n--;
        merged = 1;
      }
  }
done:
  free(seen);
  free(stack);
  return n;
}
