package com.example.depthmeasure;

import android.content.Context;
import android.media.Image;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class DepthRenderer {

    private static final String TAG = "DepthRenderer";

    // Rotate the texture sampling so the landscape depth image aligns with the
    // portrait display (uTexRot), then flip horizontally so it mirrors correctly.
    private static final String VERTEX_SHADER =
        "attribute vec4 vPosition;\n" +
        "attribute vec2 vTexCoord;\n" +
        "uniform mat2 uTexRot;\n" +
        "varying vec2 texCoord;\n" +
        "\n" +
        "void main() {\n" +
        "    gl_Position = vPosition;\n" +
        "    vec2 c = uTexRot * (vTexCoord - vec2(0.5, 0.5)) + vec2(0.5, 0.5);\n" +
        "    c.x = 1.0 - c.x;\n" +               // horizontal flip
        "    texCoord = c;\n" +
        "}\n";

    // Grayscale depth: invalid = black, near = dark, far = bright, normalized
    // over MAX_RANGE metres.
    private static final String FRAGMENT_SHADER =
        "precision mediump float;\n" +
        "uniform sampler2D depthTexture;\n" +
        "varying vec2 texCoord;\n" +
        "const float MAX_RANGE = 8.0;\n" +
        "\n" +
        "void main() {\n" +
        "    float depth = texture2D(depthTexture, texCoord).r;\n" +
        "    if (depth <= 0.0) {\n" +
        "        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
        "        return;\n" +
        "    }\n" +
        "    float g = clamp(depth / MAX_RANGE, 0.0, 1.0);\n" +
        "    gl_FragColor = vec4(g, g, g, 1.0);\n" +
        "}\n";

    private final Context context;
    private final OnDepthUpdateListener depthUpdateListener;

    private int programHandle = 0;
    private int texRotHandle = -1;
    private int depthTextureHandle = 0;
    private int quadVAO = 0;
    private int quadVBO = 0;
    private int texVBO = 0;
    private int quadEBO = 0;

    private int surfaceWidth = 0;
    private int surfaceHeight = 0;
    private int depthWidth = 0;
    private int depthHeight = 0;

    // Per-pixel confidence (0..1), decoded from the 3 MSB of each depth sample.
    private float[] confidenceArray;

    public interface OnDepthUpdateListener {
        void onDepthUpdate(float depth, float confidence);
    }

    public DepthRenderer(Context context, OnDepthUpdateListener listener) {
        this.context = context;
        this.depthUpdateListener = listener;
    }

    public void onSurfaceChanged(int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        GLES20.glViewport(0, 0, width, height);
        initializeShaders();
        setupQuad();
    }

    private void initializeShaders() {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        programHandle = GLES20.glCreateProgram();
        GLES20.glAttachShader(programHandle, vertexShader);
        GLES20.glAttachShader(programHandle, fragmentShader);
        GLES20.glLinkProgram(programHandle);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(programHandle, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Shader link failed: " + GLES20.glGetProgramInfoLog(programHandle));
            GLES20.glDeleteProgram(programHandle);
        }

        texRotHandle = GLES20.glGetUniformLocation(programHandle, "uTexRot");

        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
    }

    /** Maps the display rotation to the texture-rotation angle needed to align
     *  the landscape depth image with the (portrait-locked) display. */
    private static float rotationDegrees(int surfaceRotation) {
        switch (surfaceRotation) {
            case 1:  return 180.0f;  // Surface.ROTATION_90
            case 2:  return 90.0f;   // Surface.ROTATION_180
            case 3:  return 0.0f;    // Surface.ROTATION_270
            default: return 270.0f;  // Surface.ROTATION_0 (portrait)
        }
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
        }

        return shader;
    }

    private void setupQuad() {
        float[] positions = {
            -1.0f, 1.0f,
            -1.0f, -1.0f,
            1.0f, -1.0f,
            1.0f, 1.0f
        };

        float[] texCoords = {
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        };

        int[] indices = {0, 1, 2, 0, 2, 3};

        // Vertex Array Objects are a GL ES 3.0 feature.
        int[] vao = new int[1];
        GLES30.glGenVertexArrays(1, vao, 0);
        quadVAO = vao[0];
        GLES30.glBindVertexArray(quadVAO);

        int[] vbos = new int[3];
        GLES20.glGenBuffers(3, vbos, 0);
        quadVBO = vbos[0];
        texVBO = vbos[1];
        quadEBO = vbos[2];

        // Position buffer
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVBO);
        FloatBuffer posBuffer = FloatBuffer.wrap(positions);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, positions.length * 4, posBuffer, GLES20.GL_STATIC_DRAW);

        int positionHandle = GLES20.glGetAttribLocation(programHandle, "vPosition");
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, 0);

        // TexCoord buffer (its own VBO — the original code overwrote the position buffer)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, texVBO);
        FloatBuffer texBuffer = FloatBuffer.wrap(texCoords);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, texCoords.length * 4, texBuffer, GLES20.GL_STATIC_DRAW);

        int texCoordHandle = GLES20.glGetAttribLocation(programHandle, "vTexCoord");
        GLES20.glEnableVertexAttribArray(texCoordHandle);
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, 0);

        // Index buffer
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, quadEBO);
        IntBuffer indexBuffer = IntBuffer.wrap(indices);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.length * 4, indexBuffer, GLES20.GL_STATIC_DRAW);

        GLES30.glBindVertexArray(0);
    }

    public void renderDepthMap(Image depthImage, long frameTimestamp, int rotation) {
        try {
            depthWidth = depthImage.getWidth();
            depthHeight = depthImage.getHeight();

            // Extract depth data
            float[] depthData = getDepthData(depthImage);

            // Create or update texture
            if (depthTextureHandle == 0) {
                depthTextureHandle = createDepthTexture(depthWidth, depthHeight, depthData);
            } else {
                updateDepthTexture(depthData);
            }

            // Calculate center depth + confidence for UI update
            float[] centerResult = getCenterDepth(depthData, confidenceArray, depthWidth, depthHeight);
            if (depthUpdateListener != null) {
                depthUpdateListener.onDepthUpdate(centerResult[0], centerResult[1]);
            }

            // Render to screen
            GLES20.glUseProgram(programHandle);

            // Rotate texture sampling to match the display orientation.
            float angle = (float) Math.toRadians(rotationDegrees(rotation));
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            // Column-major mat2: [cos, sin, -sin, cos]
            float[] rot = { cos, sin, -sin, cos };
            GLES20.glUniformMatrix2fv(texRotHandle, 1, false, rot, 0);

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureHandle);
            GLES30.glBindVertexArray(quadVAO);
            GLES30.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_INT, 0);
            GLES30.glBindVertexArray(0);

        } catch (Exception e) {
            Log.e(TAG, "Error rendering depth map", e);
        }
    }

    private float[] getDepthData(Image depthImage) {
        Image.Plane[] planes = depthImage.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();

        float[] depthArray = new float[depthWidth * depthHeight];
        confidenceArray = new float[depthWidth * depthHeight];

        for (int y = 0; y < depthHeight; y++) {
            int rowStart = y * rowStride;
            for (int x = 0; x < depthWidth; x++) {
                int index = rowStart + x * pixelStride;
                // ImageFormat.DEPTH16: low 13 bits = depth in mm, high 3 bits = confidence.
                // Confidence encoding: 0 => 100%, 1 => 0%, 2..7 => 1/7..6/7.
                int sample = buffer.getShort(index) & 0xFFFF;
                int depthMillimeters = sample & 0x1FFF;
                int confBits = (sample >> 13) & 0x7;
                float confidence = (confBits == 0) ? 1.0f : (confBits - 1) / 7.0f;
                int flatIndex = y * depthWidth + x;
                depthArray[flatIndex] = depthMillimeters / 1000.0f;
                confidenceArray[flatIndex] = confidence;
            }
        }

        return depthArray;
    }

    private int createDepthTexture(int width, int height, float[] depthData) {
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // GL_R32F / GL_RED single-channel float textures are GL ES 3.0.
        FloatBuffer buffer = FloatBuffer.wrap(depthData);
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R32F, width, height, 0,
            GLES30.GL_RED, GLES20.GL_FLOAT, buffer
        );

        return texture[0];
    }

    private void updateDepthTexture(float[] depthData) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureHandle);
        FloatBuffer buffer = FloatBuffer.wrap(depthData);
        GLES30.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, depthWidth, depthHeight,
            GLES30.GL_RED, GLES20.GL_FLOAT, buffer
        );
    }

    /**
     * Samples a 5x5 region around the image center.
     *
     * @return {averageDepthMeters, confidence} where depth is the mean of valid
     *         samples (-1 if none) and confidence is the mean ARCore per-pixel
     *         confidence (0..1) over those valid samples.
     */
    private float[] getCenterDepth(float[] depthData, float[] confData, int width, int height) {
        int centerX = width / 2;
        int centerY = height / 2;

        float depthSum = 0.0f;
        float confSum = 0.0f;
        int validSamples = 0;

        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = centerX + dx;
                int y = centerY + dy;
                if (x >= 0 && x < width && y >= 0 && y < height) {
                    int idx = y * width + x;
                    float depth = depthData[idx];
                    if (depth > 0) {
                        depthSum += depth;
                        confSum += confData[idx];
                        validSamples++;
                    }
                }
            }
        }

        float depth = validSamples > 0 ? depthSum / validSamples : -1.0f;
        float confidence = validSamples > 0 ? confSum / validSamples : 0.0f;
        return new float[]{depth, confidence};
    }
}
