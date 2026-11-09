/* almazimg.c — see almazimg.h. GPL-2.0-or-later. */
#include "almazimg.h"

#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <jpeglib.h>
#include <png.h>
#include <setjmp.h>
#include <tiffio.h>

#define MIN(a, b) ((a) < (b) ? (a) : (b))
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#define CLAMP(v, lo, hi) ((v) < (lo) ? (lo) : (v) > (hi) ? (hi) : (v))

static char *dupstr(const char *s) {
  char *o = (char *)malloc(strlen(s) + 1);
  strcpy(o, s);
  return o;
}
static void set_err(char **err, const char *msg) {
  if (err) *err = dupstr(msg);
}
void almz_free_string(char *s) { free(s); }

/* ---- parallel for ------------------------------------------------------ */
typedef void (*job_fn)(void *ctx, int i0, int i1);
typedef struct { job_fn fn; void *ctx; int i0, i1; } job_t;

static void *job_run(void *p) {
  job_t *j = (job_t *)p;
  j->fn(j->ctx, j->i0, j->i1);
  return NULL;
}

static int ncpu(void) {
  static int n = 0;
  if (!n) {
    long c = sysconf(_SC_NPROCESSORS_ONLN);
    n = (int)CLAMP(c, 1, 16);
  }
  return n;
}

/* Split [lo, hi) into contiguous chunks across threads; small ranges run inline. */
static void par_range(int lo, int hi, int min_chunk, job_fn fn, void *ctx) {
  const int n = hi - lo;
  int t = MIN(ncpu(), MAX(1, n / MAX(1, min_chunk)));
  if (t <= 1) {
    if (n > 0) fn(ctx, lo, hi);
    return;
  }
  pthread_t th[16];
  job_t jobs[16];
  for (int i = 0; i < t; i++) {
    jobs[i] = (job_t){fn, ctx, lo + (int)((long)n * i / t), lo + (int)((long)n * (i + 1) / t)};
    if (i) pthread_create(&th[i], NULL, job_run, &jobs[i]);
  }
  job_run(&jobs[0]);
  for (int i = 1; i < t; i++) pthread_join(th[i], NULL);
}

static void par_for(int n, int min_chunk, job_fn fn, void *ctx) { par_range(0, n, min_chunk, fn, ctx); }

/* ---- images ------------------------------------------------------------ */
static void pyr_alloc(AlmzImg *img) {
  int w = img->w, h = img->h, k = 1;
  img->lw[0] = w;
  img->lh[0] = h;
  while (k < ALMZ_MAX_LEV && w > 64 && h > 64) {
    w = (w + 1) / 2;
    h = (h + 1) / 2;
    img->lw[k] = w;
    img->lh[k] = h;
    img->lev[k] = (uint16_t *)calloc((size_t)w * h * 3, sizeof(uint16_t));
    k++;
  }
  img->nlev = k;
}

AlmzImg *almz_img_wrap(int w, int h, uint16_t *data) {
  AlmzImg *img = (AlmzImg *)calloc(1, sizeof(AlmzImg));
  img->w = w;
  img->h = h;
  img->lev[0] = data;
  pyr_alloc(img);
  almz_img_pyr_update(img, 0, 0, w, h);
  return img;
}

AlmzImg *almz_img_new(int w, int h) {
  return almz_img_wrap(w, h, (uint16_t *)calloc((size_t)w * h * 3, sizeof(uint16_t)));
}

AlmzImg *almz_img_copy(const AlmzImg *src) {
  AlmzImg *img = (AlmzImg *)calloc(1, sizeof(AlmzImg));
  *img = *src;
  for (int k = 0; k < src->nlev; k++) {
    size_t n = (size_t)src->lw[k] * src->lh[k] * 3;
    img->lev[k] = (uint16_t *)malloc(n * sizeof(uint16_t));
    memcpy(img->lev[k], src->lev[k], n * sizeof(uint16_t));
  }
  return img;
}

void almz_img_free(AlmzImg *img) {
  if (!img) return;
  for (int k = 0; k < img->nlev; k++) free(img->lev[k]);
  free(img);
}

int almz_img_width(const AlmzImg *img) { return img->w; }
int almz_img_height(const AlmzImg *img) { return img->h; }

typedef struct { const uint16_t *src; uint16_t *dst; int sw, sh, dw, x0, x1; } down_ctx;

static void down_rows(void *p, int y0, int y1) {
  down_ctx *c = (down_ctx *)p;
  for (int y = y0; y < y1; y++) {
    const int sy0 = MIN(2 * y, c->sh - 1), sy1 = MIN(2 * y + 1, c->sh - 1);
    const uint16_t *r0 = c->src + (size_t)sy0 * c->sw * 3, *r1 = c->src + (size_t)sy1 * c->sw * 3;
    uint16_t *o = c->dst + (size_t)y * c->dw * 3;
    for (int x = c->x0; x < c->x1; x++) {
      const int sx0 = MIN(2 * x, c->sw - 1) * 3, sx1 = MIN(2 * x + 1, c->sw - 1) * 3;
      for (int ch = 0; ch < 3; ch++)
        o[x * 3 + ch] = (uint16_t)((r0[sx0 + ch] + r0[sx1 + ch] + r1[sx0 + ch] + r1[sx1 + ch] + 2) >> 2);
    }
  }
}

void almz_img_pyr_update(AlmzImg *img, int x, int y, int w, int h) {
  int x0 = MAX(0, x), y0 = MAX(0, y), x1 = MIN(img->w, x + w), y1 = MIN(img->h, y + h);
  for (int k = 1; k < img->nlev; k++) {
    x0 /= 2;
    y0 /= 2;
    x1 = MIN(img->lw[k], (x1 + 1) / 2);
    y1 = MIN(img->lh[k], (y1 + 1) / 2);
    if (x1 <= x0 || y1 <= y0) return;
    down_ctx c = {img->lev[k - 1], img->lev[k], img->lw[k - 1], img->lh[k - 1], img->lw[k], x0, x1};
    par_range(y0, y1, MAX(1, 65536 / MAX(1, x1 - x0)), down_rows, &c);
  }
}

