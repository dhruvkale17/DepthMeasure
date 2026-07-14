package com.example.depthmeasure;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.Image;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

    private static final String TAG = "DepthMeasure";
    private static final int PERMISSION_CODE = 100;
    private static final int MAX_HISTORY = 20;

    // ARCore depth is reliable roughly between these distances.
    private static final float MIN_RANGE_M = 0.5f;
    private static final float MAX_RANGE_M = 8.0f;
    private static final float LOW_CONFIDENCE = 0.5f;

    private GLSurfaceView glSurfaceView;
    private Session arSession;
    private TextView depthTextView;
    private TextView confidenceTextView;

    private DrawerLayout drawerLayout;
    private TextView drawerContent;
    private Button tabInstructions;
    private Button tabHowItWorks;

    private DisplayRotationHelper displayRotationHelper;
    private DepthRenderer depthRenderer;

    // ARCore requires a camera texture to be registered before Session.update().
    private int cameraTextureId = 0;
    private boolean cameraTextureSet = false;

    private final List<DepthMeasurement> measurementHistory = new ArrayList<>();
    private final SimpleDateFormat logTimeFormat =
        new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        glSurfaceView = findViewById(R.id.surfaceview);
        depthTextView = findViewById(R.id.depthValue);
        confidenceTextView = findViewById(R.id.confidenceValue);

        setupDrawer();

        // Check permissions
        if (!allPermissionsGranted()) {
            requestPermissions();
        } else {
            initializeARSession();
        }

        displayRotationHelper = new DisplayRotationHelper(this);
        setupGLSurfaceView();
    }

    private void setupGLSurfaceView() {
        glSurfaceView.setPreserveEGLContextOnPause(true);
        glSurfaceView.setEGLContextClientVersion(3);
        glSurfaceView.setRenderer(this);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    private void initializeARSession() {
        try {
            // Check if device supports ARCore
            ArCoreApk.InstallStatus installStatus =
                ArCoreApk.getInstance().requestInstall(this, !BuildConfig.DEBUG);

            switch (installStatus) {
                case INSTALL_REQUESTED:
                    Log.i(TAG, "ARCore install requested");
                    return;
                case INSTALLED:
                    break;
            }

            arSession = new Session(this);

            // Enable the depth API if the device supports it.
            Config config = new Config(arSession);
            if (arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.setDepthMode(Config.DepthMode.AUTOMATIC);
                Log.d(TAG, "Depth mode AUTOMATIC enabled");
            } else {
                config.setDepthMode(Config.DepthMode.DISABLED);
                Log.w(TAG, "Depth not supported on this device");
            }
            arSession.configure(config);

            Log.i(TAG, "ARCore ready");
        } catch (UnavailableArcoreNotInstalledException e) {
            Toast.makeText(this, R.string.arcore_unavailable, Toast.LENGTH_LONG).show();
            Log.e(TAG, "ARCore not installed", e);
        } catch (Exception e) {
            Toast.makeText(this, "ARCore error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "ARCore initialization failed", e);
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            new String[]{Manifest.permission.CAMERA},
            PERMISSION_CODE
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeARSession();
            } else {
                Toast.makeText(this, R.string.camera_permission, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        depthRenderer = new DepthRenderer(this, this::updateDepthReading);

        // Generate the external OES texture ARCore renders the camera into.
        // We don't draw the camera background, but ARCore still requires it.
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        cameraTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        cameraTextureSet = false;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (displayRotationHelper != null) {
            displayRotationHelper.onSurfaceChanged(width, height);
        }
        if (depthRenderer != null) {
            depthRenderer.onSurfaceChanged(width, height);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        try {
            if (displayRotationHelper != null && arSession != null) {
                displayRotationHelper.updateSessionIfNeeded(arSession);
            }

            if (arSession != null) {
                // Register the camera texture with ARCore before the first update().
                if (!cameraTextureSet && cameraTextureId != 0) {
                    arSession.setCameraTextureName(cameraTextureId);
                    cameraTextureSet = true;
                }
                Frame frame = arSession.update();
                try {
                    Image depthImage = frame.acquireDepthImage16Bits();
                    if (depthImage != null && depthRenderer != null) {
                        int rotation = displayRotationHelper != null ? displayRotationHelper.getRotation() : 0;
                        depthRenderer.renderDepthMap(depthImage, frame.getTimestamp(), rotation);
                        depthImage.close();
                    }
                } catch (NotYetAvailableException e) {
                    // Depth not ready yet this frame — expected during the first frames.
                }
            }
        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Camera not available", e);
        } catch (Exception e) {
            Log.e(TAG, "Frame update error", e);
        }
    }

    private void updateDepthReading(final float depth, final float confidence) {
        if (depth > 0) {
            recordMeasurement(depth, confidence);
        }
        runOnUiThread(() -> {
            if (depth <= 0) {
                depthTextView.setText(R.string.depth_unknown);
                confidenceTextView.setText("no surface detected");
            } else if (depth < MIN_RANGE_M) {
                depthTextView.setText("too close");
                confidenceTextView.setText("move back (min ~0.5 m)");
            } else if (depth > MAX_RANGE_M) {
                depthTextView.setText("too far");
                confidenceTextView.setText("move closer (max ~8 m)");
            } else {
                depthTextView.setText(String.format(Locale.US, "%.2f m", depth));
                if (confidence < LOW_CONFIDENCE) {
                    confidenceTextView.setText(String.format(Locale.US, "confidence %.0f%% (low)", confidence * 100));
                } else {
                    confidenceTextView.setText(String.format(Locale.US, "confidence %.0f%%", confidence * 100));
                }
            }
        });
    }

    private void recordMeasurement(float depth, float confidence) {
        long now = System.currentTimeMillis();
        DepthMeasurement measurement = new DepthMeasurement(depth, now, confidence);

        synchronized (measurementHistory) {
            measurementHistory.add(measurement);
            while (measurementHistory.size() > MAX_HISTORY) {
                measurementHistory.remove(0);
            }
        }

        Log.d(TAG, String.format(Locale.US, "[%s] depth=%.3f m confidence=%.2f",
            logTimeFormat.format(new Date(now)), depth, confidence));
    }

    // ---------------------------------------------------------------------
    // Side panel (navigation drawer)
    // ---------------------------------------------------------------------

    private void setupDrawer() {
        drawerLayout = findViewById(R.id.drawerLayout);
        drawerContent = findViewById(R.id.drawerContent);
        tabInstructions = findViewById(R.id.tabInstructions);
        tabHowItWorks = findViewById(R.id.tabHowItWorks);
        ImageButton menuButton = findViewById(R.id.menuButton);

        menuButton.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        tabInstructions.setOnClickListener(v -> selectTab(true));
        tabHowItWorks.setOnClickListener(v -> selectTab(false));

        selectTab(true);
    }

    private void selectTab(boolean instructions) {
        drawerContent.setText(instructions ? INSTRUCTIONS_TEXT : HOW_IT_WORKS_TEXT);
        tabInstructions.setTextColor(instructions ? 0xFFFFFFFF : 0x88FFFFFF);
        tabHowItWorks.setTextColor(instructions ? 0x88FFFFFF : 0xFFFFFFFF);
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    private static final String INSTRUCTIONS_TEXT =
        "HOW TO USE\n\n" +
        "• Grant camera permission on first launch.\n\n" +
        "• Point the camera at objects 0.5 m to 8 m away — ARCore's reliable depth range.\n\n" +
        "• Move the phone slowly. Depth is built from motion, so gentle panning improves the map.\n\n" +
        "• The green crosshair shows the distance to whatever it points at.\n\n" +
        "• Reading the map:\n" +
        "   dark  = near\n" +
        "   bright = far\n" +
        "   black  = no depth data\n\n" +
        "• Confidence % shows how much to trust the reading. Low % = hard-to-measure surface.\n\n" +
        "• Expect poor results when:\n" +
        "   - closer than ~0.5 m\n" +
        "   - farther than ~8 m\n" +
        "   - blank, shiny or transparent surfaces\n" +
        "   - poor light, or a perfectly still phone\n\n" +
        "• Share Log exports your last 20 readings as CSV (timestamp, depth, confidence).\n\n" +
        "• Requires Google Play Services for AR (ARCore).";

    private static final String HOW_IT_WORKS_TEXT =
        "THE TECHNOLOGY\n\n" +
        "This app uses Google ARCore's Depth API. This phone has a single rear camera, so " +
        "depth is estimated by \"depth from motion\" — like human stereo vision, but across " +
        "time instead of two eyes.\n\n" +
        "1. DEPTH FROM MOTION\n\n" +
        "As you move the phone, ARCore sees the same scene point from two positions. The gap " +
        "between them is the baseline b. A near point shifts a lot between views; a far point " +
        "barely shifts. That shift is the disparity d.\n\n" +
        "Depth Z comes from triangulation:\n\n" +
        "      Z = (f × b) / d\n\n" +
        "   Z = distance to the point\n" +
        "   f = focal length (pixels)\n" +
        "   b = baseline (phone movement)\n" +
        "   d = disparity (pixel shift)\n\n" +
        "Because Z depends on 1/d, tiny far-away shifts are noisy — so far and textureless " +
        "regions are less reliable, and a still phone (b ≈ 0) gives no depth.\n\n" +
        "2. THE PINHOLE CAMERA MODEL\n\n" +
        "A 3D point (X, Y, Z) projects to pixel (u, v):\n\n" +
        "      u = f × (X / Z) + cx\n" +
        "      v = f × (Y / Z) + cy\n\n" +
        "   (cx, cy) = optical center of the image.\n\n" +
        "Going from a pixel back to a 3D ray and solving for Z is what the depth estimate does.\n\n" +
        "3. THE DEPTH IMAGE (DEPTH16)\n\n" +
        "ARCore returns 16 bits per pixel:\n" +
        "   13 low bits  = depth in mm (0–8191)\n" +
        "   3 high bits  = confidence\n\n" +
        "The 13-bit limit is why max range is 8.191 m. Confidence is encoded as 0 → 100%, " +
        "1 → 0%, and 2–7 → 1/7 … 6/7.\n\n" +
        "4. WHAT THIS APP DOES\n\n" +
        "   • reads the raw 16-bit depth buffer each frame\n" +
        "   • averages a 5×5 window at the center for a stable distance\n" +
        "   • rotates the sensor image to match the portrait screen\n" +
        "   • draws depth as grayscale on the GPU (OpenGL ES)\n\n" +
        "5. WHY MOVEMENT & TEXTURE MATTER\n\n" +
        "Triangulation needs a baseline (move the phone) and matchable features (textured, " +
        "well-lit surfaces). A blank wall gives nothing to match, so depth there is sparse or " +
        "low-confidence.";

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (arSession != null) {
                arSession.resume();
            }
        } catch (CameraNotAvailableException e) {
            Toast.makeText(this, R.string.camera_error, Toast.LENGTH_LONG).show();
        }
        glSurfaceView.onResume();
        if (displayRotationHelper != null) {
            displayRotationHelper.onResume();
        }
    }

    @Override
    protected void onPause() {
        glSurfaceView.onPause();
        if (arSession != null) {
            arSession.pause();
        }
        if (displayRotationHelper != null) {
            displayRotationHelper.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (arSession != null) {
            arSession.close();
            arSession = null;
        }
        super.onDestroy();
    }
}
