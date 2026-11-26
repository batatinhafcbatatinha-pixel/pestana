// C interface of the Rust core (core/src/ffi.rs).
#pragma once
#include <stdbool.h>
#include <stdint.h>

typedef struct PestanaDetector PestanaDetector;

/// Every returned string must be released with pestana_free_string.
void pestana_free_string(char *s);
char *pestana_extract_page(const char *html, const char *url);
char *pestana_playlist_links(const char *html, const char *url);
char *pestana_render(const char *text, const char *key, int32_t transpose, bool simplify,
                     int32_t view, bool show_tabs, int32_t accidentals, int32_t cols);
char *pestana_chord_shape(const char *name);
char *pestana_bass_shape(const char *name);

PestanaDetector *pestana_detector_new(void);
void pestana_detector_free(PestanaDetector *d);
/// Writes [frequency (-1 if none), clarity 0..1, rms] into out[0..3].
void pestana_detect(PestanaDetector *d, const float *samples, int32_t count, int32_t sample_rate,
                    float min_hz, float max_hz, float *out);