void almz_img_get(const AlmzImg *img, int x, int y, float *rgb) {
  x = CLAMP(x, 0, img->w - 1);
  y = CLAMP(y, 0, img->h - 1);
  const uint16_t *p = img->lev[0] + ((size_t)y * img->w + x) * 3;
  for (int c = 0; c < 3; c++) rgb[c] = p[c] / 65535.0f;
}

static inline uint16_t f2u(float v) {
  v = CLAMP(v, 0.0f, 1.0f);
  return (uint16_t)(v * 65535.0f + 0.5f);
}

void almz_img_set_f32(AlmzImg *img, const float *rgb) {
  size_t n = (size_t)img->w * img->h * 3;
  for (size_t i = 0; i < n; i++) img->lev[0][i] = f2u(rgb[i]);
  almz_img_pyr_update(img, 0, 0, img->w, img->h);
}

/* ---- masks ------------------------------------------------------------- */
AlmzMask *almz_mask_new(int iw, int ih, int div, float value) {
  AlmzMask *m = (AlmzMask *)calloc(1, sizeof(AlmzMask));
  div = MAX(1, div);
  m->iw = iw;
  m->ih = ih;
  m->div = div;
  m->w = (iw + div - 1) / div;
  m->h = (ih + div - 1) / div;
  size_t n = (size_t)m->w * m->h;
  m->paint = (float *)malloc(n * sizeof(float));
  m->soft = (float *)malloc(n * sizeof(float));
  almz_mask_fill(m, value);
  return m;
}

void almz_mask_free(AlmzMask *m) {
  if (!m) return;
  free(m->paint);
  free(m->soft);
  free(m);
}

/* Recompute `soft` over the mask rectangle [x0,x1) x [y0,y1): a separable
 * gaussian of `paint` (edges clamped). */
static void soft_update(AlmzMask *m, int x0, int y0, int x1, int y1) {
  x0 = MAX(0, x0);
  y0 = MAX(0, y0);
  x1 = MIN(m->w, x1);
  y1 = MIN(m->h, y1);
  if (x1 <= x0 || y1 <= y0) return;
  const float sigma = m->feather / m->div;
  if (sigma < 0.3f) {
    for (int y = y0; y < y1; y++)
      memcpy(m->soft + (size_t)y * m->w + x0, m->paint + (size_t)y * m->w + x0, (x1 - x0) * sizeof(float));
    return;
  }
  const int R = (int)ceilf(3.0f * sigma);
  float *k = (float *)malloc((2 * R + 1) * sizeof(float));
  float ks = 0;
  for (int i = -R; i <= R; i++) ks += k[i + R] = expf(-(float)(i * i) / (2 * sigma * sigma));
  for (int i = 0; i <= 2 * R; i++) k[i] /= ks;
  const int ty0 = MAX(0, y0 - R), ty1 = MIN(m->h, y1 + R), tw = x1 - x0;
  float *tmp = (float *)malloc((size_t)(ty1 - ty0) * tw * sizeof(float));
  for (int y = ty0; y < ty1; y++) {
    const float *row = m->paint + (size_t)y * m->w;
    float *o = tmp + (size_t)(y - ty0) * tw;
    for (int x = x0; x < x1; x++) {
      float s = 0;
      for (int i = -R; i <= R; i++) s += k[i + R] * row[CLAMP(x + i, 0, m->w - 1)];
      o[x - x0] = s;
    }
  }
  for (int y = y0; y < y1; y++) {
    float *o = m->soft + (size_t)y * m->w;
    for (int x = 0; x < tw; x++) {
      float s = 0;
      for (int i = -R; i <= R; i++) {
        int yy = CLAMP(y + i, 0, m->h - 1);
        yy = CLAMP(yy, ty0, ty1 - 1);
        s += k[i + R] * tmp[(size_t)(yy - ty0) * tw + x];
      }
      o[x0 + x] = s;
    }
  }
  free(tmp);
  free(k);
}

void almz_mask_fill(AlmzMask *m, float v) {
  size_t n = (size_t)m->w * m->h;
  for (size_t i = 0; i < n; i++) m->paint[i] = m->soft[i] = v;
}

void almz_mask_invert(AlmzMask *m) {
  size_t n = (size_t)m->w * m->h;
  for (size_t i = 0; i < n; i++) m->paint[i] = 1.0f - m->paint[i];
  soft_update(m, 0, 0, m->w, m->h);
}

void almz_mask_set_feather(AlmzMask *m, float sigma) {
  m->feather = MAX(0.0f, sigma);
  soft_update(m, 0, 0, m->w, m->h);
}

static inline float falloff(float d, float hard) {
  if (d >= 1.0f) return 0.0f;
  if (d <= hard) return 1.0f;
  float t = (d - hard) / (1.0f - hard);
  return 1.0f - t * t * (3.0f - 2.0f * t);
}

