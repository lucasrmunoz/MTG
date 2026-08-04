package com.lucasmunoz.mtg.ar;

/**
 * Pure math for projecting OCR text boxes onto the screen. Boxes are float[4]
 * {left, top, right, bottom}, deliberately not android.graphics.RectF — the JVM test runner
 * stubs Android classes to no-ops, so keeping this Android-free is what makes the 90°
 * un-rotation testable.
 */
final class ScanGeometry {

    private ScanGeometry() {}

    /**
     * Sensor-pixel box for an ML Kit box reported in the rotated upright frame.
     *
     * rotationDegrees is the rotation that makes the sensor image upright — in this app only 0
     * and 90 occur (landscape camera, portrait-locked activity); anything else is a programming
     * error, not a case to guess at.
     */
    static float[] rotatedBoxToImage(float[] box, int imageHeight, int rotationDegrees) {
        if (rotationDegrees == 0) {
            return new float[] {box[0], box[1], box[2], box[3]};
        }
        if (rotationDegrees == 90) {
            // Upright (x', y') came from sensor (x, y) via x' = H - y, y' = x.
            return new float[] {box[1], imageHeight - box[2], box[3], imageHeight - box[0]};
        }
        throw new IllegalArgumentException("Unsupported rotation: " + rotationDegrees);
    }

    /**
     * View-space box for a sensor-pixel box, given where three image corners land on screen:
     * cornerViews = view coords of image (0,0), (width,0) and (0,height), packed as
     * {x00, y00, x10, y10, x01, y01} — exactly what one transformCoordinates2d call yields.
     *
     * Exact for the affine display transform. All four box corners are mapped and re-boxed, so
     * a rotated or mirrored display transform cannot produce an inside-out rect.
     */
    static float[] imageBoxToView(
            float[] box, int imageWidth, int imageHeight, float[] cornerViews) {
        float[][] corners = {
                {box[0], box[1]}, {box[2], box[1]}, {box[2], box[3]}, {box[0], box[3]},
        };
        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (float[] corner : corners) {
            float u = corner[0] / imageWidth;
            float v = corner[1] / imageHeight;
            float x = cornerViews[0]
                    + u * (cornerViews[2] - cornerViews[0])
                    + v * (cornerViews[4] - cornerViews[0]);
            float y = cornerViews[1]
                    + u * (cornerViews[3] - cornerViews[1])
                    + v * (cornerViews[5] - cornerViews[1]);
            left = Math.min(left, x);
            right = Math.max(right, x);
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
        }
        return new float[] {left, top, right, bottom};
    }
}
