package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public class FrameCorpusRecorderTest {

    @Test
    public void encodesHeaderAndCompactsRowStride() {
        // A 3x2 image stored at row stride 5: two padding bytes end each row.
        byte[] raw = {
                1, 2, 3, 99, 99,
                4, 5, 6, 99, 99,
        };
        byte[] pgm = FrameCorpusRecorder.encodePgm(raw, 3, 2, 5, 90, 1234L);

        String expectedHeader = "P5\n# mtg-scan-corpus rotation=90 capturedAtMs=1234\n3 2\n255\n";
        byte[] headerBytes = expectedHeader.getBytes(StandardCharsets.US_ASCII);
        assertEquals(headerBytes.length + 6, pgm.length);
        assertArrayEquals(headerBytes, Arrays.copyOfRange(pgm, 0, headerBytes.length));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6},
                Arrays.copyOfRange(pgm, headerBytes.length, pgm.length));
    }

    @Test
    public void lastRowMayStopAtWidthInsteadOfFullStride() {
        // Camera buffers commonly end at the last row's width, not its full stride.
        byte[] raw = {
                1, 2, 3, 99, 99,
                4, 5, 6,
        };
        byte[] pgm = FrameCorpusRecorder.encodePgm(raw, 3, 2, 5, 90, 0L);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6},
                Arrays.copyOfRange(pgm, pgm.length - 6, pgm.length));
    }

    @Test
    public void rejectsBufferShorterThanTheImage() {
        byte[] raw = new byte[7]; // 3x2 at stride 5 needs at least 8 bytes.
        assertThrows(IllegalArgumentException.class,
                () -> FrameCorpusRecorder.encodePgm(raw, 3, 2, 5, 90, 0L));
    }
}