void almz_mask_stroke(AlmzMask *m, double ax, double ay, double bx, double by,
                      double radius, double hardness, double flow, int erase) {
  const float div = (float)m->div;
  const float r = MAX(0.5f, (float)radius / div);
  const float x0 = (float)ax / div - 0.5f, y0 = (float)ay / div - 0.5f;
  const float x1 = (float)bx / div - 0.5f, y1 = (float)by / div - 0.5f;
  const float hard = (float)CLAMP(hardness, 0.0, 0.99), fl = (float)CLAMP(flow, 0.0, 1.0);
  /* every mask pixel within r of the segment: distance to the segment */
  const int bx0 = MAX(0, (int)floorf(MIN(x0, x1) - r)), by0 = MAX(0, (int)floorf(MIN(y0, y1) - r));
  const int bx1 = MIN(m->w, (int)ceilf(MAX(x0, x1) + r) + 1), by1 = MIN(m->h, (int)ceilf(MAX(y0, y1) + r) + 1);
  const float dx = x1 - x0, dy = y1 - y0, len2 = dx * dx + dy * dy;
  for (int y = by0; y < by1; y++) {
    float *row = m->paint + (size_t)y * m->w;
    for (int x = bx0; x < bx1; x++) {
      float t = len2 > 0 ? ((x - x0) * dx + (y - y0) * dy) / len2 : 0.0f;
      t = CLAMP(t, 0.0f, 1.0f);
      const float px = x0 + t * dx - x, py = y0 + t * dy - y;
      const float a = falloff(sqrtf(px * px + py * py) / r, hard) * fl;
      if (a <= 0) continue;
      row[x] = erase ? MIN(row[x], 1.0f - a) : MAX(row[x], a);
    }
  }
  const int R = (int)ceilf(3.0f * m->feather / div) + 1;
  soft_update(m, bx0 - R, by0 - R, bx1 + R, by1 + R);
}

static inline float mask_at(const AlmzMask *m, float mx, float my) {
  mx = CLAMP(mx, 0.0f, (float)(m->w - 1));
  my = CLAMP(my, 0.0f, (float)(m->h - 1));
  const int x = (int)mx, y = (int)my;
  const int x2 = MIN(x + 1, m->w - 1), y2 = MIN(y + 1, m->h - 1);
  const float fx = mx - x, fy = my - y;
  const float *r0 = m->soft + (size_t)y * m->w, *r1 = m->soft + (size_t)y2 * m->w;
  return (r0[x] * (1 - fx) + r0[x2] * fx) * (1 - fy) + (r1[x] * (1 - fx) + r1[x2] * fx) * fy;
}

float almz_mask_max(const AlmzMask *m, int x, int y, int w, int h) {
  const int x0 = MAX(0, x / m->div), y0 = MAX(0, y / m->div);
  const int x1 = MIN(m->w, (x + w + m->div - 1) / m->div + 1), y1 = MIN(m->h, (y + h + m->div - 1) / m->div + 1);
  float v = 0.0f;
  for (int yy = y0; yy < y1; yy++)
    for (int xx = x0; xx < x1; xx++) v = MAX(v, m->soft[(size_t)yy * m->w + xx]);
  return v;
}

float almz_mask_sample(const AlmzMask *m, double x, double y) {
  return mask_at(m, (float)(x / m->div - 0.5), (float)(y / m->div - 0.5));
}

/* ---- compositing ------------------------------------------------------- */
/* Sample level `k` of `img` at level coordinates (lx, ly) into c[3] (0..65535). */
static inline void sample(const AlmzImg *img, int k, float lx, float ly, int nearest, float *c) {
  const int w = img->lw[k], h = img->lh[k];
  const uint16_t *d = img->lev[k];
  if (nearest) {
    const int x = CLAMP((int)floorf(lx + 0.5f), 0, w - 1), y = CLAMP((int)floorf(ly + 0.5f), 0, h - 1);
    const uint16_t *p = d + ((size_t)y * w + x) * 3;
    c[0] = p[0];
    c[1] = p[1];
    c[2] = p[2];
    return;
  }
  lx = CLAMP(lx, 0.0f, (float)(w - 1));
  ly = CLAMP(ly, 0.0f, (float)(h - 1));
  const int x = (int)lx, y = (int)ly, x2 = MIN(x + 1, w - 1), y2 = MIN(y + 1, h - 1);
  const float fx = lx - x, fy = ly - y;
  const uint16_t *a = d + ((size_t)y * w + x) * 3, *b = d + ((size_t)y * w + x2) * 3;
  const uint16_t *e = d + ((size_t)y2 * w + x) * 3, *f = d + ((size_t)y2 * w + x2) * 3;
  for (int ch = 0; ch < 3; ch++)
    c[ch] = (a[ch] * (1 - fx) + b[ch] * fx) * (1 - fy) + (e[ch] * (1 - fx) + f[ch] * fx) * fy;
}

/* Composite at level-0 image coordinates (ix, iy) using pyramid level k. */
static inline void composite_at(const AlmzImg *base, const AlmzLayer *layers, int n, int k,
                                float ix, float iy, int nearest, float *c) {
  const float s = (float)(1 << k);
  const float lx = ix / s - 0.5f, ly = iy / s - 0.5f;
  sample(base, k, lx, ly, nearest, c);
  for (int i = 0; i < n; i++) {
    const AlmzLayer *L = &layers[i];
    if (!L->enabled || !L->img || L->opacity <= 0) continue;
    float a = L->opacity;
    if (L->mask) a *= mask_at(L->mask, ix / L->mask->div - 0.5f, iy / L->mask->div - 0.5f);
    if (a <= 0) continue;
    float l[3];
    sample(L->img, MIN(k, L->img->nlev - 1), lx, ly, nearest, l);
    c[0] += (l[0] - c[0]) * a;
    c[1] += (l[1] - c[1]) * a;
    c[2] += (l[2] - c[2]) * a;
  }
}

typedef struct {
  const AlmzImg *base;
  const AlmzLayer *layers;
  int n, k, nearest;
  double ox, oy, scale;
  const AlmzMask *overlay;
  float tint[4];
  uint8_t *out;
  int out_w, rx, rw;
  uint8_t bg[4];
} render_ctx;

