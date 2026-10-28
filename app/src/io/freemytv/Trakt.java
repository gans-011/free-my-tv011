package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Your Trakt account (trakt.tv), for the picks: Trakt's recommendations from what you watched and rated. Connected on
 * the TV with a device code (the phone opens trakt.tv/activate), so only config.properties' trakt_client_id is needed:
 * Trakt's new apps sign in without a secret. The tokens are kept in trakt.json, a file, since the settings screen
 * (main process) writes them and the home screen (:home) reads them. Never logs them.
 */
final class Trakt {
    private Trakt() {}

    private static final String API = "https://api.trakt.tv";
    private static final String FILE = "trakt.json";

    /** A device code: what the TV shows (userCode at url) and what it asks Trakt with (deviceCode). */
    static final class Device {
        String deviceCode, userCode, url;
        int interval;
        long expiresAt;
    }

    /** Poll results. */
    static final int PENDING = 0, CONNECTED = 1, FAILED = -1;

    /** A Client ID is in config.properties: the settings row can connect. */
    static boolean available() {
        return !Config.TRAKT_CLIENT_ID.trim().isEmpty();
    }

    static boolean connected(Context c) {
        return available() && file(c).exists();
    }

    /** Asks Trakt for a code to show on the TV. Not on the main thread. */
    static Device start() throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("client_id", clientId());
            JSONObject o = new JSONObject(post("/oauth/device/code", body, null));
            Device d = new Device();
            d.deviceCode = o.getString("device_code");
            d.userCode = o.getString("user_code");
            d.url = o.optString("verification_url", "https://trakt.tv/activate");
            d.interval = Math.max(1, o.optInt("interval", 5));
            d.expiresAt = System.currentTimeMillis() + o.optInt("expires_in", 600) * 1000L;
            return d;
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt: unexpected answer");
        }
    }

    /** One look at whether the person approved the code yet (every d.interval seconds). Not on the main thread. */
    static int poll(Context c, Device d) {
        if (System.currentTimeMillis() > d.expiresAt) return FAILED;
        try {
            JSONObject body = new JSONObject();
            body.put("code", d.deviceCode);
            body.put("client_id", clientId());
            save(c, new JSONObject(post("/oauth/device/token", body, null)));
            AppLog.w("trakt: connected");
            return CONNECTED;
        } catch (HttpError e) {
            // 400 and 429: not yet (or too fast). 404, 409, 410 and 418: a wrong, used, expired or refused code.
            return e.code == 400 || e.code == 429 ? PENDING : FAILED;
        } catch (Exception e) {
            return PENDING;   // the network: try again at the next look
        }
    }

    static void disconnect(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
    }

    /** TMDB ids of what Trakt recommends you ("movie" or "tv"), best first. Not on the main thread. */
    static List<Integer> recommended(Context c, String kind) throws IOException {
        String path = "/recommendations/" + (kind.equals("movie") ? "movies" : "shows")
                + "?limit=40&ignore_collected=true&ignore_watchlisted=false";
        List<Integer> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(get(path, token(c)));
            for (int i = 0; i < a.length(); i++) {
                JSONObject ids = a.getJSONObject(i).optJSONObject("ids");
                int tmdb = ids == null ? 0 : ids.optInt("tmdb");
                if (tmdb > 0) out.add(tmdb);
            }
        } catch (HttpError e) {
            if (e.code == 401) disconnect(c);   // revoked on trakt.tv: connect again in Home Settings
            throw e;
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt: unexpected answer");
        }
        return out;
    }

    /** A show you're watching: its next episode and how much of what aired you've seen. */
    static final class Watching {
        int tmdb, season, number, aired, completed;
    }

    /**
     * The shows you're following: watched in the past year with an episode left, the most recently watched first, then
     * your watchlist's; never one you dropped or hid from your progress on Trakt. Up to `max`. Not on the main thread.
     */
    static List<Watching> watching(Context c, int max) throws IOException {
        List<Watching> out = new ArrayList<>();
        try {
            String token = token(c);
            java.util.Set<Integer> skip = new java.util.HashSet<>();
            for (String section : new String[] {"dropped", "progress_watched"}) hidden(section, token, skip);
            JSONArray a = new JSONArray(get("/sync/watched/shows?extended=noseasons", token));
            List<JSONObject> shows = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) shows.add(a.getJSONObject(i));
            // ISO 8601 in UTC: the text sorts as the time does.
            java.util.Collections.sort(shows, (x, y) -> y.optString("last_watched_at").compareTo(x.optString("last_watched_at")));
            java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT);
            iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String yearAgo = iso.format(new java.util.Date(System.currentTimeMillis() - 365L * 24 * 3600_000L));
            List<JSONObject> candidates = new ArrayList<>();
            for (JSONObject o : shows) {
                if (o.optString("last_watched_at").compareTo(yearAgo) < 0) break;
                candidates.add(o);
            }
            JSONArray list = new JSONArray(get("/sync/watchlist/shows?sort_by=added&sort_how=desc", token));
            for (int i = 0; i < list.length(); i++) candidates.add(list.getJSONObject(i));
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (JSONObject o : candidates) {
                if (out.size() >= max) break;
                JSONObject ids = o.optJSONObject("show") == null ? null : o.getJSONObject("show").optJSONObject("ids");
                if (ids == null || ids.optInt("tmdb") <= 0 || skip.contains(ids.optInt("trakt")) || !seen.add(ids.optInt("trakt"))) continue;
                JSONObject p = new JSONObject(get("/shows/" + ids.optInt("trakt") + "/progress/watched"
                        + "?hidden=false&specials=false&count_specials=false", token));
                JSONObject next = p.optJSONObject("next_episode");
                if (next == null) continue;   // seen to the end (until a new episode airs)
                Watching w = new Watching();
                w.tmdb = ids.optInt("tmdb");
                w.season = next.optInt("season");
                w.number = next.optInt("number");
                w.aired = p.optInt("aired");
                w.completed = p.optInt("completed");
                out.add(w);
            }
        } catch (HttpError e) {
            if (e.code == 401) disconnect(c);
            throw e;
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt: unexpected answer");
        }
        return out;
    }

    /** Adds the Trakt ids of the shows in one of your hidden sections ("dropped", "progress_watched") to `out`. */
    private static void hidden(String section, String token, java.util.Set<Integer> out) throws org.json.JSONException {
        int before = out.size();
        try {
            for (int page = 1; page <= 10; page++) {
                JSONArray a = new JSONArray(get("/users/hidden/" + section + "?type=show&limit=100&page=" + page, token));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject show = a.getJSONObject(i).optJSONObject("show");
                    if (show != null && show.optJSONObject("ids") != null) out.add(show.getJSONObject("ids").optInt("trakt"));
                }
                if (a.length() < 100) break;
            }
            AppLog.w("trakt: " + (out.size() - before) + " shows hidden in " + section);
        } catch (IOException e) {
            AppLog.w("trakt: hidden " + section + ": " + e.getMessage());   // a section this account or API lacks
        }
    }

    // MARK: - Tokens

    /** The access token, renewed with the refresh token a day before it runs out. */
    private static synchronized String token(Context c) throws IOException {
        JSONObject t = load(c);
        if (t == null) throw new IOException("Trakt: not connected");
        if (System.currentTimeMillis() < t.optLong("expiresAt") - 24 * 3600_000L) return t.optString("access");
        try {
            JSONObject body = new JSONObject();
            body.put("refresh_token", t.optString("refresh"));
            body.put("client_id", clientId());
            body.put("grant_type", "refresh_token");
            save(c, new JSONObject(post("/oauth/token", body, null)));
            AppLog.w("trakt: token renewed");
            return load(c).optString("access");
        } catch (HttpError e) {
            AppLog.w("trakt: renewing the token failed (HTTP " + e.code + ")");
            if (System.currentTimeMillis() < t.optLong("expiresAt")) return t.optString("access");
            if (e.code == 400 || e.code == 401) disconnect(c);
            throw e;
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt: unexpected answer");
        }
    }

    private static void save(Context c, JSONObject o) throws IOException, org.json.JSONException {
        JSONObject t = new JSONObject();
        t.put("access", o.getString("access_token"));
        t.put("refresh", o.getString("refresh_token"));
        long created = o.optLong("created_at", System.currentTimeMillis() / 1000) * 1000L;
        t.put("expiresAt", created + o.optLong("expires_in", 86400) * 1000L);
        File tmp = new File(c.getFilesDir(), FILE + "." + android.os.Process.myPid() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(t.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file(c))) throw new IOException("Trakt: couldn't save the sign-in");
    }

    private static JSONObject load(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return null;
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    private static String clientId() {
        return Config.TRAKT_CLIENT_ID.trim();
    }

    // MARK: - HTTP

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code) {
            super("Trakt returned HTTP " + code);
            this.code = code;
        }
    }

    private static String post(String path, JSONObject body, String token) throws IOException {
        HttpURLConnection c = open(path, token);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return answer(c);
    }

    private static String get(String path, String token) throws IOException {
        return answer(open(path, token));
    }

    private static HttpURLConnection open(String path, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("trakt-api-version", "2");
        c.setRequestProperty("trakt-api-key", clientId());
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private static String answer(HttpURLConnection c) throws IOException {
        try {
            int code = c.getResponseCode();
            if (code != 200 && code != 201) throw new HttpError(code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
