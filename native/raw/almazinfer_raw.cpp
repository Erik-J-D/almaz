// almazinfer_raw.cpp — a thin C++ shim over LibRaw exposing RAW -> 16-bit RGB
// decoding and embedded-thumbnail extraction behind a plain C API so jolt.ffi
// can bind it directly.
//
// Build against LibRaw's static reentrant archive (see build.sh); the Nikon HE
// decoder (Z9/Z8/Zf/Z6 III) is upstream in the 202611 snapshot.
//
// License: GPL-2.0-or-later (LibRaw is dual LGPL/CDDL; this shim is GPL).

#include "libraw/libraw.h"

#include "almazinfer_raw.h"

#include <cmath>
#include <cstdlib>
#include <cstring>
#include <ctime>

static char* almz_raw_strdup(const char* s) {
  char* out = (char*)malloc(strlen(s) + 1);
  strcpy(out, s);
  return out;
}

void almz_raw_free_string(char* s) { free(s); }
void almz_raw_free(void* p) { free(p); }

static int fail(libraw_data_t* d, char** err, const char* msg) {
  if (err) *err = almz_raw_strdup(msg);
  if (d) libraw_close(d);
  return -1;
}

static libraw_data_t* open_raw(const char* path, char** err) {
  libraw_data_t* d = libraw_init(0);
  if (!d) {
    fail(nullptr, err, "libraw_init failed");
    return nullptr;
  }
  int ret = libraw_open_file(d, path);
  if (ret != LIBRAW_SUCCESS) {
    fail(d, err, libraw_strerror(ret));
    return nullptr;
  }
  return d;
}

int almz_raw_probe(const char* path) {
  libraw_data_t* d = open_raw(path, nullptr);
  if (!d) return 0;
  libraw_close(d);
  return 1;
}

int almz_raw_decode16(const char* path, int half_size, int* width, int* height,
                      uint16_t** data, char** err) {
  libraw_data_t* d = open_raw(path, err);
  if (!d) return -1;
  int ret = libraw_unpack(d);
  if (ret != LIBRAW_SUCCESS) return fail(d, err, libraw_strerror(ret));

  d->params.output_bps = 16;
  d->params.use_camera_wb = 1;
  d->params.half_size = half_size ? 1 : 0;
  d->params.user_qual = 3;          // AHD
  d->params.gamm[0] = 1.0 / 2.4;    // sRGB transfer curve
  d->params.gamm[1] = 12.92;
  // No automatic brightening: LibRaw otherwise rescales every image so 1% of
  // its pixels clip. Brightness is the user's call (a Light layer).
  d->params.no_auto_bright = 1;

  ret = libraw_dcraw_process(d);
  if (ret != LIBRAW_SUCCESS) return fail(d, err, libraw_strerror(ret));

  libraw_processed_image_t* img = libraw_dcraw_make_mem_image(d, &ret);
  if (!img || ret != LIBRAW_SUCCESS) return fail(d, err, "make_mem_image failed");
  if (img->colors != 3) {
    libraw_dcraw_clear_mem(img);
    return fail(d, err, "not an RGB image");
  }

  const size_t n = (size_t)img->width * img->height * 3;
  uint16_t* out = (uint16_t*)malloc(n * sizeof(uint16_t));
  if (img->bits == 8) {
    const unsigned char* s = img->data;
    for (size_t i = 0; i < n; i++) out[i] = (uint16_t)(s[i] * 257);
  } else {
    memcpy(out, img->data, n * sizeof(uint16_t));
  }
  *width = img->width;
  *height = img->height;
  *data = out;
  libraw_dcraw_clear_mem(img);
  libraw_close(d);
  return 0;
}

int almz_raw_thumb(const char* path, int* format, int* width, int* height,
                   uint8_t** data, size_t* len, char** err) {
  libraw_data_t* d = open_raw(path, err);
  if (!d) return -1;
  int ret = libraw_unpack_thumb(d);
  if (ret != LIBRAW_SUCCESS) return fail(d, err, libraw_strerror(ret));
  libraw_processed_image_t* t = libraw_dcraw_make_mem_thumb(d, &ret);
  if (!t || ret != LIBRAW_SUCCESS) return fail(d, err, "no embedded thumbnail");
  if (t->type == LIBRAW_IMAGE_JPEG) {
    *format = 1;
  } else if (t->type == LIBRAW_IMAGE_BITMAP && t->colors == 3 && t->bits == 8) {
    *format = 2;
  } else {
    libraw_dcraw_clear_mem(t);
    return fail(d, err, "unsupported thumbnail format");
  }
  *width = t->width;
  *height = t->height;
  *len = t->data_size;
  *data = (uint8_t*)malloc(t->data_size);
  memcpy(*data, t->data, t->data_size);
  libraw_dcraw_clear_mem(t);
  libraw_close(d);
  return 0;
}

int almz_raw_meta(const char* path, AlmzMeta* out) {
  memset(out, 0, sizeof *out);
  libraw_data_t* d = open_raw(path, nullptr);
  if (!d) return 0;
  snprintf(out->make, sizeof out->make, "%s", d->idata.make);
  snprintf(out->model, sizeof out->model, "%s", d->idata.model);
  snprintf(out->lens, sizeof out->lens, "%s", d->lens.Lens);
  out->exposure = d->other.shutter;
  out->fnumber = std::round(d->other.aperture * 10.0) / 10.0;
  out->focal = d->other.focal_len;
  out->iso = (int32_t)std::lround(d->other.iso_speed);
  out->focal35 = d->lens.FocalLengthIn35mmFormat;
  out->orientation = 1;  // dcraw_process applies the camera's rotation
  if (d->other.timestamp) {
    // LibRaw turns the camera's local EXIF time into time_t with mktime
    time_t t = d->other.timestamp;
    struct tm tm;
    localtime_r(&t, &tm);
    strftime(out->taken, sizeof out->taken, "%Y:%m:%d %H:%M:%S", &tm);
  }
  libraw_close(d);
  return 1;
}
