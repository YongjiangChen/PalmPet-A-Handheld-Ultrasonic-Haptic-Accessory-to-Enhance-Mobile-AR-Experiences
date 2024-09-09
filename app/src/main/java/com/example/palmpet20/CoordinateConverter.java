package com.example.palmpet20;

import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;

public class CoordinateConverter {
    private static final float CUBE_HALF_SIZE = 0.025f; // Half size of the cube in meters
    private static final float MAX_Z = 0.05f; // Maximum Z distance in meters
    private static final float scale = 20.0f; // Because ±0.025 is 1/20 of a meter

    public static float[] convertToRelativeCoordinates(Anchor anchor, Camera camera) {
        float[] arCorePosition = new float[3];
        anchor.getPose().getTranslation(arCorePosition, 0);

        float[] cameraSpacePosition = new float[3];
        camera.getPose().inverse().transformPoint(arCorePosition, 0, cameraSpacePosition, 0);

        // Calculate the scale factor based on Z coordinate
        float scaleFactor = Math.max(Math.abs(cameraSpacePosition[2]) / MAX_Z, 1.0f)/2;

        // Scale X and Y coordinates
        float scaledX = cameraSpacePosition[1] / scaleFactor;
        float scaledY = cameraSpacePosition[0] / scaleFactor;

        // Clamp X and Y to the cube boundaries
        // IMPORTANT: notice that the x, y values are modified due to the orientation of the placement for the mini PAT board
        float y = -Math.max(-CUBE_HALF_SIZE, Math.min(CUBE_HALF_SIZE, scaledX));
        float x = Math.max(-CUBE_HALF_SIZE, Math.min(CUBE_HALF_SIZE, scaledY));

        // Clamp Z to the cube boundaries (always positive in camera space)
        float z = Math.max(0, Math.min(MAX_Z, Math.abs(cameraSpacePosition[2])/ scale));

        return new float[]{x, y, z, 1.0f};
    }
}