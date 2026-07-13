package com.example.depthmeasure;

/**
 * Immutable value object holding a single depth reading.
 */
public class DepthMeasurement {
    public final float depthMeters;
    public final long timestampMillis;
    public final float confidence;

    public DepthMeasurement(float depthMeters, long timestampMillis, float confidence) {
        this.depthMeters = depthMeters;
        this.timestampMillis = timestampMillis;
        this.confidence = confidence;
    }
}
