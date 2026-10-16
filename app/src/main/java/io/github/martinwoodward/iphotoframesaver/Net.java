package io.github.martinwoodward.iphotoframesaver;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

final class Net {
    private static boolean trusted(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        String[] roots = {"icloud.com", "icloud.com.cn", "icloud-content.com", "icloud-content.com.cn",
                "apple.com", "apple.com.cn", "apple-cloudkit.com"};
        for (String root : roots) if (h.equals(root) || h.endsWith("." + root)) return true;
        return false;
    }

    static HttpsURLConnection open(String text, byte[] body) throws IOException {
        URL url = new URL(text);
        for (int redirect = 0; redirect < 5; redirect++) {
            if (!"https".equals(url.getProtocol()) || !trusted(url.getHost())
                    || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)) {
                throw new IOException("Untrusted photo host");
            }
            HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "PhotoFrameSaver/1.0");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/plain;charset=UTF-8");
                c.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream out = c.getOutputStream()) { out.write(body); }
            }
            int status = c.getResponseCode();
            if (status >= 300 && status <= 399 && c.getHeaderField("Location") != null) {
                URL next = new URL(url, c.getHeaderField("Location"));
                c.disconnect();
                url = next;
                continue;
            }
            boolean legacyRouting = status == 330 && body != null
                    && url.getHost().contains("-sharedstreams.");
            if ((status < 200 || status >= 300) && !legacyRouting) {
                c.disconnect();
                throw new IOException("Photo service request failed");
            }
            return c;
        }
        throw new IOException("Photo service redirect limit");
    }

    static JSONObject post(String url, JSONObject body) throws FrameException {
        HttpsURLConnection c = null;
        try {
            c = open(url, body.toString().getBytes(StandardCharsets.UTF_8));
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                transfer(in, out, 32L * 1024 * 1024);
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new FrameException(FrameException.NETWORK);
        } catch (JSONException e) {
            throw new FrameException(FrameException.ALBUM);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static void download(String url, File destination) throws FrameException {
        HttpsURLConnection c = null;
        try {
            c = open(url, null);
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(destination)) {
                int expected = c.getContentLength();
                long received = transfer(in, out, 128L * 1024 * 1024);
                if (expected >= 0 && received != expected) throw new IOException("Incomplete photo response");
                out.getFD().sync();
            }
        } catch (IOException e) {
            if (destination.exists() && !destination.delete()) destination.deleteOnExit();
            throw new FrameException(FrameException.DOWNLOAD);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static long transfer(InputStream in, java.io.OutputStream out, long max) throws IOException {
        byte[] buffer = new byte[32768];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > max || Thread.currentThread().isInterrupted()) throw new IOException("Transfer stopped");
            out.write(buffer, 0, n);
        }
        return total;
    }
}