static void render_rows(void *p, int y0, int y1) {
  const render_ctx *c = (const render_ctx *)p;
  const int W = c->base->w, H = c->base->h;
  for (int sy = y0; sy < y1; sy++) {
    uint8_t *o = c->out + ((size_t)sy * c->out_w + c->rx) * 4;
    const float iy = (float)((sy + 0.5 - c->oy) / c->scale);
    for (int sx = c->rx; sx < c->rx + c->rw; sx++, o += 4) {
      const float ix = (float)((sx + 0.5 - c->ox) / c->scale);
      if (ix < 0 || iy < 0 || ix >= W || iy >= H) {
        memcpy(o, c->bg, 4);
        continue;
      }
      float v[3];
      composite_at(c->base, c->layers, c->n, c->k, ix, iy, c->nearest, v);
      if (c->overlay) {
        const float a = c->tint[3] * mask_at(c->overlay, ix / c->overlay->div - 0.5f, iy / c->overlay->div - 0.5f);
        for (int ch = 0; ch < 3; ch++) v[ch] = v[ch] * (1 - a) + c->tint[ch] * 65535.0f * a;
      }
      o[0] = (uint8_t)CLAMP((int)(v[0] / 257.0f + 0.5f), 0, 255);
      o[1] = (uint8_t)CLAMP((int)(v[1] / 257.0f + 0.5f), 0, 255);
      o[2] = (uint8_t)CLAMP((int)(v[2] / 257.0f + 0.5f), 0, 255);
      o[3] = 255;
    }
  }
}

void almz_render(const AlmzImg *base, const AlmzLayer *layers, int nlayers,
                 double ox, double oy, double scale,
                 const AlmzMask *overlay, const float *tint,
                 uint8_t *out, int out_w, int out_h,
                 int rx, int ry, int rw, int rh, const uint8_t *bg) {
  rx = MAX(0, rx);
  ry = MAX(0, ry);
  rw = MIN(rw, out_w - rx);
  rh = MIN(rh, out_h - ry);
  if (rw <= 0 || rh <= 0 || scale <= 0) return;
  /* the finest level with at most ~1 source pixel per screen pixel */
  int k = 0;
  while (k + 1 < base->nlev && scale * (1 << (k + 1)) <= 1.0) k++;
  render_ctx c = {base, layers, nlayers, k, scale >= 2.0, ox, oy, scale, overlay,
                  {0, 0, 0, 0}, out, out_w, rx, rw, {0, 0, 0, 255}};
  if (tint) memcpy(c.tint, tint, sizeof(c.tint));
  if (bg) memcpy(c.bg, bg, 4);
  par_range(ry, ry + rh, 8, render_rows, &c);
}

/* ---- tiles ------------------------------------------------------------- */
static inline int mirror(int v, int n) {
  if (n == 1) return 0;
  while (v < 0 || v >= n) v = v < 0 ? -v : 2 * n - 2 - v;
  return v;
}

typedef struct {
  const AlmzImg *base;
  const AlmzLayer *layers;
  int n, x0, y0, tw, th;
  float *dst;
} extract_ctx;

static void extract_rows(void *p, int r0, int r1) {
  const extract_ctx *c = (const extract_ctx *)p;
  const size_t plane = (size_t)c->tw * c->th;
  for (int r = r0; r < r1; r++) {
    const int iy = mirror(c->y0 + r, c->base->h);
    for (int q = 0; q < c->tw; q++) {
      const int ix = mirror(c->x0 + q, c->base->w);
      float v[3];
      composite_at(c->base, c->layers, c->n, 0, ix + 0.5f, iy + 0.5f, 1, v);
      const size_t i = (size_t)r * c->tw + q;
      c->dst[i] = v[0] / 65535.0f;
      c->dst[plane + i] = v[1] / 65535.0f;
      c->dst[2 * plane + i] = v[2] / 65535.0f;
    }
  }
}

void almz_tile_extract(const AlmzImg *base, const AlmzLayer *layers, int nlayers,
                       int x0, int y0, int tw, int th, float *dst) {
  extract_ctx c = {base, layers, nlayers, x0, y0, tw, th, dst};
  par_for(th, 16, extract_rows, &c);
}

static inline float ramp(int d, int r) { return r > 0 && d < r ? (d + 0.5f) / r : 1.0f; }

void almz_tile_write(AlmzImg *dst, const float *src, int tw, int th, int x0, int y0,
                     int rl, int rt, int rr, int rb) {
  const size_t plane = (size_t)tw * th;
  const int xs = MAX(0, x0), ys = MAX(0, y0);
  const int xe = MIN(dst->w, x0 + tw), ye = MIN(dst->h, y0 + th);
  for (int y = ys; y < ye; y++) {
    const int r = y - y0;
    const float wy = ramp(r, rt) * ramp(th - 1 - r, rb);
    uint16_t *o = dst->lev[0] + ((size_t)y * dst->w) * 3;
    for (int x = xs; x < xe; x++) {
      const int q = x - x0;
      const float a = wy * ramp(q, rl) * ramp(tw - 1 - q, rr);
      const size_t i = (size_t)r * tw + q;
      for (int ch = 0; ch < 3; ch++) {
        const float s = CLAMP(src[ch * plane + i], 0.0f, 1.0f) * 65535.0f;
        const float d = o[x * 3 + ch];
        o[x * 3 + ch] = (uint16_t)(d + (s - d) * a + 0.5f);
      }
    }
  }
  almz_img_pyr_update(dst, xs, ys, xe - xs, ye - ys);
}

