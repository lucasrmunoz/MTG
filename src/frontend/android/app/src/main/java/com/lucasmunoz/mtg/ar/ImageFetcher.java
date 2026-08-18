package com.lucasmunoz.mtg.ar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Downloads card scans from Scryfall's CDN through a disk cache, so a later session shows and
 * tracks the same cards without re-downloading anything.
 *
 * The cache lives under the app's cache directory — Android may clear it under storage pressure,
 * and {@link #trim} keeps it bounded regardless. One URL is one file, keyed by the URL's hash and
 * never replaced: Scryfall image URLs are immutable per printing. Decoded bitmaps are not
 * retained here; callers keep only what they display.
 */
final class ImageFetcher {

    private static final String TAG = "ImageFetcher";

    /** The most the cache may hold; the oldest files go first when trimming. */
    private static final long MAX_CACHE_BYTES = 128L * 1024 * 1024;

    private final File directory;

    ImageFetcher(File directory) {
        this.directory = directory;
    }

    /** The scan at this URL, from the disk cache when present, downloaded into it otherwise. */
    Bitmap fetch(String url) throws IOException {
        File file = cacheFile(url);
        if (file.isFile()) {
            Bitmap cached = BitmapFactory.decodeFile(file.getPath());
            if (cached != null) {
                return cached;
            }
            // A corrupt cache entry decodes to null; the download below writes over it.
            Log.w(TAG, "Discarding an undecodable cached scan for " + url);
        }

        download(url, file);
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
        if (bitmap == null) {
            if (!file.delete()) {
                Log.w(TAG, "Could not delete the undecodable download " + file.getName());
            }
            throw new IOException("Could not decode the card image at " + url);
        }
        return bitmap;
    }

    /** Downloads into a temp file first, so a dropped connection never leaves a partial entry. */
    private void download(String url, File file) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create the image cache at " + directory);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        // Scryfall rejects default HTTP-library User-Agents on its API; its CDN is laxer, but
        // identifying ourselves is what they ask of every client.
        connection.setRequestProperty("User-Agent", "MTGCardLookup/1.0 (Android AR)");

        File temp = new File(file.getPath() + ".tmp");
        try (InputStream stream = connection.getInputStream();
                FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[16 * 1024];
            for (int read = stream.read(buffer); read != -1; read = stream.read(buffer)) {
                out.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }

        if (!temp.renameTo(file)) {
            if (!temp.delete()) {
                Log.w(TAG, "Could not delete the stranded download " + temp.getName());
            }
            throw new IOException("Could not move a downloaded scan into place at " + file);
        }
    }

    /** Deletes the oldest cached scans until the cache fits its budget. Call off the UI thread. */
    void trim() {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        long total = 0;
        for (File file : files) {
            total += file.length();
        }
        if (total <= MAX_CACHE_BYTES) {
            return;
        }

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            long size = file.length();
            if (file.delete()) {
                total -= size;
            } else {
                Log.w(TAG, "Could not delete cached scan " + file.getName());
            }
            if (total <= MAX_CACHE_BYTES) {
                return;
            }
        }
    }

    /** One file per URL, named by the URL's SHA-1 so any URL shape maps to a safe filename. */
    private File cacheFile(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                name.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return new File(directory, name.toString());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Every Android release ships SHA-1.", e);
        }
    }
}
