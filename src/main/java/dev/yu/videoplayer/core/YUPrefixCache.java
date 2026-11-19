package dev.yu.videoplayer.core;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 缓存门面，对应 iOS 的 YUPrefixCache。
 * 负责：路径、探测、预取、装配 DataSource、容量控制、列表。
 */
public final class YUPrefixCache {

    private YUPrefixCache() {
    }

    // ---- 配置 ----

    /** 全局可占用的磁盘上限，默认 1GB */
    public static volatile long maxTotalBytes = 1024L * 1024 * 1024;

    /** 单条视频可占用的上限，默认 16MB */
    public static volatile long maxBytesPerVideo = YUPrefixMeta.DEFAULT_MAX_BYTES_PER_VIDEO;

    /** 正在使用某个 vid 的播放器数量；淘汰时跳过大于 0 的条目 */
    private static final Map<String, Integer> activeCounts = new HashMap<>();
    private static final Object ACTIVE_LOCK = new Object();

    /**
     * 正在对某个 vid 顺序写盘的 DataSource 数量。
     *
     * 和 activeCounts 是两件事：播放器可能在播，但走的是纯网络 MediaSource（还没 meta），
     * 此时没有任何 DataSource 在写文件。预取要判断的是「有没有人和我抢这份文件」，
     * 所以看这个，而不是看有没有人在播。
     */
    private static final Map<String, Integer> writers = new HashMap<>();
    private static final Object WRITER_LOCK = new Object();

    /** 正在跑的预取任务。单线程池串行，但 store 回收要知道有没有人正拿着它 */
    private static final Set<String> prefetching = new HashSet<>();
    private static final Object PREFETCH_LOCK = new Object();

    /**
     * 缓存资源：一个 vid 一份 store，可被多个播放器同时使用。
     * meta（已下载进度）只有一份，读写由 store 自己的 synchronized 保证 ——
     * 若每个播放器各 open 一份，后打开的那个会拿着旧进度，读不到缓存也写不进去。
     */
    private static final Map<String, YUPrefixStore> stores = new HashMap<>();
    private static final Object STORE_LOCK = new Object();

    // ---- 路径 ----

    static File root(Context context) {
        return new File(context.getApplicationContext().getCacheDir(), "MediaCache");
    }

    static File directory(Context context, String vid) {
        return new File(root(context), sanitize(vid));
    }

