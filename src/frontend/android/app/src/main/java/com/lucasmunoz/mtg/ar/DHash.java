package com.lucasmunoz.mtg.ar;

import android.graphics.Bitmap;

/**
 * 64-bit difference hash for "is this the same picture" checks: the image shrinks to 9x8
 * grayscale and each bit records whether a pixel is brighter than its right neighbour. Robust
 * to lighting, exposure and mild blur — exactly the differences between a card photographed on
 * a table and Scryfall's scan of the same printing.
 */
final class DHash {

    private static final int HASH_WIDTH = 9;
    private static final int HASH_HEIGHT = 8;

    private DHash() {}

    static long of(Bitmap bitmap) {
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, HASH_WIDTH, HASH_HEIGHT, true);
        long hash = 0;
        int bit = 0;
        for (int y = 0; y < HASH_HEIGHT; y++) {
            for (int x = 0; x < HASH_WIDTH - 1; x++) {
                if (gray(scaled.getPixel(x, y)) > gray(scaled.getPixel(x + 1, y))) {
                    hash |= 1L << bit;
                }
                bit++;
            }
        }
        if (scaled != bitmap) {
            scaled.recycle();
        }
        return hash;
    }

    /** Hamming distance: how many of the 64 gradient bits disagree. */
    static int distance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static int gray(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }
}
