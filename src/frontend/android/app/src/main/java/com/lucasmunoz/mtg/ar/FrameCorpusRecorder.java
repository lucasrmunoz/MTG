package com.lucasmunoz.mtg.ar;

import android.media.Image;
import android.os.SystemClock;
import android.util.Log;
import com.google.ar.core.Frame;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.ResourceExhaustedException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Saves the camera's Y-plane frames to app files while capture is toggled on — the corpus that
 * full-card-scanning thresholds are tuned against offline (docs/proposals/full-card-scanning.md,
 * phase 0). Debug builds only; the activity never constructs one otherwise.
 *
 * Each frame lands as a binary PGM: the luma bytes stride-compacted to width×height, with the
 * sensor rotation and capture time in a header comment. PGM keeps every frame openable in any
 * image viewer and trivially parseable by the desktop replay harness (tools/scan-harness) —
 * exactly the pixels the on-device detector will see, in a container that needs no sidecar
 * metadata file.
 */
final class FrameCorpusRecorder {

    interface Listener {
        /** Called on the writer thread after each frame lands on disk. */
        void onFrameSaved(int savedCount);

        /** Called on the writer thread when a write fails; recording has already stopped. */
        void onSaveFailed(Exception e);
    }

    private static final String TAG = "FrameCorpusRecorder";

    /** Frames per position are redundant; half a second apart samples a moving phone instead. */
    private static final long CAPTURE_INTERVAL_MS = 500;

    private final File directory;
    private final Listener listener;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    /** Distinguishes this session's files from earlier captures in the same directory. */
    private final String sessionPrefix;

    private volatile boolean recording;
    private final AtomicInteger savedCount = new AtomicInteger();
    /** GL thread only. */
    private long lastCaptureMs;
    private int sequence;

    FrameCorpusRecorder(File directory, Listener listener) {
        this.directory = directory;
        this.listener = listener;
        this.sessionPrefix = String.format(Locale.ROOT, "frame-%d", System.currentTimeMillis());
    }

    boolean isRecording() {
        return recording;
    }

    void setRecording(boolean recording) {
        this.recording = recording;
    }

    int savedCount() {
        return savedCount.get();
    }

    File directory() {
        return directory;
    }

    /** Called from the GL thread once per rendered frame; a cheap no-op unless capture is due. */
    void maybeCapture(Frame frame) {
        if (!recording) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastCaptureMs < CAPTURE_INTERVAL_MS) {
            return;
        }

        Image image;
        try {
            image = frame.acquireCameraImage();
        } catch (NotYetAvailableException e) {
            return;
        } catch (ResourceExhaustedException e) {
            // The identifier is holding the CPU image budget right now; a later frame will do.
            return;
        }
        // From here every outcome — saved, skipped, failed — waits a full interval to recur.
        lastCaptureMs = now;

        byte[] raw;
        int width;
        int height;
        int rowStride;
        int pixelStride;
        try {
            Image.Plane luma = image.getPlanes()[0];
            ByteBuffer buffer = luma.getBuffer();
            raw = new byte[buffer.remaining()];
            buffer.get(raw);
            width = image.getWidth();
            height = image.getHeight();
            rowStride = luma.getRowStride();
            pixelStride = luma.getPixelStride();
        } finally {
            // The buffer is only valid while the image is held; the copy above outlives it.
            image.close();
        }
        if (pixelStride != 1) {
            // YUV_420_888 guarantees a packed Y plane; anything else would corrupt the corpus.
            Log.w(TAG, "Skipping frame with Y-plane pixel stride " + pixelStride);
            return;
        }

        int seq = sequence++;
        long capturedAtMs = System.currentTimeMillis();
        writer.execute(() -> write(seq, raw, width, height, rowStride, capturedAtMs));
    }

    private void write(
            int seq, byte[] raw, int width, int height, int rowStride, long capturedAtMs) {
        File file = new File(directory, String.format(Locale.ROOT, "%s-%04d.pgm",
                sessionPrefix, seq));
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Could not create corpus directory " + directory);
            }
            byte[] pgm = encodePgm(raw, width, height, rowStride,
                    CardIdentifier.ROTATION_DEGREES, capturedAtMs);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(pgm);
            }
            listener.onFrameSaved(savedCount.incrementAndGet());
        } catch (IOException | RuntimeException e) {
            // One failed write means the rest would fail too (storage full, dir gone, a buffer
            // the encoder rejects): stop rather than silently drop frames the user believes
            // are being captured. RuntimeException included — an uncaught throw would die
            // invisibly in the executor.
            Log.w(TAG, "Stopping capture: could not write " + file, e);
            recording = false;
            listener.onSaveFailed(e);
        }
    }

    /**
     * A binary PGM (P5) of the Y plane, rows compacted from rowStride to width. The header
     * comment carries the sensor-to-display rotation and capture time, so the corpus file is
     * self-describing.
     */
    static byte[] encodePgm(byte[] raw, int width, int height, int rowStride,
            int rotationDegrees, long capturedAtMs) {
        int lastRowEnd = (height - 1) * rowStride + width;
        if (raw.length < lastRowEnd) {
            throw new IllegalArgumentException("Y plane holds " + raw.length
                    + " bytes but " + width + "x" + height + " at stride " + rowStride
                    + " needs " + lastRowEnd);
        }
        String header = "P5\n"
                + "# mtg-scan-corpus rotation=" + rotationDegrees
                + " capturedAtMs=" + capturedAtMs + "\n"
                + width + " " + height + "\n255\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[headerBytes.length + width * height];
        System.arraycopy(headerBytes, 0, out, 0, headerBytes.length);
        for (int row = 0; row < height; row++) {
            System.arraycopy(raw, row * rowStride, out, headerBytes.length + row * width, width);
        }
        return out;
    }

    void close() {
        recording = false;
        writer.shutdown();
    }
}
