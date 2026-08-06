import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Replays a captured scan corpus on the desktop — phase 0 of
 * docs/proposals/full-card-scanning.md. Loads every PGM frame the in-app recorder saved,
 * reports per-frame stats that flag unusable captures (black or blown-out frames), and
 * optionally writes upright PNGs for eyeballing. Phase 1 plugs the card-quad detector in
 * between {@link #load} and the report, which is when desktop OpenCV joins.
 *
 * <p>Pull the corpus off the phone, then run — no build step, plain JDK:
 *
 * <pre>
 *   adb pull /sdcard/Android/data/com.lucasmunoz.mtg/files/scan-corpus corpus
 *   java ReplayHarness.java corpus [pngOutDir]
 * </pre>
 */
public final class ReplayHarness {

    /** One corpus frame: luma pixels row-major at width×height, plus the sensor rotation. */
    record CorpusFrame(String name, int width, int height, int rotationDegrees, byte[] pixels) {}

    /** Mean luma outside these bounds means the frame carries no tunable signal. */
    private static final double MIN_USABLE_MEAN = 10;
    private static final double MAX_USABLE_MEAN = 245;

    private static final int ACCEPTANCE_FRAMES = 50;

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: java ReplayHarness.java <corpusDir> [pngOutDir]");
            System.exit(2);
        }
        Path corpusDir = Path.of(args[0]);
        Path pngOut = args.length == 2 ? Path.of(args[1]) : null;
        if (pngOut != null) {
            Files.createDirectories(pngOut);
        }

        List<Path> files;
        try (Stream<Path> entries = Files.list(corpusDir)) {
            files = new ArrayList<>(entries
                    .filter(p -> p.getFileName().toString().endsWith(".pgm"))
                    .sorted()
                    .toList());
        }
        if (files.isEmpty()) {
            System.err.println("No .pgm frames in " + corpusDir);
            System.exit(1);
        }

        int usable = 0;
        List<String> suspects = new ArrayList<>();
        for (Path file : files) {
            CorpusFrame frame = load(file);
            double mean = meanLuma(frame.pixels());
            boolean ok = mean >= MIN_USABLE_MEAN && mean <= MAX_USABLE_MEAN;
            if (ok) {
                usable++;
            } else {
                suspects.add(String.format("%s (mean luma %.1f — %s)", frame.name(), mean,
                        mean < MIN_USABLE_MEAN ? "black" : "blown out"));
            }
            System.out.printf("%s  %dx%d rot=%d meanLuma=%.1f%s%n",
                    frame.name(), frame.width(), frame.height(), frame.rotationDegrees(),
                    mean, ok ? "" : "  SUSPECT");
            if (pngOut != null) {
                String pngName = frame.name().replaceAll("\\.pgm$", ".png");
                ImageIO.write(toUpright(frame), "png", pngOut.resolve(pngName).toFile());
            }
        }

        System.out.println();
        System.out.printf("%d frames, %d usable, %d suspect%n",
                files.size(), usable, suspects.size());
        for (String suspect : suspects) {
            System.out.println("  suspect: " + suspect);
        }
        System.out.printf("Phase 0 acceptance: %d/%d usable frames%s%n",
                usable, ACCEPTANCE_FRAMES,
                usable >= ACCEPTANCE_FRAMES ? " — met" : "");
    }

    /** Parses one recorder PGM: binary P5, maxval 255, rotation in the header comment. */
    static CorpusFrame load(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        Header header = parseHeader(path, data);
        int expected = header.width * header.height;
        if (data.length - header.pixelOffset < expected) {
            throw new IOException(path + ": " + (data.length - header.pixelOffset)
                    + " pixel bytes for a " + header.width + "x" + header.height + " frame");
        }
        byte[] pixels = new byte[expected];
        System.arraycopy(data, header.pixelOffset, pixels, 0, expected);
        return new CorpusFrame(path.getFileName().toString(),
                header.width, header.height, header.rotationDegrees, pixels);
    }

    private record Header(int width, int height, int rotationDegrees, int pixelOffset) {}

    private static final Pattern ROTATION = Pattern.compile("rotation=(\\d+)");

    private static Header parseHeader(Path path, byte[] data) throws IOException {
        // P5 header: magic, width, height, maxval as whitespace-separated tokens; a '#' starts
        // a comment running to end of line. One whitespace byte after maxval, then pixels.
        List<String> tokens = new ArrayList<>();
        int rotation = 0;
        int i = 0;
        while (tokens.size() < 4 && i < data.length) {
            char c = (char) (data[i] & 0xFF);
            if (c == '#') {
                int start = i;
                while (i < data.length && data[i] != '\n') {
                    i++;
                }
                String comment = new String(data, start, i - start, StandardCharsets.US_ASCII);
                Matcher m = ROTATION.matcher(comment);
                if (m.find()) {
                    rotation = Integer.parseInt(m.group(1));
                }
            } else if (Character.isWhitespace(c)) {
                i++;
            } else {
                int start = i;
                while (i < data.length && !Character.isWhitespace((char) (data[i] & 0xFF))) {
                    i++;
                }
                tokens.add(new String(data, start, i - start, StandardCharsets.US_ASCII));
            }
        }
        if (tokens.size() < 4 || !tokens.get(0).equals("P5") || !tokens.get(3).equals("255")) {
            throw new IOException(path + " is not a recorder PGM (binary P5, maxval 255)");
        }
        return new Header(Integer.parseInt(tokens.get(1)), Integer.parseInt(tokens.get(2)),
                rotation, i + 1);
    }

    static double meanLuma(byte[] pixels) {
        long sum = 0;
        for (byte pixel : pixels) {
            sum += pixel & 0xFF;
        }
        return (double) sum / pixels.length;
    }

    /** The frame as a grayscale image rotated to how the phone screen showed it. */
    static BufferedImage toUpright(CorpusFrame frame) {
        int w = frame.width();
        int h = frame.height();
        boolean quarter = frame.rotationDegrees() == 90 || frame.rotationDegrees() == 270;
        BufferedImage image = new BufferedImage(
                quarter ? h : w, quarter ? w : h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int luma = frame.pixels()[y * w + x] & 0xFF;
                int rgb = 0xFF000000 | (luma << 16) | (luma << 8) | luma;
                switch (frame.rotationDegrees()) {
                    case 90 -> image.setRGB(h - 1 - y, x, rgb);
                    case 180 -> image.setRGB(w - 1 - x, h - 1 - y, rgb);
                    case 270 -> image.setRGB(y, w - 1 - x, rgb);
                    default -> image.setRGB(x, y, rgb);
                }
            }
        }
        return image;
    }

    private ReplayHarness() {}
}
