# yu_video_player_android

把远程视频的开头若干字节缓存到磁盘，让起播直接从磁盘读、其余仍走网络。
基于 Media3 `DataSource`，不需要本地代理，也不需要 `network_security_config`。

这是 `yu_video_player` 的 Android 原生核心，不依赖 Flutter，可单独用于原生 Android 工程。

## 集成

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.yonglemingyu:yu_video_player_android:1.0.0'
}
```

要求 minSdk 21。

## 用法

```java
// 1. 容量（可选，默认全局 1GB、单条 6MB）
YUVideoCache.setMaxTotalBytes(1024L * 1024 * 1024);
YUVideoCache.setMaxBytesPerVideo(6L * 1024 * 1024);

// 2. 曝光时预取，失败静默
YUVideoCache.prefetch(context, vid, realUrl);

// 3. 用缓存版 MediaSource 替代 MediaItem
player.setMediaSource(YUVideoCache.mediaSource(context, vid, realUrl));

// 4. 离开播放页：释放使用计数
YUVideoCache.release(context, vid);

// 5. 用量与清理
YUVideoCache.count(context);
YUVideoCache.totalSize(context);
YUVideoCache.entries(context);
YUVideoCache.clear(context, vid);
YUVideoCache.clearAll(context);
```

缓存以 `vid` 为 key，与 URL 无关，签名变化不影响命中。

## 签名续签

```java
YUVideoCache.renewer = url -> refreshSignedUrl(url);
```

## 日志

```bash
adb logcat -s YUPlayer
```

关闭：`YULog.enabled = false`。

## License

MIT
