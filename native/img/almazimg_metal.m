/* almazimg_metal.m — almz_render on the GPU (Metal), macOS.
 *
 * The same composite as almazimg.c's composite_at, one compute thread per
 * output pixel: the screen -> source transform, the base and layer images
 * sampled at the same pyramid level (bilinear, or nearest when magnified),
 * each layer's mask, curves, saturation, colour cube and radial weight, the
 * mask overlay and the clip. Exports, histograms and tiles stay on the CPU
 * (full resolution, exact); the GPU draws what's on screen.
 *
 * GPU copies are cached: an image's visible region at the level drawn (with
 * a margin, so small pans reuse it), and whole masks, keyed by the uid and
 * refreshed when the rev moves (almazimg.c bumps it on every pixel change).
 * Anything this path can't do returns 0 and the CPU draws instead.
 *
 * License: GPL-2.0-or-later. */
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <dispatch/dispatch.h>
#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "almazimg.h"

#define MAXL 16
#define TONE_N ALMZ_TONE_N

/* shared with the shader: 4-byte fields only, so C and MSL lay them out alike */
typedef struct {
  float xf[6];
  float clip[4];
  int rx, ry, rw, rh;
  int nlayers, nearest, has_overlay, luma_alpha;
  float tint[4];
  float bg[4];
  float base_scale, base_ox, base_oy, ov_div;
} Params;

typedef struct {
  int has_img, has_mask, has_lut, has_cube;
  float opacity, sat, mask_div, img_scale;
  float img_ox, img_oy, cube_n, has_radial;
  float radial[6];
  float pad[2];
} LayerP;