/* separable gaussian blur of one plane (edges clamped) */
static void blur_plane(const float *src, float *dst, int w, int h, float sigma) {
  const int R = MAX(1, (int)ceilf(3.0f * sigma));
  float *k = (float *)malloc((2 * R + 1) * sizeof(float)), ks = 0;
  for (int i = -R; i <= R; i++) ks += k[i + R] = expf(-(float)(i * i) / (2 * sigma * sigma));
  for (int i = 0; i <= 2 * R; i++) k[i] /= ks;
  float *tmp = (float *)malloc((size_t)w * h * sizeof(float));
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++) {
      float s = 0;
      for (int i = -R; i <= R; i++) s += k[i + R] * src[(size_t)y * w + CLAMP(x + i, 0, w - 1)];
      tmp[(size_t)y * w + x] = s;
    }
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++) {
      float s = 0;
      for (int i = -R; i <= R; i++) s += k[i + R] * tmp[(size_t)CLAMP(y + i, 0, h - 1) * w + x];
      dst[(size_t)y * w + x] = s;
    }
  free(tmp);
  free(k);
}

void almz_tile_unsharp(float *t, int tw, int th, float sigma, float amount) {
  const size_t plane = (size_t)tw * th;
  float *b = (float *)malloc(plane * sizeof(float));
  for (int ch = 0; ch < 3; ch++) {
    float *p = t + ch * plane;
    blur_plane(p, b, tw, th, MAX(0.3f, sigma));
    for (size_t i = 0; i < plane; i++) p[i] = CLAMP(p[i] + amount * (p[i] - b[i]), 0.0f, 1.0f);
  }
  free(b);
}

void almz_tile_detail(float *out, const float *in, int tw, int th, float sigma, float amount) {
  const size_t plane = (size_t)tw * th;
  float *bi = (float *)malloc(plane * sizeof(float)), *bo = (float *)malloc(plane * sizeof(float));
  for (int ch = 0; ch < 3; ch++) {
    const float *pi = in + ch * plane;
    float *po = out + ch * plane;
    blur_plane(pi, bi, tw, th, MAX(0.3f, sigma));
    blur_plane(po, bo, tw, th, MAX(0.3f, sigma));
    for (size_t i = 0; i < plane; i++)
      po[i] = CLAMP(po[i] + amount * ((pi[i] - bi[i]) - (po[i] - bo[i])), 0.0f, 1.0f);
  }
  free(bi);
  free(bo);
}

/* ---- estimators -------------------------------------------------------- */
static inline float lum16(const uint16_t *p) { return (0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]) / 65535.0f; }

void almz_estimate(const AlmzImg *img, float *noise, float *sharpness) {
  /* Immerkaer: sigma = sqrt(pi/2) / (6 (W-2)(H-2)) * sum |I * N| over a
   * central window, N = [1 -2 1; -2 4 -2; 1 -2 1]. Edges inflate it, so take
   * the median of per-block estimates, which favours flat regions. */
  const int W = img->w, H = img->h;
  const int win = MIN(2048, MIN(W, H));
  const int wx0 = (W - win) / 2, wy0 = (H - win) / 2, B = 32;
  const int nb = (win - 2) / B;
  float *est = (float *)malloc(sizeof(float) * MAX(1, nb * nb));
  int ne = 0;
  const uint16_t *d = img->lev[0];
  for (int by = 0; by < nb; by++)
    for (int bx = 0; bx < nb; bx++) {
      double s = 0;
      for (int y = 1; y < B - 1; y++)
        for (int x = 1; x < B - 1; x++) {
          const int X = wx0 + bx * B + x, Y = wy0 + by * B + y;
#define L(dx, dy) lum16(d + ((size_t)(Y + (dy)) * W + (X + (dx))) * 3)
          const float v = L(-1, -1) - 2 * L(0, -1) + L(1, -1) - 2 * L(-1, 0) + 4 * L(0, 0) - 2 * L(1, 0) +
                          L(-1, 1) - 2 * L(0, 1) + L(1, 1);
#undef L
          s += fabsf(v);
        }
      est[ne++] = (float)(sqrt(M_PI / 2) / (6.0 * (B - 2) * (B - 2)) * s);
    }
  /* 25th percentile of block estimates: flat blocks */
  for (int i = 1; i < ne; i++) {
    float v = est[i];
    int j = i - 1;
    while (j >= 0 && est[j] > v) {
      est[j + 1] = est[j];
      j--;
    }
    est[j + 1] = v;
  }
  *noise = ne ? est[ne / 4] : 0.0f;
  free(est);
  /* sharpness at level 1 (noise averaged down): mean of the top decile of
   * gradient magnitudes, relative to the luminance spread. */
  const int k = MIN(1, img->nlev - 1), w = img->lw[k], h = img->lh[k];
  const uint16_t *e = img->lev[k];
  int hist[1024] = {0}, cnt = 0;
  float lo = 1, hi = 0;
  for (int y = 1; y < h - 1; y += 2)
    for (int x = 1; x < w - 1; x += 2) {
      const float c = lum16(e + ((size_t)y * w + x) * 3);
      const float gx = lum16(e + ((size_t)y * w + x + 1) * 3) - lum16(e + ((size_t)y * w + x - 1) * 3);
      const float gy = lum16(e + ((size_t)(y + 1) * w + x) * 3) - lum16(e + ((size_t)(y - 1) * w + x) * 3);
      const float g = sqrtf(gx * gx + gy * gy);
      hist[MIN(1023, (int)(g * 2048))]++;
      cnt++;
      lo = MIN(lo, c);
      hi = MAX(hi, c);
    }
  int acc = 0, i = 1023;
  double sum = 0;
  for (; i >= 0 && acc < cnt / 10; i--) {
    acc += hist[i];
    sum += hist[i] * (i + 0.5) / 2048.0;
  }
  *sharpness = acc ? (float)(sum / acc / MAX(0.05f, hi - lo)) : 0.0f;
}

