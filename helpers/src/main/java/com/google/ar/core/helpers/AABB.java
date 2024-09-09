
package com.google.ar.core.helpers;

// An axis-aligned bounding box is defined by the minimum and maximum extends in each dimension.
public class AABB {
    public float minX = Float.MAX_VALUE;
    public float minY = Float.MAX_VALUE;
    public float minZ = Float.MAX_VALUE;
    public float maxX = -Float.MAX_VALUE;
    public float maxY = -Float.MAX_VALUE;
    public float maxZ = -Float.MAX_VALUE;
    private float totalConfidence = 0;
    private int pointCount = 0;

    public void update(float x, float y, float z) {
        minX = Math.min(x, minX);
        minY = Math.min(y, minY);
        minZ = Math.min(z, minZ);
        maxX = Math.max(x, maxX);
        maxY = Math.max(y, maxY);
        maxZ = Math.max(z, maxZ);
    }

    public boolean contains(float x, float y, float z) {
        return x >= minX && x <= maxX &&
                y >= minY && y <= maxY &&
                z >= minZ && z <= maxZ;
    }

    public void addConfidence(float confidence) {
        totalConfidence += confidence;
        pointCount++;
    }

    public float getAverageConfidence() {
        return pointCount > 0 ? totalConfidence / pointCount : 0;
    }

    public float[] getCenter() {
        return new float[] {
                (minX + maxX) / 2,
                (minY + maxY) / 2,
                (minZ + maxZ) / 2
        };
    }

    public void reset() {
        minX = Float.MAX_VALUE;
        minY = Float.MAX_VALUE;
        minZ = Float.MAX_VALUE;
        maxX = -Float.MAX_VALUE;
        maxY = -Float.MAX_VALUE;
        maxZ = -Float.MAX_VALUE;
        totalConfidence = 0;
        pointCount = 0;
    }
}
