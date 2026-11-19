package dev.yu.videoplayer.core;

import android.os.Debug;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生命周期日志：时间戳 + 存活数量 + 内存占用，与 iOS 的 YULog 一一对应。
 *
 * 排查泄漏时看三条线：
 * 1. {@code [创建]} 与 {@code [销毁]} 是否成对：只有创建没有销毁就是泄漏；
 * 2. 行尾 {@code xxx存活} 是否回到 0；
 * 3. {@code 内存} 是否随开关播放单调上涨。
 *
 * 只看本插件的日志：
 * <pre>adb logcat -s YUPlayer</pre>
 *
 * 关掉：{@code YULog.enabled = false}。
 */
public final class YULog {

    /** 三端统一的 tag：iOS 的日志前缀、Dart 的 _tag 都是这个名字 */
    public static final String TAG = "YUPlayer";

    /** 总开关，默认开 */
    public static boolean enabled = true;

    private static final AtomicInteger SEQ = new AtomicInteger();
    /** 计数表：不用 ConcurrentHashMap.computeIfAbsent，那个 API 在 minSdk 24 以下不可用 */
    private static final Map<String, AtomicInteger> LIVE = new HashMap<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private YULog() {
    }

    /** 每创建一个对象取一个号，销毁时用同一个号打印，方便配对 */
    public static int nextId() {
        return SEQ.incrementAndGet();
    }

    /** 当前存活数量 */
    public static int count(String kind, int delta) {
        synchronized (LIVE) {
            AtomicInteger counter = LIVE.get(kind);
            if (counter == null) {
                counter = new AtomicInteger();
                LIVE.put(kind, counter);
            }
            return Math.max(0, counter.addAndGet(delta));
        }
    }

    public static void log(String message) {
        if (!enabled) return;
        Log.i(TAG, stamp() + " " + message);
    }

    /** 创建打点：{@code [创建] 视图#1 vid=xx 视图存活=1 内存=Java 12MB / Native 30MB} */
    public static void birth(String kind, int id, String detail) {
        if (!enabled) return;
        log("[创建] " + kind + "#" + id + " " + detail
                + " " + kind + "存活=" + count(kind, 1) + " 内存=" + mem());
    }

    /** 销毁打点：{@code [销毁] 视图#1 存活=4.32s 触发=xxx 视图存活=0 内存=...} */
    public static void death(String kind, int id, String detail) {
        if (!enabled) return;
        log("[销毁] " + kind + "#" + id + " " + detail
                + " " + kind + "存活=" + count(kind, -1) + " 内存=" + mem());
    }

    /** 存活时长，形如 {@code 存活=4.32s} */
    public static String alive(long createdAtMs) {
        return String.format(Locale.US, "存活=%.2fs",
                (System.currentTimeMillis() - createdAtMs) / 1000.0);
    }

    /** 耗时长，形如 {@code 耗时=1.23s} */
    public static String cost(long startedAtMs) {
        return String.format(Locale.US, "耗时=%.2fs",
                (System.currentTimeMillis() - startedAtMs) / 1000.0);
    }

    /** 字节数转 MB，日志里比裸数字好读 */
    public static String mb(long bytes) {
        return String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0);
    }

    /**
     * 内存占用。
     *
     * Java 是托管堆（Bitmap / 集合等），Native 是 ExoPlayer 的解码器、缓冲池所在，
     * 视频相关泄漏通常先在 Native 上体现出来。
     */
    public static String mem() {
        Runtime runtime = Runtime.getRuntime();
        long javaMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long nativeMb = Debug.getNativeHeapAllocatedSize() / 1024 / 1024;
        return "Java " + javaMb + "MB / Native " + nativeMb + "MB";
    }

    private static String stamp() {
        synchronized (FMT) {
            return FMT.format(new Date());
        }
    }
}
