#pragma once
// One handle owns a GLES2 context's immediate-mode drawing state. The caller
// supplies a current context, viewport and framebuffer for every operation.
#ifdef __cplusplus
extern "C" {
#endif
typedef struct LumenGL LumenGL;
LumenGL *lumenGLCreate(void);
void lumenGLDestroy(LumenGL *);
void lumenGLMakeCurrent(LumenGL *);
void *lumenGLGetProcAddress(const char *);
#ifdef __cplusplus
}
#endif
