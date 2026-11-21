package dev.yu.videoplayer.core;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 业务层唯一依赖的门面，对应 iOS 的 YUVideoCache。
 *
 * 用法：
 *   player.setMediaSource(YUVideoCache.mediaSource(context, vid, realUrl));
 *   YUVideoCache.prefetch(context, vid, realUrl);
 */
public final class YUVideoCache {

    private YUVideoCache() {
    }

    /** 签名续签：接业务自己的逻辑，默认不处理 */
    public static volatile YUUrlRenewer renewer = new YUUrlRenewer() {
        @Override
        public String renew(String url) {
            return url;
        }
    };

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "video-cache-prefetch");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** 线程池已 shutdown（进程退出中）时静默忽略，避免 RejectedExecutionException 崩主线程 */
    private static void schedule(Runnable task) {
        try {
            IO.execute(task);
        } catch (RejectedExecutionException ignored) {
            // 已经关闭，什么也不用做
        }
    }

    // ---- 配置 ----

    /**
     * 预取总开关，默认关闭。
     *
     * 关闭时不探测总长、不下载前缀，包括首次播放时 {@link #mediaSource} 里
     * 那次隐式预取。因为 meta 建不出来，缓存链路也就一直不会生效。磁盘上已经
     * 有的前缀照常命中：播放仍走缓存链路，只是不再往前补。
     */
    private static volatile boolean prefetchEnabled = false;

    /** 见 {@link #prefetchEnabled} */
    public static void setPrefetchEnabled(boolean enabled) {
        prefetchEnabled = enabled;
    }

    public static boolean isPrefetchEnabled() {
        return prefetchEnabled;
    }

    /** 全局可用缓存总量，默认 1GB */
    public static void setMaxTotalBytes(long bytes) {
        YUPrefixCache.maxTotalBytes = Math.max(0, bytes);
    }

    /** 单条视频可缓存的上限，默认 6MB */
    public static void setMaxBytesPerVideo(long bytes) {
        YUPrefixCache.maxBytesPerVideo = Math.max(0, bytes);
    }

    // ---- 预取 ----

    /**
     * 详情页曝光时调用，失败静默，不阻塞任何 UI。
     *
     * 预取开关关闭时直接返回，连 IO 线程都不进。
     */
    public static void prefetch(final Context context, final String vid, final String realUrl) {
        if (!prefetchEnabled) return;
        final Context app = context.getApplicationContext();
        final YUUrlRenewer renewerNow = renewer;
        schedule(() -> YUPrefixCache.prefetch(app, vid, realUrl, renewerNow));
    }

    // ---- 播放 ----

    /**
     * 生成 MediaSource。有 meta 时走缓存链路（边播边写），
     * 没有时退回纯网络，同时后台开始探测。
     *
     * 需要在切换视频时调用，与 iOS 的 playerItem(vid:realURL:) 对应。
     */
    public static MediaSource mediaSource(Context context, String vid, String realUrl) {
        DataSource.Factory upstream = newUpstream();

        // 与 iOS 一致：能拿到缓存版就直接返回
        MediaSource cached = cachedMediaSource(context, vid, realUrl, upstream);
        if (cached != null) return cached;

        // 预取开关关闭时这里什么都不做（prefetch 自己短路），直接退回纯网络
        prefetch(context, vid, realUrl);
        return new DefaultMediaSourceFactory(upstream)
                .createMediaSource(MediaItem.fromUri(realUrl));
    }

    /** 无 meta 时返回 null，由调用方走远程兜底 */
    @Nullable
    private static MediaSource cachedMediaSource(Context context, String vid,
                                                 String realUrl, DataSource.Factory upstream) {
        YUPrefixStore store = YUPrefixCache.storeIfReady(context, vid);
        if (store == null) return null;

        DataSource.Factory caching =
                new YUPrefixDataSource.Factory(upstream, store, vid, realUrl, renewer);
        return new DefaultMediaSourceFactory(caching)
                .createMediaSource(MediaItem.fromUri(realUrl));
    }

    private static DataSource.Factory newUpstream() {
        return new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("VideoPlayer/1.0");
    }

    /** 播放开始：计数加一。同一个 vid 可以被多个播放器同时使用，互不影响 */
    public static void acquire(String vid) {
        YUPrefixCache.acquire(vid);
    }

    /**
     * 播放结束：计数减一，归零后才解除淘汰保护（对应 iOS 的 release）。
     *
     * 减计数必须**同步**执行：丢到 IO 线程的话，队列里可能正卡着一个 6MB 预取，
     * 延迟几十秒后这次 release 会把下一轮播放的标记也减掉。
     * 容量扫描本身仍然异步，避免在 UI 线程扫目录。
     */
    public static void release(Context context, String vid) {
        final Context app = context.getApplicationContext();
        YUPrefixCache.release(vid);
        schedule(() -> YUPrefixCache.enforceTotalBytes(app));
    }

    // ---- 容量 ----

    public static long totalSize(Context context) {
        return YUPrefixCache.totalDiskUsage(context);
    }

    public static int count(Context context) {
        return YUPrefixCache.entries(context).size();
    }

    public static List<YUPrefixCache.YUEntry> entries(Context context) {
        return YUPrefixCache.entries(context);
    }

    /** 超过全局上限时按最久未使用淘汰；启动/回前台可主动调一次 */
    public static void enforceLimit(final Context context) {
        final Context app = context.getApplicationContext();
        schedule(() -> YUPrefixCache.enforceTotalBytes(app));
    }

    // ---- 清理 ----

    public static void clear(Context context, String vid) {
        YUPrefixCache.clear(context, vid);
    }

    public static void clearAll(Context context) {
        YUPrefixCache.clearAll(context);
    }

    /** 退出时释放线程池（一般不需要调用） */
    public static void shutdown() {
        IO.shutdown();
    }

    @Nullable
    static String dumpOf(List<YUPrefixCache.YUEntry> entries) {
        if (entries == null) return null;
        StringBuilder sb = new StringBuilder();
        for (YUPrefixCache.YUEntry e : entries) {
            sb.append(e.key).append(' ').append(e.bytes).append('\n');
        }
        return sb.toString();
    }
}
