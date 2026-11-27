#include "legacy.h"
#include <GLES2/gl2.h>
#include <EGL/egl.h>
#include <jni.h>
extern "C" JNIEXPORT jstring JNICALL Java_dev_lumencraft_LegacyGraphics_check(JNIEnv *env,jclass) {
    EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(d,nullptr,nullptr)) return env->NewStringUTF("EGL initialization failed");
    const EGLint attrs[]={EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
    EGLConfig cfg;EGLint count=0;
    EGLSurface surf=EGL_NO_SURFACE;EGLContext ctx=EGL_NO_CONTEXT;
    const char *msg="EGL context unavailable";
    if (eglChooseConfig(d,attrs,&cfg,1,&count) && count>0) {
        const EGLint surface[]={EGL_WIDTH,8,EGL_HEIGHT,8,EGL_NONE};
        const EGLint context[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
        surf=eglCreatePbufferSurface(d,cfg,surface);ctx=eglCreateContext(d,cfg,EGL_NO_CONTEXT,context);
        if (surf!=EGL_NO_SURFACE && ctx!=EGL_NO_CONTEXT && eglMakeCurrent(d,surf,surf,ctx)) {
            LumenGL *bridge=lumenGLCreate();
            msg="GLES2 shader initialization failed";
            if (bridge) {
                lumenGLMakeCurrent(bridge);
                auto begin=(void(*)(unsigned))lumenGLGetProcAddress("glBegin");
                auto vertex=(void(*)(float,float,float))lumenGLGetProcAddress("glVertex3f");
                auto color=(void(*)(float,float,float,float))lumenGLGetProcAddress("glColor4f");
                auto end=(void(*)())lumenGLGetProcAddress("glEnd");
                glViewport(0,0,8,8);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
                begin(7);color(1,0,0,1);vertex(-1,-1,0);vertex(1,-1,0);vertex(1,1,0);vertex(-1,1,0);end();
                unsigned char p[4]={};glReadPixels(4,4,1,1,GL_RGBA,GL_UNSIGNED_BYTE,p);
                bool quad=glGetError()==GL_NO_ERROR && p[0]==255 && p[1]==0 && p[2]==0;
                begin(9);color(0,1,0,1);vertex(-1,-1,0);vertex(1,-1,0);vertex(1,1,0);vertex(-1,1,0);end();
                glReadPixels(4,4,1,1,GL_RGBA,GL_UNSIGNED_BYTE,p);
                bool polygon=glGetError()==GL_NO_ERROR && p[0]==0 && p[1]==255 && p[2]==0;
                msg=!quad ? "GL_QUADS -> GLES 2.0   FAIL" : !polygon ? "GL_POLYGON -> GLES 2.0   FAIL" :
                    "GL_QUADS → GLES 2.0   PASS (RGBA readback)\nGL_POLYGON → GLES 2.0   PASS (RGBA readback)";
                lumenGLDestroy(bridge);
            }
            eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        }
    }
    if(ctx!=EGL_NO_CONTEXT) eglDestroyContext(d,ctx);
    if(surf!=EGL_NO_SURFACE) eglDestroySurface(d,surf);
    eglTerminate(d);eglReleaseThread();
    return env->NewStringUTF(msg);
}
