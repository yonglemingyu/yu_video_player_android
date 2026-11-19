package dev.yu.videoplayer.core;

/**
 * 全局音频策略，影响此后创建的所有播放器。
 *
 * 不配置时按「独占媒体」处理：会打断其他 App 正在播放的音乐。
 */
public final class YUPlayerAudio {

    /** 与其它 App 混音：true 时不申请音频焦点 */
    private static volatile boolean mixWithOthers = false;

    private YUPlayerAudio() {}

    public static void configure(boolean mix, boolean duck, boolean resumeAfterInterruption) {
        mixWithOthers = mix;
        // duck 由系统在 AudioFocus 授予时按 DUCK 结果自动处理，无需额外开关。
        // resumeAfterInterruption 在 Android 上由 ExoPlayer 的音频焦点管理负责，
        // 目前恒为「中断结束后恢复」，不回滚其行为。
    }

    public static boolean mixWithOthers() {
        return mixWithOthers;
    }
}