/* ---- file I/O ---------------------------------------------------------- */
AlmzImg *almz_from_rgb8(const uint8_t *rgb, int w, int h) {
  uint16_t *d = (uint16_t *)malloc((size_t)w * h * 3 * sizeof(uint16_t));
  for (size_t i = 0; i < (size_t)w * h * 3; i++) d[i] = (uint16_t)(rgb[i] * 257);
  return almz_img_wrap(w, h, d);
}

typedef struct { struct jpeg_error_mgr mgr; jmp_buf jb; char msg[JMSG_LENGTH_MAX]; } jerr_t;
static void jerr_exit(j_common_ptr ci) {
  jerr_t *e = (jerr_t *)ci->err;
  (*ci->err->format_message)(ci, e->msg);
  longjmp(e->jb, 1);
}

static AlmzImg *jpeg_read(struct jpeg_decompress_struct *ci, jerr_t *je, char **err) {
  (void)je;
  jpeg_read_header(ci, TRUE);
  ci->out_color_space = JCS_RGB;
  jpeg_start_decompress(ci);
  const int w = ci->output_width, h = ci->output_height;
  uint8_t *rgb = (uint8_t *)malloc((size_t)w * h * 3);
  while (ci->output_scanline < (JDIMENSION)h) {
    JSAMPROW row = rgb + (size_t)ci->output_scanline * w * 3;
    jpeg_read_scanlines(ci, &row, 1);
  }
  jpeg_finish_decompress(ci);
  AlmzImg *img = almz_from_rgb8(rgb, w, h);
  free(rgb);
  (void)err;
  return img;
}

AlmzImg *almz_load_jpeg_mem(const uint8_t *data, size_t len, char **err) {
  struct jpeg_decompress_struct ci;
  jerr_t je;
  ci.err = jpeg_std_error(&je.mgr);
  je.mgr.error_exit = jerr_exit;
  if (setjmp(je.jb)) {
    jpeg_destroy_decompress(&ci);
    set_err(err, je.msg);
    return NULL;
  }
  jpeg_create_decompress(&ci);
  jpeg_mem_src(&ci, data, (unsigned long)len);
  AlmzImg *img = jpeg_read(&ci, &je, err);
  jpeg_destroy_decompress(&ci);
  return img;
}

static AlmzImg *load_jpeg(FILE *f, char **err) {
  struct jpeg_decompress_struct ci;
  jerr_t je;
  ci.err = jpeg_std_error(&je.mgr);
  je.mgr.error_exit = jerr_exit;
  if (setjmp(je.jb)) {
    jpeg_destroy_decompress(&ci);
    set_err(err, je.msg);
    return NULL;
  }
  jpeg_create_decompress(&ci);
  jpeg_stdio_src(&ci, f);
  AlmzImg *img = jpeg_read(&ci, &je, err);
  jpeg_destroy_decompress(&ci);
  return img;
}

static AlmzImg *load_png(FILE *f, char **err) {
  png_structp p = png_create_read_struct(PNG_LIBPNG_VER_STRING, NULL, NULL, NULL);
  png_infop info = png_create_info_struct(p);
  uint16_t *d = NULL;
  png_bytep *rows = NULL;
  if (setjmp(png_jmpbuf(p))) {
    png_destroy_read_struct(&p, &info, NULL);
    free(d);
    free(rows);
    set_err(err, "invalid PNG");
    return NULL;
  }
  png_init_io(p, f);
  png_read_info(p, info);
  const int w = png_get_image_width(p, info), h = png_get_image_height(p, info);
  const int ct = png_get_color_type(p, info);
  png_set_expand(p);
  png_set_strip_alpha(p);
  if (ct == PNG_COLOR_TYPE_GRAY || ct == PNG_COLOR_TYPE_GRAY_ALPHA) png_set_gray_to_rgb(p);
  if (png_get_bit_depth(p, info) < 16) png_set_expand_16(p);
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
  png_set_swap(p);
#endif
  png_read_update_info(p, info);
  d = (uint16_t *)malloc((size_t)w * h * 3 * sizeof(uint16_t));
  rows = (png_bytep *)malloc(sizeof(png_bytep) * h);
  for (int y = 0; y < h; y++) rows[y] = (png_bytep)(d + (size_t)y * w * 3);
  png_read_image(p, rows);
  png_destroy_read_struct(&p, &info, NULL);
  free(rows);
  return almz_img_wrap(w, h, d);
}

static void tiff_silent(const char *m, const char *fmt, va_list ap) {
  (void)m;
  (void)fmt;
  (void)ap;
}

static AlmzImg *load_tiff(const char *path, char **err) {
  TIFFSetWarningHandler(tiff_silent);
  TIFF *t = TIFFOpen(path, "r");
  if (!t) {
    set_err(err, "cannot open TIFF");
    return NULL;
  }
  uint32_t w = 0, h = 0;
  uint16_t bps = 8, spp = 3, cfg = PLANARCONFIG_CONTIG, photo = 0;
  TIFFGetField(t, TIFFTAG_IMAGEWIDTH, &w);
  TIFFGetField(t, TIFFTAG_IMAGELENGTH, &h);
  TIFFGetFieldDefaulted(t, TIFFTAG_BITSPERSAMPLE, &bps);
  TIFFGetFieldDefaulted(t, TIFFTAG_SAMPLESPERPIXEL, &spp);
  TIFFGetFieldDefaulted(t, TIFFTAG_PLANARCONFIG, &cfg);
  TIFFGetFieldDefaulted(t, TIFFTAG_PHOTOMETRIC, &photo);
  uint16_t *d = (uint16_t *)malloc((size_t)w * h * 3 * sizeof(uint16_t));
  if (bps == 16 && spp >= 3 && cfg == PLANARCONFIG_CONTIG && photo == PHOTOMETRIC_RGB) {
    uint16_t *line = (uint16_t *)malloc(TIFFScanlineSize(t));
    for (uint32_t y = 0; y < h; y++) {
      if (TIFFReadScanline(t, line, y, 0) < 0) break;
      for (uint32_t x = 0; x < w; x++)
        for (int c = 0; c < 3; c++) d[((size_t)y * w + x) * 3 + c] = line[x * spp + c];
    }
    free(line);
  } else {
    uint32_t *rgba = (uint32_t *)malloc((size_t)w * h * 4);
    if (!TIFFReadRGBAImageOriented(t, w, h, rgba, ORIENTATION_TOPLEFT, 0)) {
      free(rgba);
      free(d);
      TIFFClose(t);
      set_err(err, "unsupported TIFF");
      return NULL;
    }
    for (size_t i = 0; i < (size_t)w * h; i++) {
      d[i * 3] = (uint16_t)(TIFFGetR(rgba[i]) * 257);
      d[i * 3 + 1] = (uint16_t)(TIFFGetG(rgba[i]) * 257);
      d[i * 3 + 2] = (uint16_t)(TIFFGetB(rgba[i]) * 257);
    }
    free(rgba);
  }
  TIFFClose(t);
  return almz_img_wrap((int)w, (int)h, d);
}

