package com.lucasmunoz.mtg.ar;

/**
 * Pure math for projecting detected card quads onto the screen. Quads are float[8] corner
 * pairs, deliberately not android.graphics types — the JVM test runner stubs Android classes
 * to no-ops, so staying Android-free is what keeps this testable.
 */
final class ScanGeometry {

    private ScanGeometry() {}

    /**
     * View-space corners for a sensor-pixel quad, given where three image corners land on
     * screen: cornerViews = view coords of image (0,0), (width,0) and (0,height), packed as
     * {x00, y00, x10, y10, x01, y01} — exactly what one transformCoordinates2d call yields.
     *
     * Exact for the affine display transform; corners map individually, so rotated or mirrored
     * display transforms come through as the same polygon, just reoriented.
     */
    static float[] imageQuadToView(
            float[] quad, int imageWidth, int imageHeight, float[] cornerViews) {
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            float u = quad[i * 2] / imageWidth;
            float v = quad[i * 2 + 1] / imageHeight;
            out[i * 2] = cornerViews[0]
                    + u * (cornerViews[2] - cornerViews[0])
                    + v * (cornerViews[4] - cornerViews[0]);
            out[i * 2 + 1] = cornerViews[1]
                    + u * (cornerViews[3] - cornerViews[1])
                    + v * (cornerViews[5] - cornerViews[1]);
        }
        return out;
    }
}
