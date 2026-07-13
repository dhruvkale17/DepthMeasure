package com.example.depthmeasure;

import android.app.Activity;
import android.view.Surface;
import com.google.ar.core.Session;

public class DisplayRotationHelper {

    private final Activity activity;
    private int rotation = Surface.ROTATION_0;
    private boolean geometrySet = false;

    public DisplayRotationHelper(Activity activity) {
        this.activity = activity;
    }

    public void onResume() {
        updateDeviceRotation();
    }

    public void onPause() {
        // No-op
    }

    public void updateSessionIfNeeded(Session session) {
        if (session == null) {
            return;
        }

        int currentRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        if (!geometrySet || currentRotation != rotation) {
            geometrySet = true;
            rotation = currentRotation;
            session.setDisplayGeometry(
                rotation,
                activity.getResources().getDisplayMetrics().widthPixels,
                activity.getResources().getDisplayMetrics().heightPixels
            );
        }
    }

    public void onSurfaceChanged(int width, int height) {
        updateDeviceRotation();
    }

    private void updateDeviceRotation() {
        rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
    }

    public int getRotation() {
        return rotation;
    }
}