    /** vid 可能含中文或斜杠，落到目录名前统一处理 */
    private static String sanitize(String vid) {
        StringBuilder sb = new StringBuilder(vid.length());
        for (int i = 0; i < vid.length(); i++) {
            char c = vid.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    // ---- 查询 ----

    /** 只要探测过总长就返回 store，前缀没下完也能用（边播边写）。
     *  按 vid 共享：多个播放器播同一条时拿到的是同一个实例，进度实时一致。 */
    @Nullable
    public static YUPrefixStore storeIfReady(Context context, String vid) {
        String key = sanitize(vid);

        synchronized (STORE_LOCK) {
            YUPrefixStore cached = stores.get(key);
            if (cached != null) {
                cached.touch();
                return cached;
            }
        }

        YUPrefixStore opened = YUPrefixStore.open(directory(context, vid));
        if (opened == null) {
            YULog.log("[缓存] vid=" + key + " 没下过，走纯网络");
            return null;
        }
        YUPrefixMeta meta = opened.meta();
        if (meta.totalLength <= 0 || meta.limit() <= 0) {
            YULog.log("[缓存] vid=" + key + " 目录在但没有 meta，走纯网络");
            return null;
        }

        // 双检：并发进来时只留一个实例
        synchronized (STORE_LOCK) {
            YUPrefixStore cached = stores.get(key);
            if (cached != null) return cached;
            stores.put(key, opened);
        }
        opened.touch();
        YULog.log("[缓存] vid=" + key + " 命中 已下=" + YULog.mb(meta.downloaded)
                + "/" + YULog.mb(meta.limit()) + " 总长=" + YULog.mb(meta.totalLength));
        return opened;
    }

    /** 把新实例放进共享表；表里已有则返回已有的那个 */
    private static YUPrefixStore publish(YUPrefixStore store, String key) {
        synchronized (STORE_LOCK) {
            YUPrefixStore existing = stores.get(key);
            if (existing != null) return existing;
            stores.put(key, store);
            return store;
        }
    }

    /** 条目被删除时从共享表摘掉，下次访问重新 open */
    private static void forget(String key) {
        synchronized (STORE_LOCK) {
            stores.remove(key);
        }
    }

    /** 播放开始：计数加一，允许同一个 vid 被多个播放器同时使用 */
    static void acquire(String vid) {
        String key = sanitize(vid);
        int now;
        synchronized (ACTIVE_LOCK) {
            Integer n = activeCounts.get(key);
            now = n == null ? 1 : n + 1;
            activeCounts.put(key, now);
        }
        // 泄漏时这里只会有占用没有配对的释放，使用中永远归不了零
        YULog.log("[占用] vid=" + key + " 使用中=" + now);
    }

    /** 播放结束：计数减一，归零后才解除淘汰保护（删文件） */
    static void release(String vid) {
        String key = sanitize(vid);
        int now;
        int total = 0;
        synchronized (ACTIVE_LOCK) {
            Integer n = activeCounts.get(key);
            if (n == null || n <= 1) {
                activeCounts.remove(key);
                now = 0;
            } else {
                activeCounts.put(key, n - 1);
                now = n - 1;
            }
            for (Integer value : activeCounts.values()) total += value;
        }
        YULog.log("[释放] vid=" + key + " 使用中=" + now + " 全局使用中=" + total);

        // 最后一个播放器也走了：内存里这份 store 可以放掉（磁盘文件保留）。
        // 不放的话每看过一条就常驻一份，直到进程被杀 —— 单条不大但条数无界，
        // 而 1GB 上限要 500 条 2MB 才轮到淘汰，等于永不回收。
        if (now == 0) forgetIfIdle(key);
    }

    static boolean isActive(String vid) {
        String key = sanitize(vid);
        synchronized (ACTIVE_LOCK) {
            Integer n = activeCounts.get(key);
            return n != null && n > 0;
        }
    }

    /** 有没有 DataSource 正在对这条顺序写盘 */
    static boolean isWriting(String vid) {
        String key = sanitize(vid);
        synchronized (WRITER_LOCK) {
            Integer n = writers.get(key);
            return n != null && n > 0;
        }
    }

    /** DataSource 创建时调用，与 endWrite 配对（在 close 里各一次） */
    static void beginWrite(String vid) {
        String key = sanitize(vid);
        synchronized (WRITER_LOCK) {
            Integer n = writers.get(key);
            writers.put(key, n == null ? 1 : n + 1);
        }
    }

    /** DataSource 关闭时调用。归零后这条若也没人播、没预取任务，就把 store 摘掉 */
    static void endWrite(String vid) {
        String key = sanitize(vid);
        synchronized (WRITER_LOCK) {
            Integer n = writers.get(key);
            if (n == null || n <= 1) {
                writers.remove(key);
            } else {
                writers.put(key, n - 1);
            }
        }
        forgetIfIdle(key);
    }

    /**
     * 把不再被使用的 store 从共享表里摘掉：**只掉内存实例，磁盘文件不动**，
     * 下次 storeIfReady 会重新 open 一份。
     *
     * 三个条件缺一不可，否则会出现「同一个 vid 两份 store」：旧的那份还在被
     * DataSource / 预取任务拿着写，新的那份按自己的进度快照读，两边对不上，等于白下。
     */
    private static void forgetIfIdle(String key) {
        synchronized (ACTIVE_LOCK) {
            Integer n = activeCounts.get(key);
            if (n != null && n > 0) return;
        }
        synchronized (WRITER_LOCK) {
            Integer n = writers.get(key);
            if (n != null && n > 0) return;
        }
        synchronized (PREFETCH_LOCK) {
            if (prefetching.contains(key)) return;
        }

        boolean removed;
        int left;
        synchronized (STORE_LOCK) {
            removed = stores.remove(key) != null;
            left = stores.size();
        }
        if (removed) {
            YULog.log("[缓存] vid=" + key + " 已无人使用，释放内存里的 store（余 "
                    + left + " 份）");
        }
    }

    @Nullable
    private static YUPrefixStore cachedStore(String key) {
        synchronized (STORE_LOCK) {
            return stores.get(key);
        }
    }

    // ---- 预取登记 ----

    /** 返回 false 表示这条已经在下了（单线程池里一般不会，兜住重复入队） */
    private static boolean beginPrefetch(String key) {
        synchronized (PREFETCH_LOCK) {
            return prefetching.add(key);
        }
    }

    private static void endPrefetch(String key) {
        synchronized (PREFETCH_LOCK) {
            prefetching.remove(key);
        }
        // 预取期间新建的 store 如果没人接手，到这里就该放了
        forgetIfIdle(key);
    }

    // ---- 预取 ----

    /** 探测总长并把前 N 字节下到磁盘；已下载一部分时只补剩余部分。
     *  renewer 在每次真实请求前调用，保证预取用的是当前有效签名。 */
    static void prefetch(Context context, String vid, String realUrl, YUUrlRenewer renewer) {
        String key = sanitize(vid);
        if (!beginPrefetch(key)) {
            YULog.log("[预取] vid=" + key + " 跳过：已有任务在下");
            return;
        }
        try {
            prefetchInner(context, vid, realUrl, renewer);
        } finally {
            endPrefetch(key);
        }
    }

    private static void prefetchInner(Context context, String vid,
                                      String realUrl, YUUrlRenewer renewer) {
        File dir = directory(context, vid);
        String key = sanitize(vid);

        // 表里已有就直接用：播放中的 store 就是它，绝不能另开一份（否则进度各算各的）
        YUPrefixStore store = cachedStore(key);
        if (store == null) {
            // 表里没有：先**只读**看一眼磁盘，已下满就直接收工，不进共享表。
            //
            // 走 storeIfReady 的话会把实例塞进表、touch 一次 updatedAt，再被
            // endPrefetch 立刻摘掉：一次切源（左右相邻两条）白刷 3 行日志；
            // 更要紧的是 touch 会把「从没看过的视频」的最后使用时间刷成现在，
            // 等磁盘到 1GB 淘汰时它反而排在真正该留的前面。
            YUPrefixStore peek = YUPrefixStore.open(dir);
            if (peek != null && peek.meta().isComplete()) {
                YULog.log("[预取] vid=" + key + " 跳过：前缀已下满（磁盘，未入表）");
                return;
            }
            store = storeIfReady(context, vid);
        }
        if (store != null && store.meta().isComplete()) {
            YULog.log("[预取] vid=" + key + " 跳过：前缀已下满");
            return;
        }

        long startedAt = System.currentTimeMillis();

        if (store == null) {
            Probe probe = probe(renew(renewer, realUrl));
            if (probe == null) {
                YULog.log("[预取] vid=" + key + " 失败：探测总长没拿到（服务端不支持 Range？）");
                return;
            }
            long limitBytes = Math.min(maxBytesPerVideo, probe.totalLength);
            if (limitBytes <= 0) {
                YULog.log("[预取] vid=" + key + " 失败：总长 " + probe.totalLength + " 不合法");
                return;
            }
            YUPrefixStore created = YUPrefixStore.create(dir, probe.totalLength, limitBytes, probe.etag);
            if (created == null) {
                YULog.log("[预取] vid=" + key + " 失败：建缓存目录出错");
                return;
            }
            // 探测期间可能已经有播放器建好了 store，别把人家正在用的文件重置掉
            store = publish(created, key);
            YULog.log("[预取] vid=" + key + " 建条目 总长=" + YULog.mb(probe.totalLength)
                    + " 前缀上限=" + YULog.mb(limitBytes));
        }

        long from = store.downloaded();
        long to = store.limit();
        if (from >= to) {
            YULog.log("[预取] vid=" + key + " 跳过：已下 " + YULog.mb(from) + "，到达上限");
            return;
        }

        // 有 DataSource 正在顺序写这条：再并发下载只会被丢弃，切源时尤其浪费。
        //
        // 这里刻意判断「有没有人在写」而不是「有没有人在播」：首次播放走的是纯网络
        // MediaSource（meta 还没建出来），根本没有 DataSource 写文件，此时下载不会
        // 和谁抢，正好把这条的前缀补上 —— 否则第一次看的那条永远是 0 字节，
        // 要「看第二遍」才开始缓存。
        if (isWriting(vid)) {
            YULog.log("[预取] vid=" + key + " 跳过：数据源正在写盘");
            return;
        }
        if (isActive(vid)) {
            YULog.log("[预取] vid=" + key + " 播放中但没走缓存链路，照常下载");
        }

        YULog.log("[预取] vid=" + key + " 开始 范围=" + YULog.mb(from) + "→" + YULog.mb(to));

        File tmp = new File(dir, "prefix.tmp");
        if (!downloadRange(renew(renewer, realUrl), from, to, tmp)) {
            YULog.log("[预取] vid=" + key + " 失败：下载没拿到 206");
            return;
        }

        // 下载期间 DataSource 可能已经开始写：这里绝不能替换或追加，
        // 否则文件与 meta 会错位。丢弃这次结果，交给边播边写。
        if (isWriting(vid) || store.downloaded() != from) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            YULog.log("[预取] vid=" + key + " 丢弃：数据源已开始写盘");
            return;
        }

        if (from == 0) {
            store.install(tmp);
        } else {
            store.appendFile(tmp);
        }

        YULog.log("[预取] vid=" + key + " 完成 已下=" + YULog.mb(store.downloaded())
                + "/" + YULog.mb(store.limit()) + " " + YULog.cost(startedAt));

        enforceTotalBytes(context);
    }

    private static String renew(YUUrlRenewer renewer, String url) {
        if (renewer == null) return url;
        try {
            String renewed = renewer.renew(url);
            return renewed == null ? url : renewed;
        } catch (RuntimeException e) {
            return url;
        }
    }

    private static final class Probe {
        final long totalLength;
        final String etag;

        Probe(long totalLength, String etag) {
            this.totalLength = totalLength;
            this.etag = etag;
        }
    }

    @Nullable
    private static Probe probe(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("Range", "bytes=0-1");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);

            int code = conn.getResponseCode();
            String etag = conn.getHeaderField("ETag");

            if (code == 206) {
                String contentRange = conn.getHeaderField("Content-Range");
                if (contentRange != null) {
                    int slash = contentRange.lastIndexOf('/');
                    if (slash >= 0) {
                        long total = parseLong(contentRange.substring(slash + 1), -1);
                        if (total > 0) return new Probe(total, etag);
                    }
                }
            } else if (code == 200) {
                long total = parseLong(conn.getHeaderField("Content-Length"), -1);
                if (total > 0) return new Probe(total, etag);
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 下载 [from, to) 到 tmp；成功返回 true */
    private static boolean downloadRange(String url, long from, long to, File tmp) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("Range", "bytes=" + from + "-" + (to - 1));
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);

            int code = conn.getResponseCode();
            boolean ok = code == 206 || (from == 0 && code == 200);
            if (!ok) return false;

            long expected = to - from;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[256 * 1024];
                long written = 0;
                int read;
                while (written < expected && (read = in.read(buffer)) > 0) {
                    int accept = (int) Math.min(read, expected - written);
                    out.write(buffer, 0, accept);
                    written += accept;
                }
                out.getFD().sync();
                return written > 0;
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---- 列表 ----

    /** 一条缓存的可读快照 */
    public static final class YUEntry {
        public final String key;
        public final long bytes;
        public final long downloaded;
        public final long limit;
        public final long totalLength;
        public final long updatedAt;

        YUEntry(String key, long bytes, long downloaded, long limit, long totalLength, long updatedAt) {
            this.key = key;
            this.bytes = bytes;
            this.downloaded = downloaded;
            this.limit = limit;
            this.totalLength = totalLength;
            this.updatedAt = updatedAt;
        }

        /** 前缀完成度 0...1 */
        public float progress() {
            return limit > 0 ? Math.min(1f, (float) downloaded / limit) : 0f;
        }
    }

    /** 列出全部缓存，按最后使用时间倒序。只读 meta 与文件大小，不做校验 */
    public static List<YUEntry> entries(Context context) {
        File root = root(context);
        File[] dirs = root.listFiles();
        if (dirs == null) return new ArrayList<>();

        List<YUEntry> result = new ArrayList<>();
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            YUPrefixMeta meta = YUPrefixStore.readMeta(dir);
            if (meta == null) continue;

            File data = new File(dir, "prefix.dat");
            long bytes = data.exists() ? data.length() : 0;

            result.add(new YUEntry(dir.getName(), bytes,
                    meta.downloaded, meta.limit(), meta.totalLength, meta.updatedAt));
        }

        Collections.sort(result, new Comparator<YUEntry>() {
            @Override
            public int compare(YUEntry a, YUEntry b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return result;
    }

    public static long totalDiskUsage(Context context) {
        long total = 0;
        for (YUEntry entry : entries(context)) total += entry.bytes;
        return total;
    }

    // ---- 容量控制 ----

    /** 超过全局上限时，按最后使用时间从最旧的开始整条删除 */
    public static void enforceTotalBytes(Context context) {
        File root = root(context);
        File[] dirs = root.listFiles();
        if (dirs == null) return;

        List<YUEntry> all = entries(context);
        long total = 0;
        for (YUEntry entry : all) total += entry.bytes;
        if (total <= maxTotalBytes) return;

        // entries 已按时间倒序，从尾部（最旧）开始删
        int removed = 0;
        long freed = 0;
        for (int i = all.size() - 1; i >= 0 && total > maxTotalBytes; i--) {
            YUEntry entry = all.get(i);
            // 有播放器正在用的条目一律跳过：删掉它 = 正在播的这条断流
            if (isActive(entry.key)) continue;
            YUPrefixStore.deleteRecursively(new File(root, entry.key));
            forget(entry.key);
            total -= entry.bytes;
            removed++;
            freed += entry.bytes;
        }

        if (removed > 0) {
            YULog.log("[淘汰] 删除 " + removed + " 条，腾出 " + YULog.mb(freed)
                    + "，剩余 " + YULog.mb(total) + "/" + YULog.mb(maxTotalBytes));
        }
    }

    // ---- 清理 ----

    /** 删除某条缓存。正在播放时删掉不会崩：DataSource 读不到盘会自动退回网络 */
    public static void clear(Context context, String vid) {
        YULog.log("[清理] vid=" + sanitize(vid));
        forget(sanitize(vid));
        YUPrefixStore.deleteRecursively(directory(context, vid));
    }

    public static void clearAll(Context context) {
        int count;
        synchronized (STORE_LOCK) {
            count = stores.size();
            stores.clear();
        }
        YULog.log("[清理] 全部缓存（内存中 " + count + " 份 store）");
        YUPrefixStore.deleteRecursively(root(context));
    }

    // ---- 工具 ----

    private static long parseLong(String text, long fallback) {
        if (text == null) return fallback;
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static JSONObject readMetaJson(File dir) {
        try {
            YUPrefixStore store = YUPrefixStore.open(dir);
            return store == null ? null : store.meta().toJson();
        } catch (Exception e) {
            return null;
        }
    }

    static Uri uriOf(File file) {
        return Uri.fromFile(file);
    }
}