static NSString *const kShader = @"#include <metal_stdlib>\n"
"using namespace metal;\n"
"struct Params { float xf[6]; float clip[4]; int rx, ry, rw, rh; int nlayers, nearest, has_overlay, luma_alpha;\n"
"  float tint[4]; float bg[4]; float base_scale, base_ox, base_oy, ov_div; };\n"
"struct LayerP { int has_img, has_mask, has_lut, has_cube; float opacity, sat, mask_div, img_scale;\n"
"  float img_ox, img_oy, cube_n, has_radial; float radial[6]; float pad[2]; };\n"
"constexpr sampler lin(coord::pixel, filter::linear, address::clamp_to_edge);\n"
"constexpr sampler nea(coord::pixel, filter::nearest, address::clamp_to_edge);\n"
"constexpr sampler cub(coord::normalized, filter::linear, address::clamp_to_edge);\n"
"static float lut_at(device const float *lut, float v) {\n"
"  float f = clamp(v / 65535.0f, 0.0f, 1.0f) * 4095.0f;\n"
"  int i = min(int(f), 4094); float fr = f - float(i);\n"
"  return (lut[i] + (lut[i + 1] - lut[i]) * fr) * 65535.0f; }\n"
"static float radial_at(constant float *r, float ix, float iy) {\n"
"  float dx = (ix - r[0]) / r[2], dy = (iy - r[1]) / r[3];\n"
"  float d = sqrt(dx * dx + dy * dy);\n"
"  if (d <= r[4]) return 0.0f;\n"
"  float t = (d - r[4]) / max(r[5], 1e-4f);\n"
"  return t >= 1.0f ? 1.0f : 0.5f - 0.5f * cos(M_PI_F * t); }\n"
"kernel void render(texture2d<float> base [[texture(0)]], texture2d<float> overlay [[texture(1)]],\n"
"  array<texture2d<float>, 16> imgs [[texture(2)]], array<texture2d<float>, 16> masks [[texture(18)]],\n"
"  array<texture3d<float>, 16> cubes [[texture(34)]], constant Params &p [[buffer(0)]],\n"
"  constant LayerP *L [[buffer(1)]], device const float *luts [[buffer(2)]],\n"
"  device uchar4 *out [[buffer(3)]], uint2 gid [[thread_position_in_grid]]) {\n"
"  if (int(gid.x) >= p.rw || int(gid.y) >= p.rh) return;\n"
"  float sx = float(p.rx + int(gid.x)) + 0.5f, sy = float(p.ry + int(gid.y)) + 0.5f;\n"
"  float ix = p.xf[0] * sx + p.xf[1] * sy + p.xf[2], iy = p.xf[3] * sx + p.xf[4] * sy + p.xf[5];\n"
"  uint o = gid.y * uint(p.rw) + gid.x;\n"
"  if (ix < p.clip[0] || iy < p.clip[1] || ix >= p.clip[2] || iy >= p.clip[3]) {\n"
"    out[o] = uchar4(uchar(p.bg[0]), uchar(p.bg[1]), uchar(p.bg[2]), uchar(p.bg[3])); return; }\n"
"  float2 bc = float2(ix / p.base_scale - p.base_ox, iy / p.base_scale - p.base_oy);\n"
"  float3 c = (p.nearest ? base.sample(nea, bc) : base.sample(lin, bc)).rgb * 65535.0f;\n"
"  for (int i = 0; i < p.nlayers; i++) {\n"
"    constant LayerP &l = L[i];\n"
"    float a = l.opacity;\n"
"    if (l.has_mask) a *= masks[i].sample(lin, float2(ix / l.mask_div, iy / l.mask_div)).r;\n"
"    if (l.has_radial > 0.5f) a *= radial_at(l.radial, ix, iy);\n"
"    if (a <= 0.0f) continue;\n"
"    float3 v;\n"
"    if (l.has_img) {\n"
"      float2 ic = float2(ix / l.img_scale - l.img_ox, iy / l.img_scale - l.img_oy);\n"
"      v = (p.nearest ? imgs[i].sample(nea, ic) : imgs[i].sample(lin, ic)).rgb * 65535.0f;\n"
"    } else {\n"
"      v = c;\n"
"      if (l.has_lut) { device const float *lt = luts + i * 3 * 4096;\n"
"        v = float3(lut_at(lt, c.r), lut_at(lt + 4096, c.g), lut_at(lt + 8192, c.b)); }\n"
"      if (l.sat != 0.0f) { float y = 0.2126f * v.r + 0.7152f * v.g + 0.0722f * v.b;\n"
"        v = clamp(y + (v - y) * (1.0f + l.sat), 0.0f, 65535.0f); }\n"
"      if (l.has_cube) { float n = l.cube_n;\n"
"        float3 q = (clamp(v / 65535.0f, 0.0f, 1.0f) * (n - 1.0f) + 0.5f) / n;\n"
"        v = clamp(cubes[i].sample(cub, q).rgb, 0.0f, 1.0f) * 65535.0f; }\n"
"    }\n"
"    c += (v - c) * a;\n"
"  }\n"
"  if (p.has_overlay) { float a = p.tint[3] * overlay.sample(lin, float2(ix / p.ov_div, iy / p.ov_div)).r;\n"
"    c = c * (1.0f - a) + float3(p.tint[0], p.tint[1], p.tint[2]) * 65535.0f * a; }\n"
"  int3 b = clamp(int3(c / 257.0f + 0.5f), 0, 255);\n"
"  int al = p.luma_alpha ? clamp(int((0.2126f * c.r + 0.7152f * c.g + 0.0722f * c.b) / 257.0f + 0.5f), 0, 255) : 255;\n"
"  out[o] = uchar4(uchar(b.x), uchar(b.y), uchar(b.z), uchar(al)); }\n";

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int enabled = -1; /* -1: not decided yet (ALMAZ_GPU=0 turns it off) */
static int ready = 0;    /* 1 set up, -1 unavailable */
static id<MTLDevice> dev;
static id<MTLCommandQueue> queue;
static id<MTLComputePipelineState> pso;
static id<MTLTexture> dummy2d, dummy3d;
static NSMutableDictionary<NSString *, NSDictionary *> *cache; /* "uid:level" -> entry */
static id<MTLTexture> cube_tex[MAXL];
static id<MTLBuffer> lut_buf, layer_buf, out_buf;

