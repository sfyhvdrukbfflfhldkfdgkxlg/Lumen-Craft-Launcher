package org.lwjgl;
import java.nio.*;
public final class BufferUtils {
    public static ByteBuffer createByteBuffer(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()); }
    public static FloatBuffer createFloatBuffer(int count) { return createByteBuffer(Math.multiplyExact(count,4)).asFloatBuffer(); }
    public static IntBuffer createIntBuffer(int count) { return createByteBuffer(Math.multiplyExact(count,4)).asIntBuffer(); }
}
