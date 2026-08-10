package com.lucasmunoz.mtg.ar;

/**
 * Pure luma-plane operations for the guide-box reader. Deliberately Android-free — the JVM
 * test runner stubs Android classes to no-ops, so staying byte-array-only keeps this testable.
 */
final class LumaOps {

    private LumaOps() {}

    /** Percentiles for the contrast stretch: robust to glare speckle and shadow pockets. */
    private static final int LOW_PERCENT = 2;
    private static final int HIGH_PERCENT = 98;

    /** A narrower range than this is a featureless crop; stretching would only amplify noise. */
    private static final int MIN_RANGE = 8;

    /**
     * Cuts a crop out of a strided luma plane and linearly stretches its contrast so the 2nd
     * percentile lands at 0 and the 98th at 255. Glare washes text toward white and a bright
     * background makes auto-exposure crush the card toward black; either way the letters come
     * back with the contrast OCR wants. A near-flat crop returns unstretched.
     */
    static byte[] cropStretched(
            byte[] luma, int rowStride, int left, int top, int width, int height) {
        int lastIndex = (top + height - 1) * rowStride + left + width;
        if (left < 0 || top < 0 || width <= 0 || height <= 0 || rowStride < left + width
                || luma.length < lastIndex) {
            throw new IllegalArgumentException("Crop " + width + "x" + height + " at ("
                    + left + "," + top + ") with stride " + rowStride + " exceeds "
                    + luma.length + " luma bytes");
        }

        byte[] crop = new byte[width * height];
        int[] histogram = new int[256];
        for (int row = 0; row < height; row++) {
            int src = (top + row) * rowStride + left;
            int dst = row * width;
            for (int col = 0; col < width; col++) {
                byte value = luma[src + col];
                crop[dst + col] = value;
                histogram[value & 0xFF]++;
            }
        }

        int total = width * height;
        int low = percentile(histogram, total * LOW_PERCENT / 100);
        int high = percentile(histogram, total * HIGH_PERCENT / 100);
        if (high - low < MIN_RANGE) {
            return crop;
        }

        for (int i = 0; i < crop.length; i++) {
            int stretchedValue = (((crop[i] & 0xFF) - low) * 255) / (high - low);
            crop[i] = (byte) Math.max(0, Math.min(255, stretchedValue));
        }
        return crop;
    }

    /** The smallest luma value whose cumulative pixel count exceeds rank. */
    private static int percentile(int[] histogram, int rank) {
        int cumulative = 0;
        for (int value = 0; value < histogram.length; value++) {
            cumulative += histogram[value];
            if (cumulative > rank) {
                return value;
            }
        }
        return histogram.length - 1;
    }
}