static int setup(void) {
  if (ready) return ready;
  ready = -1;
  dev = MTLCreateSystemDefaultDevice();
  /* masks and cubes are float textures sampled bilinearly */
  if (!dev || !dev.supports32BitFloatFiltering) return ready;
  NSError *err = nil;
  id<MTLLibrary> lib = [dev newLibraryWithSource:kShader options:nil error:&err];
  id<MTLFunction> fn = lib ? [lib newFunctionWithName:@"render"] : nil;
  pso = fn ? [dev newComputePipelineStateWithFunction:fn error:&err] : nil;
  if (!pso) {
    NSLog(@"almaz: GPU renderer unavailable: %@", err);
    return ready;
  }
  queue = [dev newCommandQueue];
  MTLTextureDescriptor *d2 = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Unorm
                                                                                width:1 height:1 mipmapped:NO];
  dummy2d = [dev newTextureWithDescriptor:d2];
  MTLTextureDescriptor *d3 = [MTLTextureDescriptor new];
  d3.textureType = MTLTextureType3D;
  d3.pixelFormat = MTLPixelFormatRGBA32Float;
  d3.width = d3.height = d3.depth = 1;
  dummy3d = [dev newTextureWithDescriptor:d3];
  cache = [NSMutableDictionary new];
  lut_buf = [dev newBufferWithLength:sizeof(float) * MAXL * 3 * TONE_N options:MTLResourceStorageModeShared];
  layer_buf = [dev newBufferWithLength:sizeof(LayerP) * MAXL options:MTLResourceStorageModeShared];
  ready = 1;
  return ready;
}

void almz_gpu_set_enabled(int on) {
  pthread_mutex_lock(&lock);
  enabled = on ? 1 : 0;
  pthread_mutex_unlock(&lock);
}

static int is_enabled(void) {
  if (enabled < 0) {
    const char *e = getenv("ALMAZ_GPU");
    enabled = !(e && strcmp(e, "0") == 0);
  }
  return enabled;
}

int almz_gpu_active(void) {
  pthread_mutex_lock(&lock);
  int r;
  @autoreleasepool { r = is_enabled() && setup() == 1; }
  pthread_mutex_unlock(&lock);
  return r;
}

void almz_gpu_forget(uint64_t uid) {
  pthread_mutex_lock(&lock);
  @autoreleasepool {
    if (cache) {
      NSString *prefix = [NSString stringWithFormat:@"%llu:", (unsigned long long)uid];
      for (NSString *k in [cache allKeys])
        if ([k hasPrefix:prefix]) [cache removeObjectForKey:k];
    }
  }
  pthread_mutex_unlock(&lock);
}

/* ---- images ---------------------------------------------------------------- */
typedef struct { int x0, y0, x1, y1; } box;

/* An image's region `need` (level-k pixels) as an RGBA16 texture, from the
 * cache when it covers it at the current rev; its origin goes to ox, oy. */
