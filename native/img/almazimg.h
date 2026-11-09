/* almazimg.h — pixel engine for almaz: 16-bit images with a mip pyramid,
 * low-resolution feathered masks, layer-stack compositing, viewport rendering,
 * tiled-inference helpers, classic filters, estimators and file I/O.
 *
 * Everything that touches every pixel lives here; the Clojure side only
 * orchestrates. All functions are plain C so jolt.ffi binds them directly.
 *
 * License: GPL-2.0-or-later. */
#ifndef ALMAZIMG_H
#define ALMAZIMG_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ALMZ_MAX_LEV 10

/* An RGB image, 16 bits per channel, interleaved, display-encoded (sRGB-ish).
 * lev[0] is full resolution; lev[k] is lev[k-1] box-filtered by 2. */
typedef struct AlmzImg {
  int w, h, nlev;
  int lw[ALMZ_MAX_LEV], lh[ALMZ_MAX_LEV];
  uint16_t *lev[ALMZ_MAX_LEV];
} AlmzImg;

/* A layer mask at 1/div of the image resolution. `paint` holds what the brush
 * wrote (0..1); `soft` is `paint` blurred by the feather radius, and is what
 * compositing samples (bilinearly), so mask edges always blend smoothly. */
typedef struct AlmzMask {
  int iw, ih;        /* image size the mask covers */
  int w, h, div;     /* mask resolution */
  float feather;     /* gaussian sigma, image pixels */
  float *paint;
  float *soft;
} AlmzMask;

/* One entry of a layer stack: the filter's output image, its mask (NULL =
 * everywhere) and opacity. Layer i blends over the composite of layers < i:
 *   c = c + (img - c) * opacity * mask */
typedef struct AlmzLayer {
  const AlmzImg *img;
  const AlmzMask *mask;
  float opacity;
  int32_t enabled;
} AlmzLayer;

/* -- images -------------------------------------------------------------- */
AlmzImg *almz_img_new(int w, int h);                  /* zeroed */
AlmzImg *almz_img_wrap(int w, int h, uint16_t *data); /* takes ownership (malloc'd) */
AlmzImg *almz_img_copy(const AlmzImg *src);
void almz_img_free(AlmzImg *img);
int almz_img_width(const AlmzImg *img);
int almz_img_height(const AlmzImg *img);
/* Rebuild the pyramid over the level-0 rectangle (x, y, w, h). */
void almz_img_pyr_update(AlmzImg *img, int x, int y, int w, int h);
/* Read one level-0 pixel as floats 0..1 (tests, pickers). */
void almz_img_get(const AlmzImg *img, int x, int y, float *rgb);
/* Write the level-0 pixels from interleaved RGB floats 0..1 (tests). */
void almz_img_set_f32(AlmzImg *img, const float *rgb);

/* -- masks --------------------------------------------------------------- */
AlmzMask *almz_mask_new(int img_w, int img_h, int div, float value);
void almz_mask_free(AlmzMask *m);
void almz_mask_fill(AlmzMask *m, float value);
void almz_mask_invert(AlmzMask *m);
void almz_mask_set_feather(AlmzMask *m, float sigma_img_px);
/* Paint (erase = 0) or erase (erase = 1) a soft round stroke from (x0, y0) to
 * (x1, y1) in image pixels. hardness 0..1 is the solid core's share of the
 * radius; flow 0..1 caps the stroke's value. */
void almz_mask_stroke(AlmzMask *m, double x0, double y0, double x1, double y1,
                      double radius, double hardness, double flow, int erase);
/* The largest feathered value over the image rectangle (x, y, w, h). */
float almz_mask_max(const AlmzMask *m, int x, int y, int w, int h);
/* The feathered value at image coordinates (bilinear). */
float almz_mask_sample(const AlmzMask *m, double x, double y);

/* -- rendering ----------------------------------------------------------- */
/* Render the layer stack into the RGBA8 rectangle (rx, ry, rw, rh) of `out`
 * (out_w x out_h, tightly packed). Screen = image * scale + (ox, oy).
 * `overlay` (may be NULL) tints the feathered mask with tint[0..2] at
 * strength tint[3]. Pixels outside the image get `bg` (RGBA). */