AlmzImg *almz_load(const char *path, char **err) {
  FILE *f = fopen(path, "rb");
  if (!f) {
    set_err(err, "cannot open file");
    return NULL;
  }
  uint8_t sig[8] = {0};
  size_t n = fread(sig, 1, 8, f);
  rewind(f);
  AlmzImg *img = NULL;
  if (n >= 3 && sig[0] == 0xFF && sig[1] == 0xD8)
    img = load_jpeg(f, err);
  else if (n >= 8 && !png_sig_cmp(sig, 0, 8))
    img = load_png(f, err);
  else if (n >= 4 && ((sig[0] == 'I' && sig[1] == 'I') || (sig[0] == 'M' && sig[1] == 'M'))) {
    fclose(f);
    return load_tiff(path, err);
  } else
    set_err(err, "unsupported image format");
  fclose(f);
  return img;
}

/* composite one full-resolution row into u16 RGB */
static void composite_row(const AlmzImg *base, const AlmzLayer *layers, int n, int y, uint16_t *row) {
  for (int x = 0; x < base->w; x++) {
    float v[3];
    composite_at(base, layers, n, 0, x + 0.5f, y + 0.5f, 1, v);
    for (int c = 0; c < 3; c++) row[x * 3 + c] = (uint16_t)CLAMP((int)(v[c] + 0.5f), 0, 65535);
  }
}

typedef struct { AlmzImg *dst; const AlmzImg *base; const AlmzLayer *layers; int n; } comp_ctx;

static void comp_rows(void *p, int y0, int y1) {
  const comp_ctx *c = (const comp_ctx *)p;
  for (int y = y0; y < y1; y++)
    composite_row(c->base, c->layers, c->n, y, c->dst->lev[0] + (size_t)y * c->dst->w * 3);
}

void almz_composite_into(AlmzImg *dst, const AlmzImg *base, const AlmzLayer *layers, int n) {
  if (dst->w != base->w || dst->h != base->h) return;
  comp_ctx c = {dst, base, layers, n};
  par_for(base->h, 16, comp_rows, &c);
  almz_img_pyr_update(dst, 0, 0, dst->w, dst->h);
}

/* TIFF: the camera fields as real tags, the EXIF ones in an EXIF directory. */
static void tiff_main_tags(TIFF *t, const AlmzMeta *m) {
  if (m->make[0]) TIFFSetField(t, TIFFTAG_MAKE, m->make);
  if (m->model[0]) TIFFSetField(t, TIFFTAG_MODEL, m->model);
  if (m->orientation > 0) TIFFSetField(t, TIFFTAG_ORIENTATION, (uint16_t)m->orientation);
  TIFFSetField(t, TIFFTAG_SOFTWARE, "Almaz");
}

static int has_exif_fields(const AlmzMeta *m) {
  return m->exposure > 0 || m->fnumber > 0 || m->iso > 0 || m->taken[0] || m->focal > 0 ||
         m->focal35 > 0 || m->lens[0];
}

/* After IFD0 is written: write the EXIF directory and point IFD0 at it. */
static int tiff_exif_dir(TIFF *t, const AlmzMeta *m) {
  uint64_t off = 0;
  if (TIFFCreateEXIFDirectory(t)) return 0;
  if (m->exposure > 0) TIFFSetField(t, EXIFTAG_EXPOSURETIME, m->exposure);
  if (m->fnumber > 0) TIFFSetField(t, EXIFTAG_FNUMBER, m->fnumber);
  if (m->iso > 0 && m->iso < 65536) {
    uint16_t iso = (uint16_t)m->iso;
    TIFFSetField(t, EXIFTAG_ISOSPEEDRATINGS, 1, &iso);
  }
  TIFFSetField(t, EXIFTAG_EXIFVERSION, "0232");
  if (m->taken[0]) TIFFSetField(t, EXIFTAG_DATETIMEORIGINAL, m->taken);
  if (m->focal > 0) TIFFSetField(t, EXIFTAG_FOCALLENGTH, m->focal);
  if (m->focal35 > 0) TIFFSetField(t, EXIFTAG_FOCALLENGTHIN35MMFILM, (uint16_t)m->focal35);
  if (m->lens[0]) TIFFSetField(t, EXIFTAG_LENSMODEL, m->lens);
  if (!TIFFWriteCustomDirectory(t, &off)) return 0;
  if (!TIFFSetDirectory(t, 0)) return 0;
  TIFFSetField(t, TIFFTAG_EXIFIFD, off);
  return 1;
}

