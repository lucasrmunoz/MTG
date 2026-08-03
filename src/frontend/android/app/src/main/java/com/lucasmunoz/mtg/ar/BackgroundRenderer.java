package com.lucasmunoz.mtg.ar;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Draws the camera image as the scene background.
 *
 * ARCore delivers camera frames into an OpenGL external texture; there is no View that shows
 * them, so every AR app renders this full-screen quad itself. Adapted from Google's ARCore
 * sample renderer (Apache 2.0). The texture coordinates are recomputed whenever the display
 * geometry changes so the image stays upright and unstretched.
 */
final class BackgroundRenderer {

    private static final String VERTEX_SHADER =
            "attribute vec4 a_Position;\n"
                    + "attribute vec2 a_TexCoord;\n"
                    + "varying vec2 v_TexCoord;\n"
                    + "void main() {\n"
                    + "  gl_Position = a_Position;\n"
                    + "  v_TexCoord = a_TexCoord;\n"
                    + "}";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 v_TexCoord;\n"
                    + "uniform samplerExternalOES u_Texture;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(u_Texture, v_TexCoord);\n"
                    + "}";

    /** Full-screen quad in normalised device coordinates, as a triangle strip. */
    private static final float[] QUAD_COORDS = {
            -1f, -1f, +1f, -1f, -1f, +1f, +1f, +1f,
    };

    private final FloatBuffer quadCoords;
    private final FloatBuffer quadTexCoords;

    private int program;
    private int positionAttribute;
    private int texCoordAttribute;
    private int textureId = -1;

    BackgroundRenderer() {
        quadCoords = ByteBuffer.allocateDirect(QUAD_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        quadCoords.put(QUAD_COORDS).position(0);

        quadTexCoords = ByteBuffer.allocateDirect(QUAD_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
    }

    int getTextureId() {
        return textureId;
    }

    /** Must run on the GL thread, once the context exists. */
    void createOnGlThread() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position");
        texCoordAttribute = GLES20.glGetAttribLocation(program, "a_TexCoord");
    }

    void draw(Frame frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadCoords,
                    Coordinates2d.TEXTURE_NORMALIZED, quadTexCoords);
        }

        if (frame.getTimestamp() == 0) {
            return; // No camera image yet; drawing now would show uninitialised texture memory.
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

        quadCoords.position(0);
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, quadCoords);
        quadTexCoords.position(0);
        GLES20.glVertexAttribPointer(texCoordAttribute, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords);
        GLES20.glEnableVertexAttribArray(positionAttribute);
        GLES20.glEnableVertexAttribArray(texCoordAttribute);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(positionAttribute);
        GLES20.glDisableVertexAttribArray(texCoordAttribute);
        GLES20.glDepthMask(true);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }
}
