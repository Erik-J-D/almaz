#ifndef ALMAZINFER_RAW_H
#define ALMAZINFER_RAW_H

#include <stddef.h>
#include <stdint.h>

#include "../img/almazimg.h" /* AlmzMeta */

#ifdef __cplusplus
extern "C" {
#endif

/* Decode a RAW file (NEF/CR3/ARW/...) to 16-bit sRGB-encoded RGB, interleaved,
   demosaiced (AHD) at full resolution, camera white balance. `half_size` (0/1)
   instead bins each 2x2 Bayer quad (fast, but every channel's noise is then
   independent per pixel, which restoration models trained on demosaiced
   images do not expect: use it for previews only).
   On success *data is malloc'd (free with almz_raw_free) and 0 is returned;
   on error -1 and *err is a malloc'd message. */
int almz_raw_decode16(const char* path, int half_size,
                      int* width, int* height, uint16_t** data, char** err);

/* The camera's embedded preview. *format is 1 for JPEG bytes (*len bytes) or
   2 for 8-bit RGB (*width x *height). 0 on success. Free *data with almz_raw_free. */
int almz_raw_thumb(const char* path, int* format, int* width, int* height,
                   uint8_t** data, size_t* len, char** err);

/* LibRaw's decoded shooting metadata (camera, lens, exposure, aperture, ISO,
   focal length, capture time), for RAW formats whose EXIF almz_meta_read
   cannot reach (CR3, RAF, ...). 1 on success. */
int almz_raw_meta(const char* path, AlmzMeta* out);

/* Is this a file LibRaw can open (header only)? 1/0. */
int almz_raw_probe(const char* path);

void almz_raw_free(void* p);
void almz_raw_free_string(char* s);

#ifdef __cplusplus
}
#endif

#endif
