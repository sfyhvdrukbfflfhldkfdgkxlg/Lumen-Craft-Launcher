#include "../../graphics/legacy.h"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <stdio.h>
#include <string.h>

// Run against the same shared object shipped in the APK, on an EGL-capable Android device.
int main() {
    EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    const EGLint config[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                             EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
    EGLConfig cfg;
    EGLint count = 0;
    if (!eglInitialize(d, nullptr, nullptr) || !eglChooseConfig(d, config, &cfg, 1, &count) || count < 1) return 1;
    const EGLint pixels[] = {EGL_WIDTH, 8, EGL_HEIGHT, 8, EGL_NONE}, es2[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(d, cfg, pixels);
    EGLContext context = eglCreateContext(d, cfg, EGL_NO_CONTEXT, es2);
    if (!eglMakeCurrent(d, surface, surface, context)) return 2;
    auto *bridge = lumenGLCreate();
    if (!bridge) return 3;
    lumenGLMakeCurrent(bridge);
    auto begin = (void (*)(unsigned))lumenGLGetProcAddress("glBegin");
    auto end = (void (*)())lumenGLGetProcAddress("glEnd");
    auto color = (void (*)(float, float, float, float))lumenGLGetProcAddress("glColor4f");
    auto vertex = (void (*)(float, float, float))lumenGLGetProcAddress("glVertex3f");
    auto error = (unsigned (*)())lumenGLGetProcAddress("glGetError");
    auto enable = (void (*)(unsigned))lumenGLGetProcAddress("glEnable");
    auto disable = (void (*)(unsigned))lumenGLGetProcAddress("glDisable");
    auto alphaFunc = (void (*)(unsigned, float))lumenGLGetProcAddress("glAlphaFunc");
    glViewport(0, 0, 8, 8);
    unsigned modes[] = {7, 7, 8, 9, 7};
    for (unsigned mode : modes) {
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        begin(mode); color(0, 1, 0, 1);
        vertex(-1, -1, 0); vertex(1, -1, 0);
        if (mode == 8) { vertex(-1, 1, 0); vertex(1, 1, 0); }
        else { vertex(1, 1, 0); vertex(-1, 1, 0); }
        end();
        unsigned char sample[4] = {};
        glReadPixels(4, 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, sample);
        if (error() != GL_NO_ERROR || sample[0] != 0 || sample[1] != 255 || sample[2] != 0) return 4;
        printf("mode=%u pixel=%u,%u,%u\n", mode, sample[0], sample[1], sample[2]);
    }
    // Alpha test discard check
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    enable(0x0BC0);
    alphaFunc(GL_GREATER, 0.5f);
    begin(7); color(1, 0, 0, 0.1f); vertex(-1, -1, 0); vertex(1, -1, 0); vertex(1, 1, 0); vertex(-1, 1, 0); end();
    unsigned char px[4] = {};
    glReadPixels(4, 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    if (px[0] != 0) return 6;
    disable(0x0BC0);

    // GLSL translator check
    const char *src = "#version 120\nvarying vec2 uv;\nuniform sampler2D tex;\nvoid main(){gl_FragColor=texture2D(tex,uv);}\n";
    char dst[512] = {};
    intptr_t len = lumenGLTranslateShader(src, strlen(src), 3, 1, dst, sizeof(dst) - 1);
    if (len <= 0 || !strstr(dst, "#version 300 es")) return 7;

    begin(10);
    if (error() != GL_INVALID_ENUM || error() != GL_NO_ERROR || lumenGLGetProcAddress("glDispatchCompute")) return 5;
    lumenGLDestroy(bridge);
    eglMakeCurrent(d, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(d, surface);
    eglDestroyContext(d, context);
    eglTerminate(d);
    eglReleaseThread();
    puts("PASS");
    return 0;
}
