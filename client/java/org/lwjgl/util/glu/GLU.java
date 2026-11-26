package org.lwjgl.util.glu;
import java.nio.*;

public final class GLU {
    public static native void gluPerspective(float fovy, float aspect, float near, float far);
    public static native void gluPickMatrix(float x, float y, float width, float height, IntBuffer viewport);
    private static native void upload(int target, int level, int format, int width, int height, ByteBuffer pixels);
    public static String gluErrorString(int error) { return "OpenGL error 0x" + Integer.toHexString(error); }
    public static int gluBuild2DMipmaps(int target, int internalFormat, int width, int height, int format, int type, ByteBuffer input) {
        if (width < 1 || height < 1 || width > 2048 || height > 2048 || format != 6408 || type != 5121 ||
                !input.isDirect() || input.remaining() < width * height * 4) return 100901;
        ByteBuffer pixels = input.slice();
        for (int level = 0; ; level++) {
            upload(target, level, internalFormat, width, height, pixels);
            if (width == 1 && height == 1) return 0;
            int w = Math.max(width/2,1), h = Math.max(height/2,1);
            ByteBuffer next = ByteBuffer.allocateDirect(w * h * 4);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int a = 4 * (2*y*width + 2*x);
                int b = a + (width > 1 ? 4 : 0);
                int row = height > 1 ? width * 4 : 0;
                for (int c = 0; c < 4; c++)
                    next.put((byte)(((pixels.get(a+c)&255) + (pixels.get(b+c)&255) +
                            (pixels.get(a+row+c)&255) + (pixels.get(b+row+c)&255) + 2) / 4));
            }
            next.flip();
            pixels = next;
            width = w;
            height = h;
        }
    }
}