static id<MTLTexture> image_region(const AlmzImg *img, int k, box need, float *ox, float *oy) {
  NSString *key = [NSString stringWithFormat:@"%llu:%d", (unsigned long long)img->uid, k];
  NSDictionary *e = cache[key];
  const uint64_t rev = __atomic_load_n(&img->rev, __ATOMIC_RELAXED);
  if (e && [e[@"rev"] unsignedLongLongValue] == rev && [e[@"x0"] intValue] <= need.x0 &&
      [e[@"y0"] intValue] <= need.y0 && [e[@"x1"] intValue] >= need.x1 && [e[@"y1"] intValue] >= need.y1) {
    *ox = [e[@"x0"] floatValue];
    *oy = [e[@"y0"] floatValue];
    return e[@"tex"];
  }
  const int lw = img->lw[k], lh = img->lh[k];
  /* a quarter of the region's size around it: small pans reuse the copy */
  const int mx = (need.x1 - need.x0) / 4 + 2, my = (need.y1 - need.y0) / 4 + 2;
  box r = {MAX(0, need.x0 - mx), MAX(0, need.y0 - my), MIN(lw, need.x1 + mx), MIN(lh, need.y1 + my)};
  const int w = MAX(1, r.x1 - r.x0), h = MAX(1, r.y1 - r.y0);
  MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Unorm
                                                                               width:w height:h mipmapped:NO];
  d.usage = MTLTextureUsageShaderRead;
  d.storageMode = MTLStorageModeShared;
  id<MTLTexture> tex = [dev newTextureWithDescriptor:d];
  if (!tex) return nil;
  uint16_t *rgba = (uint16_t *)malloc((size_t)w * h * 4 * sizeof(uint16_t));
  if (!rgba) return nil;
  const uint16_t *src = img->lev[k];
  dispatch_apply((size_t)h, dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^(size_t y) {
    const uint16_t *s = src + ((size_t)(r.y0 + y) * lw + r.x0) * 3;
    uint16_t *o = rgba + (size_t)y * w * 4;
    for (int x = 0; x < w; x++, s += 3, o += 4) {
      o[0] = s[0];
      o[1] = s[1];
      o[2] = s[2];
      o[3] = 65535;
    }
  });
  [tex replaceRegion:MTLRegionMake2D(0, 0, w, h) mipmapLevel:0 withBytes:rgba bytesPerRow:(NSUInteger)w * 8];
  free(rgba);
  cache[key] = @{@"tex" : tex, @"rev" : @(rev), @"x0" : @(r.x0), @"y0" : @(r.y0), @"x1" : @(r.x0 + w),
                 @"y1" : @(r.y0 + h)};
  *ox = (float)r.x0;
  *oy = (float)r.y0;
  return tex;
}

static id<MTLTexture> mask_texture(const AlmzMask *m) {
  NSString *key = [NSString stringWithFormat:@"%llu:m", (unsigned long long)m->uid];
  NSDictionary *e = cache[key];
  const uint64_t rev = __atomic_load_n(&m->rev, __ATOMIC_RELAXED);
  if (e && [e[@"rev"] unsignedLongLongValue] == rev) return e[@"tex"];
  MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float
                                                                               width:m->w height:m->h mipmapped:NO];
  d.usage = MTLTextureUsageShaderRead;
  d.storageMode = MTLStorageModeShared;
  id<MTLTexture> tex = [dev newTextureWithDescriptor:d];
  if (!tex) return nil;
  [tex replaceRegion:MTLRegionMake2D(0, 0, m->w, m->h) mipmapLevel:0 withBytes:m->soft
         bytesPerRow:(NSUInteger)m->w * sizeof(float)];
  cache[key] = @{@"tex" : tex, @"rev" : @(rev)};
  return tex;
}

static id<MTLTexture> cube_texture(int slot, const float *rgb, int n) {
  id<MTLTexture> t = cube_tex[slot];
  if (!t || (int)t.width != n) {
    MTLTextureDescriptor *d = [MTLTextureDescriptor new];
    d.textureType = MTLTextureType3D;
    d.pixelFormat = MTLPixelFormatRGBA32Float;
    d.width = d.height = d.depth = n;
    d.usage = MTLTextureUsageShaderRead;
    d.storageMode = MTLStorageModeShared;
    t = cube_tex[slot] = [dev newTextureWithDescriptor:d];
    if (!t) return nil;
  }
  const size_t cnt = (size_t)n * n * n;
  float *rgba = (float *)malloc(cnt * 4 * sizeof(float));
  if (!rgba) return nil;
  for (size_t i = 0; i < cnt; i++) {
    rgba[i * 4] = rgb[i * 3];
    rgba[i * 4 + 1] = rgb[i * 3 + 1];
    rgba[i * 4 + 2] = rgb[i * 3 + 2];
    rgba[i * 4 + 3] = 1.0f;
  }
  [t replaceRegion:MTLRegionMake3D(0, 0, 0, n, n, n) mipmapLevel:0 slice:0 withBytes:rgba
       bytesPerRow:(NSUInteger)n * 16 bytesPerImage:(NSUInteger)n * n * 16];
  free(rgba);
  return t;
}

