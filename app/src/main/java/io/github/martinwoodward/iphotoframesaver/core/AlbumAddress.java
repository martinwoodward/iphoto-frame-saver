package io.github.martinwoodward.iphotoframesaver.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Accepts only public album routes, never arbitrary URLs or account pages. */
public final class AlbumAddress {
    public final String token;
    public final boolean modern;
    public final boolean china;
    public final String canonical;

    private AlbumAddress(String token, boolean modern, boolean china) {
        this.token = token;
        this.modern = modern;
        this.china = china;
        String domain = china ? "icloud.com.cn" : "icloud.com";
        canonical = modern ? "https://photos." + domain + "/shared/album/" + token + "/"
                : "https://www." + domain + "/sharedalbum/#" + token;
    }

    public static AlbumAddress parse(String text) {
        try {
            URI u = new URI(text.trim());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo() != null
                    || u.getPort() != -1 || u.getQuery() != null) throw invalid();
            boolean china = host.endsWith(".com.cn");
            if (host.equals("photos.icloud.com") || host.equals("photos.icloud.com.cn")) {
                String path = u.getPath();
                if (u.getFragment() == null && path.matches("/shared/album/[A-Za-z0-9_-]{10,200}/?")) {
                    return new AlbumAddress(path.split("/")[3], true, china);
                }
            }
            if (host.equals("www.icloud.com") || host.equals("icloud.com")
                    || host.equals("www.icloud.com.cn") || host.equals("icloud.com.cn")) {
                String path = u.getPath();
                String token = u.getFragment();
                if (("/sharedalbum/".equals(path) || "/sharedalbum".equals(path))
                        && token != null && token.matches("[A-Za-z0-9_-]{10,200}")) {
                    return new AlbumAddress(token, false, china);
                }
            }
        } catch (URISyntaxException e) {
            throw invalid();
        }
        throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Enter a public HTTPS iCloud shared album link.");
    }
}
