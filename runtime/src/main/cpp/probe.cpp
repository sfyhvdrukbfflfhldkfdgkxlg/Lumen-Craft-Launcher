#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <jni.h>
#include <stdio.h>

namespace {
const char* gl_string(GLenum name) {
    auto value = glGetString(name);
    return value ? reinterpret_cast<const char*>(value) : "unknown";
}
bool inspect_version(EGLDisplay display, EGLint version, char* report, size_t size) {
    const EGLint config_attrs[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE,
        version == 3 ? 0x0040 : EGL_OPENGL_ES2_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8, EGL_NONE };
    EGLConfig config;
    EGLint n;
    if (!eglChooseConfig(display, config_attrs, &config, 1, &n) || n < 1) return false;
    const EGLint pbuffer_attrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    const EGLint context_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, version, EGL_NONE };
    EGLSurface surface = eglCreatePbufferSurface(display, config, pbuffer_attrs);
    if (surface == EGL_NO_SURFACE) return false;
    EGLContext ctx = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attrs);
    bool bound = ctx != EGL_NO_CONTEXT && eglMakeCurrent(display, surface, surface, ctx);
    if (bound) {
        unsigned char pixel[4] = {};
        glClearColor(0, 1, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        bool passed = glGetError() == GL_NO_ERROR && pixel[0] == 0 && pixel[1] == 255 && pixel[2] == 0;
        snprintf(report, size, "GPU: %.256s\nVendor: %.256s\nGL: %.256s\nGLSL: %.256s\n1px render/readback: %s",
            gl_string(GL_RENDERER), gl_string(GL_VENDOR), gl_string(GL_VERSION),
            gl_string(GL_SHADING_LANGUAGE_VERSION), passed ? "PASS" : "FAIL");
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (ctx != EGL_NO_CONTEXT) eglDestroyContext(display, ctx);
    eglDestroySurface(display, surface);
    return bound;
}
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_lumencraft_runtime_GraphicsProbe_readOnWorker(JNIEnv* env, jclass) {
    char report[1536] = {};
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) {
        snprintf(report, sizeof(report), "EGL initialization failed (0x%x)", eglGetError());
    } else {
        if (!eglBindAPI(EGL_OPENGL_ES_API) ||
            (!inspect_version(display, 3, report, sizeof(report)) && !inspect_version(display, 2, report, sizeof(report))))
            snprintf(report, sizeof(report), "EGL pbuffer context unavailable (0x%x)", eglGetError());
        eglTerminate(display);
    }
    eglReleaseThread();
    return env->NewStringUTF(report);
}
