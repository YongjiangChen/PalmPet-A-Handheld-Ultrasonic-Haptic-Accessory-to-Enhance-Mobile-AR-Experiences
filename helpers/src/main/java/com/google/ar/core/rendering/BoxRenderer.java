
package com.google.ar.core.rendering;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;

import com.google.ar.core.Camera;
import com.google.ar.core.helpers.AABB;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

public class BoxRenderer {
    private static final String TAG = BoxRenderer.class.getSimpleName();
    private static final int NUM_FACES = 6;
    private static final int COORDS_PER_VERTEX = 3;
    private static final int VERTICES_PER_CUBE = 24;  // 6 faces * 4 vertices per face
    private static final int FLOAT_SIZE = 4;

    private static final String VERTEX_SHADER_NAME = "shaders/box.vert";
    private static final String FRAGMENT_SHADER_NAME = "shaders/box.frag";

    // Stores the triangulation of the cube.
    private FloatBuffer vertexBuffer;
    private ShortBuffer indexBuffer;

    // Shader program.
    private int program;
    private int vPosition;
    private int uViewProjection;

    public BoxRenderer() {}

    public void createOnGlThread(Context context) throws IOException {
        ShaderUtil.checkGLError(TAG, "Create");

        // Compile vertex and fragment shaders
        int vertexShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_NAME);
        int fragmentShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_NAME);

        // Link shaders into a program and create a shader program handle
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        GLES20.glUseProgram(program);

        ShaderUtil.checkGLError(TAG, "Program creation");

        // Get the attribute and uniform locations
        vPosition = GLES20.glGetAttribLocation(program, "vPosition");
        uViewProjection = GLES20.glGetUniformLocation(program, "uViewProjection");

        // Initialize vertexBuffer
        ByteBuffer vbb = ByteBuffer.allocateDirect(VERTICES_PER_CUBE * COORDS_PER_VERTEX * FLOAT_SIZE);
        vbb.order(ByteOrder.nativeOrder());
        vertexBuffer = vbb.asFloatBuffer();

        // Initialize indexBuffer
        short[] indices = {
                0, 1, 2, 2, 1, 3, // Front
                4, 5, 6, 6, 5, 7, // Back
                8, 9, 10, 10, 9, 11, // Left
                12, 13, 14, 14, 13, 15, // Right
                16, 17, 18, 18, 17, 19, // Top
                20, 21, 22, 22, 21, 23  // Bottom
        };
        ByteBuffer ibb = ByteBuffer.allocateDirect(indices.length * 2);
        ibb.order(ByteOrder.nativeOrder());
        indexBuffer = ibb.asShortBuffer();
        indexBuffer.put(indices);
        indexBuffer.position(0);

        // Create and bind the vertex buffer object (VBO)
        int[] vboIds = new int[1];
        GLES20.glGenBuffers(1, vboIds, 0);
        int vboId = vboIds[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexBuffer.capacity() * FLOAT_SIZE, null, GLES20.GL_DYNAMIC_DRAW);

        // Create and bind the index buffer object (IBO)
        int[] iboIds = new int[1];
        GLES20.glGenBuffers(1, iboIds, 0);
        int iboId = iboIds[0];
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, iboId);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer.capacity() * 2, indexBuffer, GLES20.GL_STATIC_DRAW);

        // Unbind buffers
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

        ShaderUtil.checkGLError(TAG, "Buffer creation");
    }

    private void setCubeDimensions(AABB aabb) {
        if (aabb == null || vertexBuffer == null) {
            Log.e(TAG, "Cannot set cube dimensions: aabb or vertexBuffer is null");
            return;
        }

        float[] vertices = new float[VERTICES_PER_CUBE * COORDS_PER_VERTEX];
        int idx = 0;

        // Front
        vertices[idx++] = aabb.minX; vertices[idx++] = aabb.minY; vertices[idx++] = aabb.maxZ;
        vertices[idx++] = aabb.maxX; vertices[idx++] = aabb.minY; vertices[idx++] = aabb.maxZ;
        vertices[idx++] = aabb.minX; vertices[idx++] = aabb.maxY; vertices[idx++] = aabb.maxZ;
        vertices[idx++] = aabb.maxX; vertices[idx++] = aabb.maxY; vertices[idx++] = aabb.maxZ;

        // Back
        vertices[idx++] = aabb.maxX; vertices[idx++] = aabb.minY; vertices[idx++] = aabb.minZ;
        vertices[idx++] = aabb.minX; vertices[idx++] = aabb.minY; vertices[idx++] = aabb.minZ;
        vertices[idx++] = aabb.maxX; vertices[idx++] = aabb.maxY; vertices[idx++] = aabb.minZ;
        vertices[idx++] = aabb.minX; vertices[idx++] = aabb.maxY; vertices[idx++] = aabb.minZ;

        // Left
        vertices[idx++] = aabb.minX; vertices[idx++] = aabb.minY; vertices[idx++] = aabb.minZ;
        vertices[idx++] = aabb.minX; vertices[idx++] = aabb.minY; vertices[idx++] = aabb.maxZ;
        vertices[idx++] = aabb.minX; vertices[idx++] = aabb.maxY; vertices[idx++] = aabb.minZ;
        vertices[idx++] = aabb.minX; vertices[idx++] = aabb.maxY; vertices[idx++] = aabb.maxZ;

        // Right
        vertices[idx++] = aabb.maxX; vertices[idx++] = aabb.minY; vertices[idx++] = aabb.maxZ;
        vertices[idx++] = aabb.maxX; vertices[idx++] = aabb.minY; vertices[idx++] = aabb.minZ;
        vertices[idx++] = aabb.maxX; vertices[idx++] = aabb.maxY; vertices[idx++] = aabb.maxZ;
        vertices[idx++] = aabb.maxX; vertices[idx++] = aabb.maxY; vertices[idx++] = aabb.minZ;

        // Top
        vertices[idx++] = aabb.minX; vertices[idx++] = aabb.maxY; vertices[idx++] = aabb.maxZ;
        vertices[idx++] = aabb.maxX; vertices[idx++] = aabb.maxY; vertices[idx++] = aabb.maxZ;
        vertices[idx++] = aabb.minX; vertices[idx++] = aabb.maxY; vertices[idx++] = aabb.minZ;
        vertices[idx++] = aabb.maxX; vertices[idx++] = aabb.maxY; vertices[idx++] = aabb.minZ;

        // Bottom
        vertices[idx++] = aabb.minX; vertices[idx++] = aabb.minY; vertices[idx++] = aabb.minZ;
        vertices[idx++] = aabb.maxX; vertices[idx++] = aabb.minY; vertices[idx++] = aabb.minZ;
        vertices[idx++] = aabb.minX; vertices[idx++] = aabb.minY; vertices[idx++] = aabb.maxZ;
        vertices[idx++] = aabb.maxX; vertices[idx++] = aabb.minY; vertices[idx++] = aabb.maxZ;

        vertexBuffer.clear();
        vertexBuffer.put(vertices);
        vertexBuffer.position(0);
    }

    public void draw(AABB aabb, Camera camera) {
        if (aabb == null || camera == null) {
            Log.w(TAG, "Cannot draw box: aabb or camera is null");
            return;
        }

        if (vertexBuffer == null) {
            Log.e(TAG, "Cannot draw box: vertexBuffer is null");
            return;
        }

        float[] projectionMatrix = new float[16];
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);
        float[] viewMatrix = new float[16];
        camera.getViewMatrix(viewMatrix, 0);
        float[] viewProjection = new float[16];
        Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0);

        // Updates the positions of the cube.
        setCubeDimensions(aabb);
        if (vertexBuffer == null) {
            Log.e(TAG, "Cannot draw box: vertexBuffer is null");
            return;
        }

        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uViewProjection, 1, false, viewProjection, 0);
        GLES20.glEnableVertexAttribArray(vPosition);
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(vPosition, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);
        ShaderUtil.checkGLError(TAG, "Draw");

        // Draws a cube.
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        indexBuffer.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexBuffer.remaining(), GLES20.GL_UNSIGNED_SHORT, indexBuffer);
        GLES20.glDisableVertexAttribArray(vPosition);
        GLES20.glDisable(GLES20.GL_BLEND);

        ShaderUtil.checkGLError(TAG, "Draw complete");
    }
}