/* ---- render ---------------------------------------------------------------- */
/* The level-k pixels the rect (rx, ry, rw, rh) shows, within the clip. */
static box needed(const AlmzImg *img, const double *xf, const float *clip, int rx, int ry, int rw, int rh, int k) {
  double xs[4] = {rx, rx + rw, rx, rx + rw}, ys[4] = {ry, ry, ry + rh, ry + rh};
  double x0 = 1e30, y0 = 1e30, x1 = -1e30, y1 = -1e30;
  for (int i = 0; i < 4; i++) {
    const double ix = xf[0] * xs[i] + xf[1] * ys[i] + xf[2], iy = xf[3] * xs[i] + xf[4] * ys[i] + xf[5];
    x0 = fmin(x0, ix), y0 = fmin(y0, iy), x1 = fmax(x1, ix), y1 = fmax(y1, iy);
  }
  x0 = fmax(x0, clip[0]), y0 = fmax(y0, clip[1]), x1 = fmin(x1, clip[2]), y1 = fmin(y1, clip[3]);
  const double s = (double)(1 << k);
  box b = {(int)floor(x0 / s) - 2, (int)floor(y0 / s) - 2, (int)ceil(x1 / s) + 2, (int)ceil(y1 / s) + 2};
  b.x0 = MAX(0, MIN(b.x0, img->lw[k] - 1));
  b.y0 = MAX(0, MIN(b.y0, img->lh[k] - 1));
  b.x1 = MAX(b.x0 + 1, MIN(b.x1, img->lw[k]));
  b.y1 = MAX(b.y0 + 1, MIN(b.y1, img->lh[k]));
  return b;
}

