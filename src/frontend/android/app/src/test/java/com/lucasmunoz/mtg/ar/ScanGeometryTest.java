package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** The OCR-box projection math: un-rotating ML Kit boxes and mapping corners onto the view. */
public class ScanGeometryTest {

    @Test
    public void rotationZeroIsIdentity() {
        assertArrayEquals(new float[] {10, 20, 30, 40},
                ScanGeometry.rotatedBoxToImage(new float[] {10, 20, 30, 40}, 480, 0), 0.001f);
    }

    @Test
    public void rotationNinetyUnrotatesIntoSensorSpace() {
        // 640x480 sensor image shown upright as 480x640. A box in the upright frame at
        // l=100 t=200 r=150 b=300 came from sensor pixels x∈[200,300], y∈[480-150, 480-100].
        assertArrayEquals(new float[] {200, 330, 300, 380},
                ScanGeometry.rotatedBoxToImage(new float[] {100, 200, 150, 300}, 480, 90),
                0.001f);
    }

    @Test
    public void unsupportedRotationThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ScanGeometry.rotatedBoxToImage(new float[] {0, 0, 1, 1}, 480, 180));
    }

    @Test
    public void plainScalingMapsProportionally() {
        // Image (0,0)->(0,0), (640,0)->(1080,0), (0,480)->(0,810): a 1.6875x scale.
        float[] corners = {0, 0, 1080, 0, 0, 810};
        float[] quad = {64, 100, 128, 100, 128, 200, 64, 200};
        assertArrayEquals(
                new float[] {108, 168.75f, 216, 168.75f, 216, 337.5f, 108, 337.5f},
                ScanGeometry.imageQuadToView(quad, 640, 480, corners), 0.01f);
    }

    @Test
    public void rotatedDisplayTransformReorientsTheCorners() {
        // A display transform that rotates the image 90°: image (0,0) lands at the view's
        // top-right, (W,0) at the bottom-right, (0,H) at the top-left.
        float[] corners = {480, 0, 480, 640, 0, 0};
        float[] quad = {0, 0, 640, 0, 640, 480, 0, 480};
        assertArrayEquals(new float[] {480, 0, 480, 640, 0, 640, 0, 0},
                ScanGeometry.imageQuadToView(quad, 640, 480, corners), 0.01f);
    }

    @Test
    public void cropOffsetShiftsTheQuad() {
        // The view shows a centre crop: image x is shifted left by 60 view px.
        float[] corners = {-60, 0, 1020, 0, -60, 810};
        float[] quad = {0, 0, 640, 0, 640, 480, 0, 480};
        assertArrayEquals(new float[] {-60, 0, 1020, 0, 1020, 810, -60, 810},
                ScanGeometry.imageQuadToView(quad, 640, 480, corners), 0.01f);
    }
}
