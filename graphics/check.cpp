#include "legacy.h"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

extern "C" JNIEXPORT jstring JNICALL Java_dev_lumencraft_LegacyGraphics_check(JNIEnv *env, jclass) {
    EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(d, nullptr, nullptr)) return env->NewStringUTF("EGL initialization failed");
    const EGLint attrs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
    EGLConfig cfg;
    EGLint count = 0;
    EGLSurface surf = EGL_NO_SURFACE;
    EGLContext ctx = EGL_NO_CONTEXT;
    char report[1024] = "EGL context unavailable";
    if (eglChooseConfig(d, attrs, &cfg, 1, &count) && count > 0) {
        const EGLint surface[] = {EGL_WIDTH, 8, EGL_HEIGHT, 8, EGL_NONE};
        const EGLint context[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
        surf = eglCreatePbufferSurface(d, cfg, surface);
        ctx = eglCreateContext(d, cfg, EGL_NO_CONTEXT, context);
        if (surf != EGL_NO_SURFACE && ctx != EGL_NO_CONTEXT && eglMakeCurrent(d, surf, surf, ctx)) {
            LumenGL *bridge = lumenGLCreate();
            snprintf(report, sizeof(report), "GLES2 shader initialization failed");
            if (bridge) {
                lumenGLMakeCurrent(bridge);
                auto begin = (void (*)(unsigned))lumenGLGetProcAddress("glBegin");
                auto vertex = (void (*)(float, float, float))lumenGLGetProcAddress("glVertex3f");
                auto color = (void (*)(float, float, float, float))lumenGLGetProcAddress("glColor4f");
                auto texCoord = (void (*)(float, float))lumenGLGetProcAddress("glTexCoord2f");
                auto end = (void (*)())lumenGLGetProcAddress("glEnd");
                auto pushM = (void (*)())lumenGLGetProcAddress("glPushMatrix");
                auto popM = (void (*)())lumenGLGetProcAddress("glPopMatrix");
                auto translate = (void (*)(float, float, float))lumenGLGetProcAddress("glTranslatef");
                auto enable = (void (*)(unsigned))lumenGLGetProcAddress("glEnable");
                auto disable = (void (*)(unsigned))lumenGLGetProcAddress("glDisable");
                auto alphaFunc = (void (*)(unsigned, float))lumenGLGetProcAddress("glAlphaFunc");
                auto genLists = (unsigned (*)(int))lumenGLGetProcAddress("glGenLists");
                auto newList = (void (*)(unsigned, unsigned))lumenGLGetProcAddress("glNewList");
                auto endList = (void (*)())lumenGLGetProcAddress("glEndList");
                auto callList = (void (*)(unsigned))lumenGLGetProcAddress("glCallList");
                auto delLists = (void (*)(unsigned, int))lumenGLGetProcAddress("glDeleteLists");
                auto errFn = (unsigned (*)())lumenGLGetProcAddress("glGetError");

                glViewport(0, 0, 8, 8);
                unsigned char p[4] = {};

                // 1. GL_QUADS
                glClearColor(0, 0, 0, 1);
                glClear(GL_COLOR_BUFFER_BIT);
                begin(7); color(1, 0, 0, 1); vertex(-1, -1, 0); vertex(1, -1, 0); vertex(1, 1, 0); vertex(-1, 1, 0); end();
                glReadPixels(4, 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, p);
                bool quad = errFn() == GL_NO_ERROR && p[0] == 255 && p[1] == 0 && p[2] == 0;

                // 2. GL_POLYGON + matrix stack translation
                glClear(GL_COLOR_BUFFER_BIT);
                pushM();
                translate(0.5f, 0.0f, 0.0f);
                begin(9); color(0, 1, 0, 1); vertex(-0.5f, -1, 0); vertex(0.5f, -1, 0); vertex(0.5f, 1, 0); vertex(-0.5f, 1, 0); end();
                popM();
                unsigned char leftPx[4] = {}, rightPx[4] = {};
                glReadPixels(1, 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, leftPx);
                glReadPixels(6, 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, rightPx);
                bool matrixPoly = errFn() == GL_NO_ERROR && leftPx[1] == 0 && rightPx[1] == 255;

                // 3. GL_ALPHA_TEST discard
                glClear(GL_COLOR_BUFFER_BIT);
                enable(0x0BC0);
                alphaFunc(GL_GREATER, 0.5f);
                begin(7); color(0, 0, 1, 0.2f); vertex(-1, -1, 0); vertex(1, -1, 0); vertex(1, 1, 0); vertex(-1, 1, 0); end();
                glReadPixels(4, 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, p);
                bool alphaDiscard = p[2] == 0;
                begin(7); color(0, 0, 1, 0.9f); vertex(-1, -1, 0); vertex(1, -1, 0); vertex(1, 1, 0); vertex(-1, 1, 0); end();
                glReadPixels(4, 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, p);
                bool alphaPass = alphaDiscard && p[2] == 255 && errFn() == GL_NO_ERROR;
                disable(0x0BC0);

                // 4. Display list + 2D texture sampling
                GLuint tex = 0;
                glGenTextures(1, &tex);
                glBindTexture(GL_TEXTURE_2D, tex);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                const unsigned char texels[4] = {0, 255, 255, 255};
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, texels);
                unsigned list = genLists(1);
                newList(list, 0x1300);
                enable(0x0DE1);
                begin(7); color(1, 1, 1, 1);
                texCoord(0, 0); vertex(-1, -1, 0);
                texCoord(1, 0); vertex(1, -1, 0);
                texCoord(1, 1); vertex(1, 1, 0);
                texCoord(0, 1); vertex(-1, 1, 0);
                end();
                disable(0x0DE1);
                endList();
                glClear(GL_COLOR_BUFFER_BIT);
                callList(list);
                glReadPixels(4, 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, p);
                bool texList = list != 0 && errFn() == GL_NO_ERROR && p[0] == 0 && p[1] == 255 && p[2] == 255;
                delLists(list, 1);
                glDeleteTextures(1, &tex);

                // 5. Desktop GLSL -> GLSL ES 3.00 translator check
                const char *glsl = "#version 120\nvarying vec2 uv;\nuniform sampler2D s;\nvoid main(){gl_FragColor=texture2D(s,uv);}\n";
                char translated[512] = {};
                intptr_t tlen = lumenGLTranslateShader(glsl, strlen(glsl), 3, 1, translated, sizeof(translated) - 1);
                bool shaderOk = tlen > 0 && strstr(translated, "#version 300 es") != nullptr &&
                                strstr(translated, "lumen_FragColor") != nullptr;

                snprintf(report, sizeof(report),
                    "GL_QUADS → GLES 2.0   %s\n"
                    "GL_POLYGON + Matrix   %s\n"
                    "GL_ALPHA_TEST         %s\n"
                    "Texture + DisplayList %s\n"
                    "GLSL 120 → ES 300     %s",
                    quad ? "PASS" : "FAIL",
                    matrixPoly ? "PASS" : "FAIL",
                    alphaPass ? "PASS" : "FAIL",
                    texList ? "PASS" : "FAIL",
                    shaderOk ? "PASS" : "FAIL");
                lumenGLDestroy(bridge);
            }
            eglMakeCurrent(d, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
    }
    if (ctx != EGL_NO_CONTEXT) eglDestroyContext(d, ctx);
    if (surf != EGL_NO_SURFACE) eglDestroySurface(d, surf);
    eglTerminate(d);
    eglReleaseThread();
    return env->NewStringUTF(report);
}
