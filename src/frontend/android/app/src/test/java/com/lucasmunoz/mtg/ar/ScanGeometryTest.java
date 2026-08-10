package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The guide-box projection math: inverting the display transform back into sensor pixels.
 * Test coordinates are chosen to be exact in float arithmetic, so the floor/ceil at the end
 * cannot wobble the expected pixels by one.
 */
public class ScanGeometryTest {

    @Test
    public void identityTransformMapsDirectly() {
        // Image corners land on the view unchanged: view coords are sensor coords.
        float[] corners = {0, 0, 640, 0, 0, 480};
        assertArrayEquals(new int[] {160, 120, 320, 240},
                ScanGeometry.viewBoxToImageBox(
                        new float[] {160, 120, 320, 240}, 640, 480, corners));
    }

    @Test
    public void scaledDisplayTransformDividesBackOut() {
        // Image (0,0)->(0,0), (512,0)->(1024,0), (0,512)->(0,1024): a 2x scale.
        float[] corners = {0, 0, 1024, 0, 0, 1024};
        assertArrayEquals(new int[] {64, 100, 128, 200},
                ScanGeometry.viewBoxToImageBox(
                        new float[] {128, 200, 256, 400}, 512, 512, corners));
    }

    @Test
    public void rotatedDisplayTransformUnrotatesIntoSensorSpace() {
        // The 640x480 sensor image shown upright as 480x640: image (0,0) lands at the view's
        // top-right, (640,0) at the bottom-right, (0,480) at the top-left. A view box at
        // l=120 t=200 r=180 b=300 came from sensor pixels x∈[200,300], y∈[480-180, 480-120].
        float[] corners = {480, 0, 480, 640, 0, 0};
        assertArrayEquals(new int[] {200, 300, 300, 360},
                ScanGeometry.viewBoxToImageBox(
                        new float[] {120, 200, 180, 300}, 640, 480, corners));
    }

    @Test
    public void resultIsClampedToTheImage() {
        float[] corners = {0, 0, 640, 0, 0, 480};
        assertArrayEquals(new int[] {0, 0, 640, 480},
                ScanGeometry.viewBoxToImageBox(
                        new float[] {-50, -50, 700, 500}, 640, 480, corners));
    }

    @Test
    public void boxEntirelyOffTheImageIsNull() {
        float[] corners = {0, 0, 640, 0, 0, 480};
        assertNull(ScanGeometry.viewBoxToImageBox(
                new float[] {700, 100, 800, 200}, 640, 480, corners));
    }

    @Test
    public void degenerateTransformIsNull() {
        // All three image corners land on one view point: nothing can be inverted.
        float[] corners = {10, 10, 10, 10, 10, 10};
        assertNull(ScanGeometry.viewBoxToImageBox(
                new float[] {0, 0, 100, 100}, 640, 480, corners));
    }
}
