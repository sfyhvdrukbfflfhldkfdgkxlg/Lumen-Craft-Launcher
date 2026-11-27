#pragma once
// One handle owns a GLES2/3 context's fixed-function compatibility state,
// matrix stacks, display lists, alpha/fog emulation, and GLSL translator.
// The caller supplies a current EGL/GLES context, viewport, and framebuffer.
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif
typedef struct LumenGL LumenGL;
LumenGL *lumenGLCreate(void);
void lumenGLDestroy(LumenGL *);
void lumenGLMakeCurrent(LumenGL *);
void *lumenGLGetProcAddress(const char *);
intptr_t lumenGLTranslateShader(const char *src, size_t srcLen, unsigned targetEs,
                                unsigned stage, char *out, size_t outCap);
#ifdef __cplusplus
}
#endif
