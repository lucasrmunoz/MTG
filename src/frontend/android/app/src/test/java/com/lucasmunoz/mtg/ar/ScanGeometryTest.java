package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/** The quad projection math: mapping detected card corners onto the view. */
public class ScanGeometryTest {

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
