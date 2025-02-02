package net.roguelogix.quartz.internal.common;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import net.roguelogix.quartz.internal.MagicNumbers;
import net.roguelogix.quartz.internal.QuartzCore;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

@NonnullDefault
public class DrawInfo {
    public long deltaNano;
    public float partialTicks;
    
    public final Vector3i playerPosition = new Vector3i();
    public final Vector3i playerPositionNegative = new Vector3i();
    public final Vector3f playerSubBlock = new Vector3f();
    public final Vector3f playerSubBlockNegative = new Vector3f();
    public final Matrix4f projectionMatrix = new Matrix4f();
    public final ByteBuffer projectionMatrixByteBuffer = MemoryUtil.memAlloc(MagicNumbers.MATRIX_4F_BYTE_SIZE);
    public final FloatBuffer projectionMatrixFloatBuffer = projectionMatrixByteBuffer.asFloatBuffer();
    
    public float fogStart;
    public float fogEnd;
    public final Vector4f fogColor = new Vector4f();
    
    
    public DrawInfo() {
        ByteBuffer projectionMatrixByteBuffer = this.projectionMatrixByteBuffer;
        QuartzCore.CLEANER.register(this, () -> MemoryUtil.memFree(projectionMatrixByteBuffer));
    }
}