static int render_locked(const AlmzImg *base, const AlmzLayer *layers, int nlayers, const double *xf,
                         const int32_t *clip, const AlmzMask *overlay, const float *tint, uint8_t *out, int out_w,
                         int rx, int ry, int rw, int rh, const uint8_t *bg, int k, int nearest, int luma_alpha) {
  Params p;
  memset(&p, 0, sizeof p);
  for (int i = 0; i < 6; i++) p.xf[i] = (float)xf[i];
  p.clip[0] = 0, p.clip[1] = 0, p.clip[2] = (float)base->w, p.clip[3] = (float)base->h;
  if (clip) {
    p.clip[0] = fmaxf(0, (float)clip[0]);
    p.clip[1] = fmaxf(0, (float)clip[1]);
    p.clip[2] = fminf((float)base->w, (float)(clip[0] + clip[2]));
    p.clip[3] = fminf((float)base->h, (float)(clip[1] + clip[3]));
  }
  p.rx = rx, p.ry = ry, p.rw = rw, p.rh = rh;
  p.nearest = nearest;
  p.luma_alpha = luma_alpha;
  for (int i = 0; i < 4; i++) p.bg[i] = bg ? bg[i] : (i == 3 ? 255 : 0);
  if (tint) memcpy(p.tint, tint, sizeof p.tint);
  p.base_scale = (float)(1 << k);

  const box need = needed(base, xf, p.clip, rx, ry, rw, rh, k);
  id<MTLTexture> base_tex = image_region(base, k, need, &p.base_ox, &p.base_oy);
  if (!base_tex) return 0;

  id<MTLTexture> imgs[MAXL], masks[MAXL], cubes[MAXL];
  LayerP *lp = (LayerP *)layer_buf.contents;
  float *luts = (float *)lut_buf.contents;
  int n = 0;
  for (int i = 0; i < nlayers; i++) {
    const AlmzLayer *L = &layers[i];
    const int adjust = L->lut || L->cube || L->saturation != 0.0f;
    if (!L->enabled || !(L->img || adjust) || L->opacity <= 0) continue;
    if (n == MAXL) return 0;
    LayerP *l = &lp[n];
    memset(l, 0, sizeof *l);
    l->opacity = L->opacity;
    imgs[n] = dummy2d;
    masks[n] = dummy2d;
    cubes[n] = dummy3d;
    if (L->img) {
      const int lk = MIN(k, L->img->nlev - 1);
      l->has_img = 1;
      l->img_scale = (float)(1 << lk);
      imgs[n] = image_region(L->img, lk, needed(L->img, xf, p.clip, rx, ry, rw, rh, lk), &l->img_ox, &l->img_oy);
      if (!imgs[n]) return 0;
    } else {
      l->sat = L->saturation;
      if (L->lut) {
        l->has_lut = 1;
        memcpy(luts + (size_t)n * 3 * TONE_N, L->lut, sizeof(float) * 3 * TONE_N);
      }
      if (L->cube && L->cube_n >= 2) {
        l->has_cube = 1;
        l->cube_n = (float)L->cube_n;
        cubes[n] = cube_texture(n, L->cube, L->cube_n);
        if (!cubes[n]) return 0;
      }
      if (L->radial[2] > 0.0f) {
        l->has_radial = 1;
        memcpy(l->radial, L->radial, sizeof l->radial);
      }
    }
    if (L->mask) {
      l->has_mask = 1;
      l->mask_div = (float)L->mask->div;
      masks[n] = mask_texture(L->mask);
      if (!masks[n]) return 0;
    }
    n++;
  }
  p.nlayers = n;
  id<MTLTexture> ov = dummy2d;
  if (overlay) {
    p.has_overlay = 1;
    p.ov_div = (float)overlay->div;
    ov = mask_texture(overlay);
    if (!ov) return 0;
  }
  for (int i = n; i < MAXL; i++) imgs[i] = masks[i] = dummy2d, cubes[i] = dummy3d;

  const NSUInteger out_len = (NSUInteger)rw * rh * 4;
  if (!out_buf || out_buf.length < out_len)
    out_buf = [dev newBufferWithLength:out_len options:MTLResourceStorageModeShared];
  if (!out_buf) return 0;

  id<MTLCommandBuffer> cb = [queue commandBuffer];
  id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
  [enc setComputePipelineState:pso];
  [enc setTexture:base_tex atIndex:0];
  [enc setTexture:ov atIndex:1];
  [enc setTextures:imgs withRange:NSMakeRange(2, MAXL)];
  [enc setTextures:masks withRange:NSMakeRange(18, MAXL)];
  [enc setTextures:cubes withRange:NSMakeRange(34, MAXL)];
  [enc setBytes:&p length:sizeof p atIndex:0];
  [enc setBuffer:layer_buf offset:0 atIndex:1];
  [enc setBuffer:lut_buf offset:0 atIndex:2];
  [enc setBuffer:out_buf offset:0 atIndex:3];
  const NSUInteger tw = pso.threadExecutionWidth, th = MAX(1, pso.maxTotalThreadsPerThreadgroup / tw);
  [enc dispatchThreads:MTLSizeMake(rw, rh, 1) threadsPerThreadgroup:MTLSizeMake(tw, MIN(th, 16), 1)];
  [enc endEncoding];
  [cb commit];
  [cb waitUntilCompleted];
  if (cb.status != MTLCommandBufferStatusCompleted) return 0;
  const uint8_t *src = (const uint8_t *)out_buf.contents;
  for (int y = 0; y < rh; y++)
    memcpy(out + ((size_t)(ry + y) * out_w + rx) * 4, src + (size_t)y * rw * 4, (size_t)rw * 4);
  return 1;
}

int almz_gpu_render(const AlmzImg *base, const AlmzLayer *layers, int nlayers, const double *xf,
                    const int32_t *clip, const AlmzMask *overlay, const float *tint, uint8_t *out, int out_w,
                    int out_h, int rx, int ry, int rw, int rh, const uint8_t *bg, int level, int nearest,
                    int luma_alpha) {
  (void)out_h;
  int ok = 0;
  pthread_mutex_lock(&lock);
  @autoreleasepool {
    if (is_enabled() && setup() == 1)
      ok = render_locked(base, layers, nlayers, xf, clip, overlay, tint, out, out_w, rx, ry, rw, rh, bg, level,
                         nearest, luma_alpha);
  }
  pthread_mutex_unlock(&lock);
  return ok;
}
