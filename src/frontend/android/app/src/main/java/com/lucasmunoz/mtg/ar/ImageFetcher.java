package com.lucasmunoz.mtg.ar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads card scans from Scryfall's CDN for display and reference-image registration. */
final class ImageFetcher {

    private ImageFetcher() {}

    static Bitmap fetch(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        // Scryfall rejects default HTTP-library User-Agents on its API; its CDN is laxer, but
        // identifying ourselves is what they ask of every client.
        connection.setRequestProperty("User-Agent", "MTGCardLookup/1.0 (Android AR)");

        try (InputStream stream = connection.getInputStream()) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            if (bitmap == null) {
                throw new IOException("Could not decode the card image at " + url);
            }
            return bitmap;
        } finally {
            connection.disconnect();
        }
    }
}
