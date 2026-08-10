package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** The guide crop's contrast stretch: strided extraction plus percentile normalisation. */
public class LumaOpsTest {

    @Test
    public void cropRespectsStrideAndOffset() {
        // A 4x3 plane at stride 5; values within 8 of each other skip the stretch, so the
        // returned bytes prove which positions were read.
        byte[] plane = {
                0, 1, 2, 3, 99,
                10, 100, 101, 13, 99,
                20, 102, 103, 23, 99,
        };
        assertArrayEquals(new byte[] {100, 101, 102, 103},
                LumaOps.cropStretched(plane, 5, 1, 1, 2, 2));
    }

    @Test
    public void stretchMapsTheRangeOntoFullContrast() {
        // Low percentile 100 -> 0, high 160 -> 255, order preserved and linear.
        byte[] plane = {100, 120, (byte) 140, (byte) 160};
        assertArrayEquals(new byte[] {0, 85, (byte) 170, (byte) 255},
                LumaOps.cropStretched(plane, 4, 0, 0, 4, 1));
    }

    @Test
    public void nearFlatCropReturnsUnstretched() {
        // Amplifying a featureless crop would only turn sensor noise into fake edges.
        byte[] plane = {(byte) 128, (byte) 130, (byte) 129, (byte) 128};
        assertArrayEquals(new byte[] {(byte) 128, (byte) 130, (byte) 129, (byte) 128},
                LumaOps.cropStretched(plane, 4, 0, 0, 4, 1));
    }

    @Test
    public void glareAndShadowSpecklesDoNotOwnThePercentiles() {
        // 100 pixels: one blown-out glare pixel and one black pixel; the stretch anchors on
        // the 2nd and 98th percentiles, so the mid-range text contrast still expands.
        byte[] plane = new byte[100];
        for (int i = 0; i < 100; i++) {
            plane[i] = (byte) (i < 50 ? 90 : 140);
        }
        plane[0] = 0;
        plane[99] = (byte) 255;
        byte[] out = LumaOps.cropStretched(plane, 100, 0, 0, 100, 1);
        assertArrayEquals(new byte[] {0, 0}, new byte[] {out[0], out[1]});
        // 90 -> 0 and 140 -> 255: the dominant two levels span the full range.
        assertArrayEquals(new byte[] {0, (byte) 255}, new byte[] {out[1], out[98]});
    }

    @Test
    public void cropBeyondThePlaneThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> LumaOps.cropStretched(new byte[20], 5, 2, 0, 4, 4));
    }
}
