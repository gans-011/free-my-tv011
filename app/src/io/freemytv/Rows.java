package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The poster rows below the shelf, like the Apple TV app: "Popular movies", "Popular shows", "Top rated" and one
 * "Trending on <service>" row per streaming app on the TV, from TMDB (the same key and region as Recs). Each title
 * knows which of the TV's apps has it, so OK opens that app. Refreshed every 6 hours; the posters are kept on disk
 * and a few dozen in memory.
 */
final class Rows {
    static final class Item {
        int id;
        String kind, title, poster, pkg, app, service, date = "", overview = "";
        double popularity, vote;
        int votes;
    }

    static final class Row {
        String title;
        final List<Item> items = new ArrayList<>();
    }

    interface Listener { void onRows(); }

    private static final String POSTERS = "https://image.tmdb.org/t/p/w342";
    private static final long EVERY = 6 * 3600_000L;
    private static final int PER_ROW = 20;
    private static final int VERSION = 3;

    private static Rows instance;

    static synchronized Rows get(Context c) {
        if (instance == null) instance = new Rows(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Set<String> loading = Collections.synchronizedSet(new HashSet<>());
    /** Decoded posters (RGB 565: half the memory, no transparency needed), ~24 MB. */
    private final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(24 << 20) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private volatile List<Row> rows = new ArrayList<>();
    private volatile long at;
    private volatile boolean fetching;
    private volatile long failedAt;

    private Rows(Context c) {
        context = c;
        cache = c.getSharedPreferences("rows", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != VERSION) cache.edit().clear().putInt("version", VERSION).apply();
        try {
            String saved = cache.getString("rows", null);
            if (saved != null && Ui.language.equals(cache.getString("language", ""))) rows = parse(new JSONArray(saved));
            at = cache.getLong("at", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The rows whose app is still on the TV (an uninstalled app's titles drop out). */
    List<Row> rows() {
        List<Row> out = new ArrayList<>();
        if (!Recs.hasKey(context)) return out;
        Recs recs = Recs.get(context);
        Map<String, Boolean> have = new LinkedHashMap<>();
        for (Row r : rows) {
            Row copy = new Row();
            copy.title = r.title;
            for (Item it : r.items) if (it.pkg != null && have.computeIfAbsent(it.pkg, recs::installed)) copy.items.add(it);
            if (!copy.items.isEmpty()) out.add(copy);
        }
        return out;
    }

    void refreshIfStale() {
        if (!Recs.hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        if (failedAt != 0 && now - failedAt >= 0 && now - failedAt < 10 * 60_000L) return;
        if (!rows.isEmpty() && now - at < EVERY && now >= at && Ui.language.equals(cache.getString("language", ""))) return;
        fetching = true;
        io.execute(() -> {
            try {
                List<Row> list = fetch();
                if (list.isEmpty()) throw new IOException("TMDB returned no rows");
                rows = list;
                at = System.currentTimeMillis();
                failedAt = 0;
                cache.edit().putString("rows", toJson(list).toString()).putLong("at", at)
                        .putString("language", Ui.language).apply();
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("rows: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    // MARK: - TMDB

    private List<Row> fetch() throws Exception {
        Recs recs = Recs.get(context);
        String lang = Recs.lang(), region = Recs.REGION;
        // The TV's services by their TMDB ids in the region (one id per app: "Netflix" and "Netflix Kids" are both
        // Netflix, the first one wins).
        Map<String, Integer> idOf = new LinkedHashMap<>();
        Map<String, String[]> serviceOf = new LinkedHashMap<>();
        JSONArray providers = recs.get("/watch/providers/movie?language=" + lang + "&watch_region=" + region).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = recs.service(o.optString("provider_name"), o.optInt("provider_id"));
            if (s == null || !recs.installed(s[2]) || idOf.containsKey(s[1])) continue;
            idOf.put(s[1], o.getInt("provider_id"));
            serviceOf.put(s[1], s);
        }
        if (idOf.isEmpty()) throw new IOException(Recs.NO_APPS);
        String common = "&language=" + lang + "&watch_region=" + region + "&with_watch_monetization_types=flatrate"
                + "&include_adult=false&sort_by=popularity.desc" + (Config.HIDE_ANIMATION ? "&without_genres=16" : "");
        List<Item> movies = new ArrayList<>(), shows = new ArrayList<>();
        List<Row> perService = new ArrayList<>();
        for (Map.Entry<String, Integer> e : idOf.entrySet()) {
            String[] s = serviceOf.get(e.getKey());
            String name = Recs.serviceName(appOnly(s[1]));
            List<Item> m = new ArrayList<>(), t = new ArrayList<>();
            try {
                read(recs.get("/discover/movie?with_watch_providers=" + e.getValue() + common), "movie", s, name, m);
                read(recs.get("/discover/tv?with_watch_providers=" + e.getValue() + common), "tv", s, name, t);
            } catch (Exception ex) {
                AppLog.w("rows: " + name + ": " + ex.getMessage());
                continue;
            }
            movies.addAll(m);
            shows.addAll(t);
            Row row = new Row();
            row.title = Ui.trf("Trending on {service}", "service", name);
            Set<String> seen = new HashSet<>();
            for (int i = 0; row.items.size() < PER_ROW && (i < m.size() || i < t.size()); i++) {
                if (i < t.size() && seen.add(key(t.get(i)))) row.items.add(t.get(i));
                if (i < m.size() && row.items.size() < PER_ROW && seen.add(key(m.get(i)))) row.items.add(m.get(i));
            }
            if (!row.items.isEmpty()) perService.add(row);
        }
        List<Row> out = new ArrayList<>();
        add(out, Ui.tr("Popular movies"), movies, (a, b) -> Double.compare(b.popularity, a.popularity));
        add(out, Ui.tr("Popular shows"), shows, (a, b) -> Double.compare(b.popularity, a.popularity));
        List<Item> recent = new ArrayList<>();
        String since = Recs.dayString(-120);
        for (Item it : concat(movies, shows)) if (it.date.compareTo(since) >= 0) recent.add(it);
        add(out, Ui.tr("New releases"), recent, (a, b) -> b.date.compareTo(a.date));
        List<Item> rated = new ArrayList<>();
        for (Item it : concat(movies, shows)) if (it.votes >= 300) rated.add(it);
        add(out, Ui.tr("Top rated"), rated, (a, b) -> Double.compare(b.vote, a.vote));
        out.addAll(perService);
        return out;
    }

    /** A Recs.Pick with only the app set, for Recs.serviceName. */
    private static Recs.Pick appOnly(String app) {
        Recs.Pick p = new Recs.Pick();
        p.app = app;
        return p;
    }

    private static List<Item> concat(List<Item> a, List<Item> b) {
        List<Item> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static void add(List<Row> out, String title, List<Item> items, java.util.Comparator<Item> order) {
        List<Item> sorted = new ArrayList<>(items);
        Collections.sort(sorted, order);
        Row row = new Row();
        row.title = title;
        Set<String> seen = new HashSet<>();
        for (Item it : sorted) if (row.items.size() < PER_ROW && seen.add(key(it))) row.items.add(it);
        if (row.items.size() >= 5) out.add(row);
    }

    private static void read(JSONObject page, String kind, String[] service, String name, List<Item> out) {
        JSONArray a = page.optJSONArray("results");
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("poster_path", "").isEmpty() || "null".equals(o.optString("poster_path"))) continue;
            Item it = new Item();
            it.id = o.optInt("id");
            it.kind = kind;
            it.title = o.optString(kind.equals("movie") ? "title" : "name", "");
            it.poster = o.optString("poster_path");
            it.date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date", "");
            it.popularity = o.optDouble("popularity", 0);
            it.vote = o.optDouble("vote_average", 0);
            it.votes = o.optInt("vote_count", 0);
            it.overview = o.optString("overview", "").trim();
            it.pkg = service[2];
            it.app = name;
            it.service = service[1];
            if (!it.title.isEmpty()) out.add(it);
        }
    }

    static String key(Item it) {
        return it.kind + ":" + it.id;
    }

    // MARK: - Posters

    /** The decoded poster, or null: loads it in the background and notifies when it's ready. */
    Bitmap poster(Item it) {
        if (it.poster == null) return null;
        Bitmap b = bitmaps.get(it.poster);
        if (b != null) return b;
        final String path = it.poster;
        if (!loading.add(path)) return null;
        images.execute(() -> {
            try {
                File f = new File(new File(context.getCacheDir(), "posters"), path.replace("/", ""));
                if (!f.exists()) download(path, f);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
                if (bmp != null) {
                    bitmaps.put(path, bmp);
                    notifyListeners();
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } catch (Exception e) {
                AppLog.w("rows: poster: " + e.getMessage());
            } finally {
                loading.remove(path);
            }
        });
        return null;
    }

    private static void download(String path, File f) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(POSTERS + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("poster HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("poster: rename failed");
        prune(f.getParentFile());
    }

    /** Keeps the newest 400 posters on disk (~12 MB). */
    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 400) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        //noinspection ResultOfMethodCallIgnored
        for (int i = 400; i < files.length; i++) files[i].delete();
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRows(); });
    }

    // MARK: - Saved copy

    private static JSONArray toJson(List<Row> list) throws org.json.JSONException {
        JSONArray a = new JSONArray();
        for (Row r : list) {
            JSONArray items = new JSONArray();
            for (Item it : r.items) {
                items.put(new JSONObject().put("id", it.id).put("kind", it.kind).put("title", it.title)
                        .put("poster", it.poster).put("pkg", it.pkg).put("app", it.app).put("date", it.date)
                        .put("vote", it.vote).put("overview", it.overview).put("service", it.service));
            }
            a.put(new JSONObject().put("title", r.title).put("items", items));
        }
        return a;
    }

    private static List<Row> parse(JSONArray a) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Row r = new Row();
            r.title = o.optString("title");
            JSONArray items = o.optJSONArray("items");
            for (int j = 0; items != null && j < items.length(); j++) {
                JSONObject x = items.optJSONObject(j);
                if (x == null) continue;
                Item it = new Item();
                it.id = x.optInt("id");
                it.kind = x.optString("kind");
                it.title = x.optString("title");
                it.poster = x.optString("poster");
                it.pkg = x.optString("pkg");
                it.app = x.optString("app");
                it.date = x.optString("date");
                it.vote = x.optDouble("vote", 0);
                it.overview = x.optString("overview");
                it.service = x.optString("service");
                r.items.add(it);
            }
            out.add(r);
        }
        return out;
    }
}
