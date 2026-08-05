package com.lucasmunoz.mtg.ar;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.geometry.Geometry;
import org.opencv.imgproc.Imgproc;

/**
 * Finds physical card rectangles in a grayscale camera frame and flattens them.
 *
 * Contours of the edge image are filtered down to convex quadrilaterals with a Magic card's
 * aspect ratio; each surviving quad can be perspective-warped into an upright card bitmap for
 * OCR and artwork verification. Detection is geometry only — identification still requires
 * Scryfall confirmation, never "this shape looks like a card, so it must be one".
 */
final class CardQuadDetector {

    /** One detected card-shaped quad, corners ordered so a short edge comes first. */
    static final class Quad {
        /** Sensor-pixel corners as x,y pairs, walking the quad from one short edge around. */
        final float[] corners;

        Quad(float[] corners) {
            this.corners = corners;
        }
    }

    /** A card must fill at least this fraction of the frame to be worth reading. */
    private static final double MIN_AREA_FRACTION = 0.015;
    /** Card aspect is 63/88 ≈ 0.716; perspective skews it, so the band is generous. */
    private static final double MIN_ASPECT = 0.50;
    private static final double MAX_ASPECT = 0.95;
    private static final int MAX_QUADS = 6;

    private CardQuadDetector() {}

    /** Card-shaped quads in a grayscale frame; corners in sensor-pixel coordinates. */
    static List<Quad> detect(byte[] gray, int rowStride, int width, int height) {
        Mat image = grayMat(gray, rowStride, width, height);
        Mat edges = new Mat();
        Imgproc.GaussianBlur(image, edges, new Size(5, 5), 0);
        Imgproc.Canny(edges, edges, 50, 150);
        Imgproc.dilate(edges, edges,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3)));

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(edges, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        hierarchy.release();
        edges.release();
        image.release();

        double minArea = MIN_AREA_FRACTION * width * height;
        List<Quad> quads = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            if (quads.size() < MAX_QUADS) {
                Quad quad = toCardQuad(contour, minArea);
                if (quad != null) {
                    quads.add(quad);
                }
            }
            contour.release();
        }
        return quads;
    }

    /** The quad flattened into an upright card bitmap (or upside down — OCR tries both). */
    static Bitmap warp(byte[] gray, int rowStride, int width, int height,
            Quad quad, int outWidth, int outHeight) {
        Mat image = grayMat(gray, rowStride, width, height);
        MatOfPoint2f src = new MatOfPoint2f(
                new Point(quad.corners[0], quad.corners[1]),
                new Point(quad.corners[2], quad.corners[3]),
                new Point(quad.corners[4], quad.corners[5]),
                new Point(quad.corners[6], quad.corners[7]));
        MatOfPoint2f dst = new MatOfPoint2f(
                new Point(0, 0),
                new Point(outWidth, 0),
                new Point(outWidth, outHeight),
                new Point(0, outHeight));
        Mat transform = Geometry.getPerspectiveTransform(src, dst);
        Mat flat = new Mat();
        Imgproc.warpPerspective(image, flat, transform, new Size(outWidth, outHeight));

        Bitmap bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(flat, bitmap);
        flat.release();
        transform.release();
        src.release();
        dst.release();
        image.release();
        return bitmap;
    }

    private static Quad toCardQuad(MatOfPoint contour, double minArea) {
        if (Geometry.contourArea(contour) < minArea) {
            return null;
        }
        MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
        MatOfPoint2f poly = new MatOfPoint2f();
        Geometry.approxPolyDP(points, poly, 0.02 * Geometry.arcLength(points, true), true);
        points.release();
        Point[] corners = poly.toArray();
        poly.release();
        if (corners.length != 4 || !Geometry.isContourConvex(new MatOfPoint(corners))) {
            return null;
        }
        float[] ordered = orderShortEdgeFirst(corners);
        return plausibleCardAspect(ordered) ? new Quad(ordered) : null;
    }

    /** Corners walked around the centroid, rotated so edge 0→1 is one of the short edges. */
    private static float[] orderShortEdgeFirst(Point[] corners) {
        double cx = (corners[0].x + corners[1].x + corners[2].x + corners[3].x) / 4;
        double cy = (corners[0].y + corners[1].y + corners[2].y + corners[3].y) / 4;
        Point[] sorted = corners.clone();
        Arrays.sort(sorted, (a, b) -> Double.compare(
                Math.atan2(a.y - cy, a.x - cx), Math.atan2(b.y - cy, b.x - cx)));

        double firstEdges = distance(sorted[0], sorted[1]) + distance(sorted[2], sorted[3]);
        double secondEdges = distance(sorted[1], sorted[2]) + distance(sorted[3], sorted[0]);
        int start = firstEdges <= secondEdges ? 0 : 1;

        float[] ordered = new float[8];
        for (int i = 0; i < 4; i++) {
            Point corner = sorted[(start + i) % 4];
            ordered[i * 2] = (float) corner.x;
            ordered[i * 2 + 1] = (float) corner.y;
        }
        return ordered;
    }

    private static boolean plausibleCardAspect(float[] quad) {
        double shortEdges = (edge(quad, 0, 1) + edge(quad, 2, 3)) / 2;
        double longEdges = (edge(quad, 1, 2) + edge(quad, 3, 0)) / 2;
        if (longEdges == 0) {
            return false;
        }
        double aspect = shortEdges / longEdges;
        return aspect >= MIN_ASPECT && aspect <= MAX_ASPECT;
    }

    private static double edge(float[] quad, int from, int to) {
        return Math.hypot(
                quad[to * 2] - quad[from * 2], quad[to * 2 + 1] - quad[from * 2 + 1]);
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    /** A single-channel Mat over the camera's Y plane, honouring its row stride. */
    private static Mat grayMat(byte[] gray, int rowStride, int width, int height) {
        Mat mat = new Mat(height, width, CvType.CV_8UC1);
        if (rowStride == width) {
            mat.put(0, 0, gray);
        } else {
            byte[] row = new byte[width];
            for (int y = 0; y < height; y++) {
                System.arraycopy(gray, y * rowStride, row, 0, width);
                mat.put(y, 0, row);
            }
        }
        return mat;
    }
}
