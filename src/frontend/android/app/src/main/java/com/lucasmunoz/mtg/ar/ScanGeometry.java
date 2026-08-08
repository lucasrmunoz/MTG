package com.lucasmunoz.mtg.ar;

/**
 * Pure math for projecting detected card quads onto the screen. Quads are float[8] corner
 * pairs, deliberately not android.graphics types — the JVM test runner stubs Android classes
 * to no-ops, so staying Android-free is what keeps this testable.
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

    /**
     * Whether the quad's centre falls inside a horizontal band of the box: the full box width,
     * vertically from topFrac to bottomFrac of the box's height. Box is {left, top, right,
     * bottom} in the same coordinate space as the quad — the guide-box scanner uses view space,
     * where the aimed card is upright, so "top band" means the card's title bar.
     */
    static boolean centerInBand(float[] quad, float[] box, float topFrac, float bottomFrac) {
        float cx = 0f;
        float cy = 0f;
        for (int i = 0; i < 4; i++) {
            cx += quad[i * 2];
            cy += quad[i * 2 + 1];
        }
        cx /= 4f;
        cy /= 4f;
        float height = box[3] - box[1];
        return cx >= box[0] && cx <= box[2]
                && cy >= box[1] + topFrac * height
                && cy <= box[1] + bottomFrac * height;
    }
}
