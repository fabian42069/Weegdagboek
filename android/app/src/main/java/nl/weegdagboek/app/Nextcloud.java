package nl.weegdagboek.app;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Kleine HTTP-helper voor Nextcloud: Login Flow v2, WebDAV en het intrekken van het app-wachtwoord. */
final class Nextcloud {

    static final String USER_AGENT = "Weegdagboek (Android)";

    static final class Response {
        final int status; final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }

    static String normalizeServer(String s) {
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.matches("(?i)^https?://.*")) s = "https://" + s;
        return s;
    }

    static String enc(String segment) throws Exception {
        return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
    }

    static String davFileUrl(SecureStore.Account a, String folder, String fileName) throws Exception {
        StringBuilder sb = new StringBuilder(a.server).append("/remote.php/dav/files/").append(enc(a.userId)).append('/');
        for (String part : folder.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            sb.append(enc(part)).append('/');
        }
        return sb.append(enc(fileName)).toString();
    }

    /**
     * Maakt de map (en eventuele bovenliggende mappen) aan met WebDAV MKCOL.
     * HttpURLConnection kent MKCOL niet, dus dit gaat via een eigen, kleine HTTP-aanvraag
     * over dezelfde beveiligde verbinding (TLS met certificaat- en hostnaamcontrole).
     */
    static boolean mkdirs(SecureStore.Account a, String folder) throws Exception {
        StringBuilder path = new StringBuilder(new URL(a.server).getPath())
            .append("/remote.php/dav/files/").append(enc(a.userId));
        for (String part : folder.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            path.append('/').append(enc(part));
            int code = mkcol(a, path.toString());
            // 201 = aangemaakt, 405 = bestaat al
            if (code != 201 && code != 405) return false;
        }
        return true;
    }

    private static int mkcol(SecureStore.Account a, String path) throws Exception {
        URL u = new URL(a.server);
        boolean tls = "https".equalsIgnoreCase(u.getProtocol());
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        java.net.Socket s;
        if (tls) {
            javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket)
                javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory().createSocket(host, port);
            ssl.startHandshake();
            if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                ssl.close();
                throw new javax.net.ssl.SSLPeerUnverifiedException("hostname");
            }
            s = ssl;
        } else {
            s = new java.net.Socket(host, port);
        }
        try {
            s.setSoTimeout(20000);
            String auth = Base64.encodeToString((a.loginName + ":" + a.appPassword).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String hostHeader = u.getPort() > 0 ? host + ":" + u.getPort() : host;
            String req = "MKCOL " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Authorization: Basic " + auth + "\r\n"
                + "User-Agent: " + USER_AGENT + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();
            java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String status = in.readLine(); // bijv. "HTTP/1.1 201 Created"
            if (status == null) return 0;
            String[] parts = status.split(" ");
            return parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        } finally {
            s.close();
        }
    }

    static Response http(String method, String url, String user, String pass, String contentType, String body,
                         boolean ocs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", USER_AGENT);
            if (ocs) {
                c.setRequestProperty("OCS-APIRequest", "true");
                c.setRequestProperty("Accept", "application/json");
            }
            if (user != null) {
                String auth = Base64.encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                c.setRequestProperty("Authorization", "Basic " + auth);
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) try (InputStream is = in) { text = new String(readAll(is), StandardCharsets.UTF_8); }
            return new Response(code, text);
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Stap 1 van Login Flow v2: geeft {login, poll:{token, endpoint}} terug. */
    static JSONObject startLogin(String server) throws Exception {
        Response r = http("POST", server + "/index.php/login/v2", null, null, "application/x-www-form-urlencoded", "", false);
        if (r.status != 200) throw new IllegalStateException("start:" + r.status);
        return new JSONObject(r.body);
    }

    /** Stap 2: één keer pollen. null zolang de gebruiker nog niet heeft ingelogd. */
    static JSONObject poll(String endpoint, String token) throws Exception {
        Response r = http("POST", endpoint, null, null, "application/x-www-form-urlencoded", "token=" + enc(token), false);
        if (r.status == 200) return new JSONObject(r.body);
        if (r.status == 404) return null;
        throw new IllegalStateException("poll:" + r.status);
    }

    /** Het echte gebruikers-id (de WebDAV-map), want inloggen kan ook met e-mailadres. */
    static String userId(String server, String login, String pass) {
        try {
            Response r = http("GET", server + "/ocs/v2.php/cloud/user?format=json", login, pass, null, null, true);
            if (r.status == 200) return new JSONObject(r.body).getJSONObject("ocs").getJSONObject("data").getString("id");
        } catch (Exception ignored) { }
        return login;
    }

    /** Trekt het app-wachtwoord in bij Nextcloud, zodat het nergens meer werkt. */
    static boolean revoke(SecureStore.Account a) {
        try {
            Response r = http("DELETE", a.server + "/ocs/v2.php/core/apppassword", a.loginName, a.appPassword, null, null, true);
            return r.status == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
