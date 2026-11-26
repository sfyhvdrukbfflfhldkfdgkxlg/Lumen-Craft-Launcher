#include <jni.h>
#include <GL/gl.h>
#include <GL/osmesa.h>
#include <stdint.h>
#include <stdlib.h>
#include <math.h>

static OSMesaContext ctx;
static unsigned char *frame;
static int frame_length;

JNIEXPORT jboolean JNICALL Java_dev_lumencraft_bridge_Session_createBuffer(JNIEnv *env, jclass cls, jint width, jint height) {
    if (ctx || width != 320 || height != 240) return JNI_FALSE;
    frame_length = width * height * 4;
    frame = calloc((size_t) frame_length, 1);
    ctx = OSMesaCreateContext(OSMESA_RGBA, NULL);
    if (!frame || !ctx || !OSMesaMakeCurrent(ctx, frame, GL_UNSIGNED_BYTE, width, height)) return JNI_FALSE;
    OSMesaPixelStore(OSMESA_Y_UP, 0);
    glViewport(0, 0, width, height);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_dev_lumencraft_bridge_Session_readBuffer(JNIEnv *env, jclass cls, jbyteArray destination) {
    glFinish();
    (*env)->SetByteArrayRegion(env, destination, 0, frame_length, (const jbyte *)frame);
}

JNIEXPORT void JNICALL Java_dev_lumencraft_bridge_Session_destroyBuffer(JNIEnv *env, jclass cls) {
    if (ctx) OSMesaDestroyContext(ctx);
    free(frame);
    frame = NULL;
    ctx = NULL;
}

/* LWJGL Buffer position and remaining are in elements. The game owns the retained direct arrays. */
static jint buffer_number(JNIEnv *env, jobject buffer, const char *name) {
    jclass type = (*env)->GetObjectClass(env, buffer);
    jmethodID method = (*env)->GetMethodID(env, type, name, "()I");
    return (*env)->CallIntMethod(env, buffer, method);
}
static void *pointer(JNIEnv *env, jobject buffer, size_t element_size) {
    unsigned char *base = (*env)->GetDirectBufferAddress(env, buffer);
    return base + element_size * buffer_number(env, buffer, "position");
}

JNIEXPORT jint JNICALL Java_org_lwjgl_opengl_GL11_glGenLists(JNIEnv *env, jclass cls, jint range) {
    return glGenLists(range);
}

JNIEXPORT jint JNICALL Java_org_lwjgl_opengl_GL11_glRenderMode(JNIEnv *env, jclass cls, jint mode) {
    return glRenderMode(mode);
}

JNIEXPORT jint JNICALL Java_org_lwjgl_opengl_GL11_glGetError(JNIEnv *env, jclass cls) {
    return glGetError();
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glCallList(JNIEnv *env, jclass cls, jint list) {
    glCallList(list);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glClear(JNIEnv *env, jclass cls, jint mask) {
    glClear(mask);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glClearColor(JNIEnv *env, jclass cls, jfloat r, jfloat g, jfloat b, jfloat a) {
    glClearColor(r, g, b, a);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glClearDepth(JNIEnv *env, jclass cls, jdouble depth) {
    glClearDepth(depth);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glColor4f(JNIEnv *env, jclass cls, jfloat r, jfloat g, jfloat b, jfloat a) {
    glColor4f(r, g, b, a);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glBindTexture(JNIEnv *env, jclass cls, jint target, jint texture) {
    glBindTexture(target, texture);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glBlendFunc(JNIEnv *env, jclass cls, jint source, jint destination) {
    glBlendFunc(source, destination);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glDepthFunc(JNIEnv *env, jclass cls, jint function) {
    glDepthFunc(function);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glDisable(JNIEnv *env, jclass cls, jint capability) {
    glDisable(capability);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glEnable(JNIEnv *env, jclass cls, jint capability) {
    glEnable(capability);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glDisableClientState(JNIEnv *env, jclass cls, jint array) {
    glDisableClientState(array);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glEnableClientState(JNIEnv *env, jclass cls, jint array) {
    glEnableClientState(array);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glDrawArrays(JNIEnv *env, jclass cls, jint mode, jint first, jint count) {
    glDrawArrays(mode, first, count);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glEndList(JNIEnv *env, jclass cls) {
    glEndList();
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glFogf(JNIEnv *env, jclass cls, jint name, jfloat value) {
    glFogf(name, value);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glFogi(JNIEnv *env, jclass cls, jint name, jint value) {
    glFogi(name, value);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glInitNames(JNIEnv *env, jclass cls) {
    glInitNames();
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glLoadIdentity(JNIEnv *env, jclass cls) {
    glLoadIdentity();
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glMatrixMode(JNIEnv *env, jclass cls, jint mode) {
    glMatrixMode(mode);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glNewList(JNIEnv *env, jclass cls, jint list, jint mode) {
    glNewList(list, mode);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glPopName(JNIEnv *env, jclass cls) {
    glPopName();
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glPushName(JNIEnv *env, jclass cls, jint name) {
    glPushName(name);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glRotatef(JNIEnv *env, jclass cls, jfloat angle, jfloat x, jfloat y, jfloat z) {
    glRotatef(angle, x, y, z);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glShadeModel(JNIEnv *env, jclass cls, jint mode) {
    glShadeModel(mode);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glTexParameteri(JNIEnv *env, jclass cls, jint target, jint name, jint value) {
    glTexParameteri(target, name, value);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glTranslatef(JNIEnv *env, jclass cls, jfloat x, jfloat y, jfloat z) {
    glTranslatef(x, y, z);
}
JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glColorPointer(JNIEnv *env, jclass cls, jint size, jint stride, jobject values) {
    glColorPointer(size, GL_FLOAT, stride, pointer(env, values, sizeof(GLfloat)));
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glTexCoordPointer(JNIEnv *env, jclass cls, jint size, jint stride, jobject values) {
    glTexCoordPointer(size, GL_FLOAT, stride, pointer(env, values, sizeof(GLfloat)));
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glVertexPointer(JNIEnv *env, jclass cls, jint size, jint stride, jobject values) {
    glVertexPointer(size, GL_FLOAT, stride, pointer(env, values, sizeof(GLfloat)));
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glFog(JNIEnv *env, jclass cls, jint name, jobject values) {
    glFogfv(name, pointer(env, values, sizeof(GLfloat)));
}
JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glAlphaFunc(JNIEnv *env, jclass cls, jint function, jfloat reference) {
    glAlphaFunc(function, reference);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glBegin(JNIEnv *env, jclass cls, jint primitive) {
    glBegin(primitive);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glColor3f(JNIEnv *env, jclass cls, jfloat r, jfloat g, jfloat b) {
    glColor3f(r, g, b);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glEnd(JNIEnv *env, jclass cls) {
    glEnd();
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glPopMatrix(JNIEnv *env, jclass cls) {
    glPopMatrix();
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glPushMatrix(JNIEnv *env, jclass cls) {
    glPushMatrix();
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glScalef(JNIEnv *env, jclass cls, jfloat x, jfloat y, jfloat z) {
    glScalef(x, y, z);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glTexCoord2f(JNIEnv *env, jclass cls, jfloat s, jfloat t) {
    glTexCoord2f(s, t);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glVertex3f(JNIEnv *env, jclass cls, jfloat x, jfloat y, jfloat z) {
    glVertex3f(x, y, z);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glLoadName(JNIEnv *env, jclass cls, jint name) {
    glLoadName(name);
}

JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glOrtho(JNIEnv *env, jclass cls, jdouble left, jdouble right, jdouble bottom, jdouble top, jdouble near_plane, jdouble far_plane) {
    glOrtho(left, right, bottom, top, near_plane, far_plane);
}
JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glInterleavedArrays(JNIEnv *env, jclass cls, jint format, jint stride, jobject values) {
    glInterleavedArrays(format, stride, pointer(env, values, sizeof(GLfloat)));
}
JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glLightModel(JNIEnv *env, jclass cls, jint name, jobject values) {
    glLightModelfv(name, pointer(env, values, sizeof(GLfloat)));
}
JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glGetFloat(JNIEnv *env, jclass cls, jint name, jobject values) {
    glGetFloatv(name, pointer(env, values, sizeof(GLfloat)));
}
JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glGetInteger(JNIEnv *env, jclass cls, jint name, jobject values) {
    glGetIntegerv(name, pointer(env, values, sizeof(GLint)));
}
JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glGenTextures(JNIEnv *env, jclass cls, jobject values) {
    glGenTextures(buffer_number(env, values, "remaining"), pointer(env, values, sizeof(GLuint)));
}
JNIEXPORT void JNICALL Java_org_lwjgl_opengl_GL11_glSelectBuffer(JNIEnv *env, jclass cls, jobject values) {
    glSelectBuffer(buffer_number(env, values, "remaining"), pointer(env, values, sizeof(GLuint)));
}
JNIEXPORT void JNICALL Java_org_lwjgl_util_glu_GLU_gluPerspective(JNIEnv *env, jclass cls, jfloat fovy, jfloat aspect, jfloat near_plane, jfloat far_plane) {
    double top = near_plane * tan(fovy * 3.141592653589793 / 360.0);
    glFrustum(-top * aspect, top * aspect, -top, top, near_plane, far_plane);
}
JNIEXPORT void JNICALL Java_org_lwjgl_util_glu_GLU_gluPickMatrix(JNIEnv *env, jclass cls, jfloat x, jfloat y, jfloat width, jfloat height, jobject buffer) {
    if (width <= 0 || height <= 0) return;
    GLint *v = pointer(env, buffer, sizeof(GLint));
    glTranslatef((v[2] - 2 * (x - v[0])) / width, (v[3] - 2 * (y - v[1])) / height, 0);
    glScalef(v[2] / width, v[3] / height, 1);
}
JNIEXPORT void JNICALL Java_org_lwjgl_util_glu_GLU_upload(JNIEnv *env, jclass cls, jint target, jint level, jint internal_format, jint width, jint height, jobject bytes) {
    glTexImage2D(target, level, internal_format, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pointer(env, bytes, 1));
}