int almz_save(const AlmzImg *base, const AlmzLayer *layers, int n,
              const char *path, int format, int quality, const AlmzMeta *meta, char **err) {
  const int w = base->w, h = base->h;
  uint8_t *exif = NULL;
  const size_t exif_len = meta ? almz_exif_build(meta, &exif) : 0;
  uint16_t *row = (uint16_t *)malloc((size_t)w * 3 * sizeof(uint16_t));
  uint8_t *row8 = (uint8_t *)malloc((size_t)w * 3);
  int rc = 0;
  if (format == ALMZ_FMT_JPEG) {
    FILE *f = fopen(path, "wb");
    if (!f) { set_err(err, "cannot write file"); rc = -1; goto done; }
    struct jpeg_compress_struct ci;
    jerr_t je;
    ci.err = jpeg_std_error(&je.mgr);
    je.mgr.error_exit = jerr_exit;
    if (setjmp(je.jb)) {
      jpeg_destroy_compress(&ci);
      fclose(f);
      set_err(err, je.msg);
      rc = -1;
      goto done;
    }
    jpeg_create_compress(&ci);
    jpeg_stdio_dest(&ci, f);
    ci.image_width = w;
    ci.image_height = h;
    ci.input_components = 3;
    ci.in_color_space = JCS_RGB;
    jpeg_set_defaults(&ci);
    jpeg_set_quality(&ci, CLAMP(quality, 1, 100), TRUE);
    jpeg_start_compress(&ci, TRUE);
    if (exif_len && exif_len + 6 <= 65533) {
      uint8_t *app1 = (uint8_t *)malloc(exif_len + 6);
      memcpy(app1, "Exif\0\0", 6);
      memcpy(app1 + 6, exif, exif_len);
      jpeg_write_marker(&ci, JPEG_APP0 + 1, app1, (unsigned)(exif_len + 6));
      free(app1);
    }
    for (int y = 0; y < h; y++) {
      composite_row(base, layers, n, y, row);
      for (int i = 0; i < w * 3; i++) row8[i] = (uint8_t)((row[i] + 128) / 257);
      JSAMPROW r = row8;
      jpeg_write_scanlines(&ci, &r, 1);
    }
    jpeg_finish_compress(&ci);
    jpeg_destroy_compress(&ci);
    fclose(f);
  } else if (format == ALMZ_FMT_PNG16 || format == ALMZ_FMT_PNG8) {
    FILE *f = fopen(path, "wb");
    if (!f) { set_err(err, "cannot write file"); rc = -1; goto done; }
    png_structp p = png_create_write_struct(PNG_LIBPNG_VER_STRING, NULL, NULL, NULL);
    png_infop info = png_create_info_struct(p);
    if (setjmp(png_jmpbuf(p))) {
      png_destroy_write_struct(&p, &info);
      fclose(f);
      set_err(err, "PNG write failed");
      rc = -1;
      goto done;
    }
    png_init_io(p, f);
    const int bits = format == ALMZ_FMT_PNG16 ? 16 : 8;
    png_set_IHDR(p, info, w, h, bits, PNG_COLOR_TYPE_RGB, PNG_INTERLACE_NONE,
                 PNG_COMPRESSION_TYPE_DEFAULT, PNG_FILTER_TYPE_DEFAULT);
    if (exif_len) png_set_eXIf_1(p, info, (png_uint_32)exif_len, exif);
    png_write_info(p, info);
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
    if (bits == 16) png_set_swap(p);
#endif
    for (int y = 0; y < h; y++) {
      composite_row(base, layers, n, y, row);
      if (bits == 16)
        png_write_row(p, (png_bytep)row);
      else {
        for (int i = 0; i < w * 3; i++) row8[i] = (uint8_t)((row[i] + 128) / 257);
        png_write_row(p, row8);
      }
    }
    png_write_end(p, info);
    png_destroy_write_struct(&p, &info);
    fclose(f);
  } else if (format == ALMZ_FMT_TIFF16) {
    TIFF *t = TIFFOpen(path, "w");
    if (!t) { set_err(err, "cannot write file"); rc = -1; goto done; }
    TIFFSetField(t, TIFFTAG_IMAGEWIDTH, w);
    TIFFSetField(t, TIFFTAG_IMAGELENGTH, h);
    TIFFSetField(t, TIFFTAG_SAMPLESPERPIXEL, 3);
    TIFFSetField(t, TIFFTAG_BITSPERSAMPLE, 16);
    TIFFSetField(t, TIFFTAG_PHOTOMETRIC, PHOTOMETRIC_RGB);
    TIFFSetField(t, TIFFTAG_PLANARCONFIG, PLANARCONFIG_CONTIG);
    TIFFSetField(t, TIFFTAG_COMPRESSION, COMPRESSION_ADOBE_DEFLATE);
    TIFFSetField(t, TIFFTAG_ROWSPERSTRIP, TIFFDefaultStripSize(t, 0));
    const int with_exif = meta && has_exif_fields(meta);
    if (meta) tiff_main_tags(t, meta);
    if (with_exif) TIFFSetField(t, TIFFTAG_EXIFIFD, (uint64_t)0); /* placeholder, patched below */
    for (int y = 0; y < h; y++) {
      composite_row(base, layers, n, y, row);
      if (TIFFWriteScanline(t, row, y, 0) < 0) {
        set_err(err, "TIFF write failed");
        rc = -1;
        break;
      }
    }
    if (rc == 0 && with_exif && !(TIFFWriteDirectory(t) && tiff_exif_dir(t, meta))) {
      set_err(err, "TIFF metadata write failed");
      rc = -1;
    }
    TIFFClose(t);
  } else {
    set_err(err, "unknown format");
    rc = -1;
  }
done:
  free(row);
  free(row8);
  free(exif);
  return rc;
}