void almz_render(const AlmzImg *base, const AlmzLayer *layers, int nlayers,
                 double ox, double oy, double scale,
                 const AlmzMask *overlay, const float *tint,
                 uint8_t *out, int out_w, int out_h,
                 int rx, int ry, int rw, int rh, const uint8_t *bg);

/* Write the full-resolution composite of base + layers into `dst` (same size). */
void almz_composite_into(AlmzImg *dst, const AlmzImg *base, const AlmzLayer *layers, int nlayers);

/* -- tiled inference ----------------------------------------------------- */
/* The composite of base + layers[0..n) over the tile (x0, y0, tw, th) as
 * planar NCHW floats 0..1. Outside the image the edge is mirrored. */
void almz_tile_extract(const AlmzImg *base, const AlmzLayer *layers, int nlayers,
                       int x0, int y0, int tw, int th, float *dst);
/* Blend a planar tile into `dst` at (x0, y0). On each side with a positive
 * ramp the tile fades in over that many pixels (crossfading with what a
 * finished neighbour already wrote); elsewhere it overwrites. Then updates
 * the pyramid under the tile. */
void almz_tile_write(AlmzImg *dst, const float *src, int tw, int th, int x0, int y0,
                     int ramp_l, int ramp_t, int ramp_r, int ramp_b);
/* Classic unsharp mask, in place on a planar tile. */
void almz_tile_unsharp(float *t, int tw, int th, float sigma, float amount);
/* Put back `amount` of the input's fine detail the model removed:
 * out += amount * (highpass(in) - highpass(out)). */
void almz_tile_detail(float *out, const float *in, int tw, int th, float sigma, float amount);

/* -- estimators (autopilot) ---------------------------------------------- */
/* Noise sigma (0..1 units, Immerkaer) of the luminance over a central window
 * of level 0, and a sharpness score (mean gradient energy on edges at level 1,
 * relative to local contrast). */
void almz_estimate(const AlmzImg *img, float *noise, float *sharpness);

/* -- photo metadata (EXIF) ------------------------------------------------ */
/* The shooting metadata almaz carries from a source file to its exports.
 * Empty strings / zero numbers mean "unknown" and are not written. */
typedef struct AlmzMeta {
  char make[64], model[64], lens[128];
  char taken[20];        /* EXIF "YYYY:MM:DD HH:MM:SS" */
  double exposure;       /* seconds */
  double fnumber;
  double focal;          /* mm */
  int32_t iso, focal35, orientation;
} AlmzMeta;

/* Read the metadata of a JPEG (APP1), PNG (eXIf) or any TIFF-structured
 * file (TIFF, NEF, ARW, DNG, CR2, ...). 1 when any field was found. */
int almz_meta_read(const char *path, AlmzMeta *out);
/* Serialize as a little-endian EXIF TIFF structure (no "Exif\0\0" prefix).
 * Returns the length; *out is malloc'd. 0 when there is nothing to write. */
size_t almz_exif_build(const AlmzMeta *m, uint8_t **out);

/* -- file I/O ------------------------------------------------------------ */
enum { ALMZ_FMT_PNG16 = 1, ALMZ_FMT_PNG8, ALMZ_FMT_JPEG, ALMZ_FMT_TIFF16 };
/* Load a JPEG / PNG / TIFF (by content). NULL on error (*err malloc'd). */
AlmzImg *almz_load(const char *path, char **err);
AlmzImg *almz_load_jpeg_mem(const uint8_t *data, size_t len, char **err);
AlmzImg *almz_from_rgb8(const uint8_t *rgb, int w, int h);
/* Write the composite of base + layers at full resolution, streaming rows,
 * with `meta` (may be NULL) as EXIF. quality is JPEG quality 1..100. 0 on
 * success. */
int almz_save(const AlmzImg *base, const AlmzLayer *layers, int nlayers,
              const char *path, int format, int quality, const AlmzMeta *meta, char **err);
void almz_free_string(char *s);

#ifdef __cplusplus
}
#endif
#endif
