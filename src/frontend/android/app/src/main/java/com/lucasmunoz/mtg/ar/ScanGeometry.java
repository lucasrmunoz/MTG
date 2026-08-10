package com.lucasmunoz.mtg.ar;

/**
 * Pure math for mapping the on-screen guide box back into camera-sensor pixels. Deliberately
 * not android.graphics types — the JVM test runner stubs Android classes to no-ops, so
 * staying Android-free is what keeps this testable.
 */
final class ScanGeometry {

    private ScanGeometry() {}

    /**
     * Sensor-pixel bounding box {left, top, right, bottom} for a view-space box, given where
     * three image corners land on screen: cornerViews = view coords of image (0,0), (width,0)
     * and (0,height), packed as {x00, y00, x10, y10, x01, y01} — exactly what one
     * transformCoordinates2d call yields.
     *
     * Inverts the affine display transform, so rotated or mirrored display transforms map
     * correctly. The result is clamped to the image; null means the transform is degenerate
     * or the box misses the image entirely.
     */
    static int[] viewBoxToImageBox(
            float[] viewBox, int imageWidth, int imageHeight, float[] cornerViews) {
        float originX = cornerViews[0];
        float originY = cornerViews[1];
        float alongWidthX = cornerViews[2] - originX;
        float alongWidthY = cornerViews[3] - originY;
        float alongHeightX = cornerViews[4] - originX;
        float alongHeightY = cornerViews[5] - originY;
        float det = alongWidthX * alongHeightY - alongWidthY * alongHeightX;
        if (Math.abs(det) < 1e-3f) {
            return null;
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float[] cornerXs = {viewBox[0], viewBox[2], viewBox[2], viewBox[0]};
        float[] cornerYs = {viewBox[1], viewBox[1], viewBox[3], viewBox[3]};
        for (int i = 0; i < 4; i++) {
            float dx = cornerXs[i] - originX;
            float dy = cornerYs[i] - originY;
            float u = (dx * alongHeightY - dy * alongHeightX) / det;
            float v = (alongWidthX * dy - alongWidthY * dx) / det;
            float pixelX = u * imageWidth;
            float pixelY = v * imageHeight;
            minX = Math.min(minX, pixelX);
            minY = Math.min(minY, pixelY);
            maxX = Math.max(maxX, pixelX);
            maxY = Math.max(maxY, pixelY);
        }

        int left = Math.max(0, (int) Math.floor(minX));
        int top = Math.max(0, (int) Math.floor(minY));
        int right = Math.min(imageWidth, (int) Math.ceil(maxX));
        int bottom = Math.min(imageHeight, (int) Math.ceil(maxY));
        if (right - left <= 0 || bottom - top <= 0) {
            return null;
        }
        return new int[] {left, top, right, bottom};
    }
}
