package org.lwjgl.opengl;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** LWJGL 2 entry points used by the hash-pinned RubyDung builds. */
public final class GL11 {
    private GL11() {}
    public static native void glAlphaFunc(int function, float reference);
    public static native void glBegin(int primitive);
    public static native void glColor3f(float r, float g, float b);
    public static native void glEnd();
    public static native void glPopMatrix();
    public static native void glPushMatrix();
    public static native void glScalef(float x, float y, float z);
    public static native void glTexCoord2f(float s, float t);
    public static native void glVertex3f(float x, float y, float z);
    public static native void glLoadName(int name);
    public static native void glOrtho(double left, double right, double bottom, double top, double near_plane, double far_plane);
    public static native void glInterleavedArrays(int format, int stride, FloatBuffer values);
    public static native void glLightModel(int name, FloatBuffer values);
    public static native int glGenLists(int range);
    public static native int glRenderMode(int mode);
    public static native int glGetError();
    public static native void glCallList(int list);
    public static native void glClear(int mask);
    public static native void glClearColor(float r, float g, float b, float a);
    public static native void glClearDepth(double depth);
    public static native void glColor4f(float r, float g, float b, float a);
    public static native void glBindTexture(int target, int texture);
    public static native void glBlendFunc(int source, int destination);
    public static native void glDepthFunc(int function);
    public static native void glDisable(int capability);
    public static native void glEnable(int capability);
    public static native void glDisableClientState(int array);
    public static native void glEnableClientState(int array);
    public static native void glDrawArrays(int mode, int first, int count);
    public static native void glEndList();
    public static native void glFogf(int name, float value);
    public static native void glFogi(int name, int value);
    public static native void glInitNames();
    public static native void glLoadIdentity();
    public static native void glMatrixMode(int mode);
    public static native void glNewList(int list, int mode);
    public static native void glPopName();
    public static native void glPushName(int name);
    public static native void glRotatef(float angle, float x, float y, float z);
    public static native void glShadeModel(int mode);
    public static native void glTexParameteri(int target, int name, int value);
    public static native void glTranslatef(float x, float y, float z);
    public static native void glColorPointer(int size, int stride, FloatBuffer values);
    public static native void glTexCoordPointer(int size, int stride, FloatBuffer values);
    public static native void glVertexPointer(int size, int stride, FloatBuffer values);
    public static native void glFog(int name, FloatBuffer values);
    public static native void glGetFloat(int name, FloatBuffer values);
    public static native void glGetInteger(int name, IntBuffer values);
    public static native void glGenTextures(IntBuffer values);
    public static native void glSelectBuffer(IntBuffer values);
}
