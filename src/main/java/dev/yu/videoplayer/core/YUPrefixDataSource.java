package dev.yu.videoplayer.core;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;

/**
 * 前缀缓存的数据源，对应 iOS 的 YUPrefixResourceLoader。
 *
 * 请求进来后先用磁盘满足能覆盖的部分，剩下的回源；回源收到的数据
 * 在前缀范围内会顺序写盘，用于"边播边写"。
 *
 * 与 iOS 的差异：ExoPlayer 的 DataSource 是拉模型，无法像 URLSession 那样
 * 提前把网络请求发出去，所以本地读完切网络时会有一次 RTT 的空隙。
 * 前缀下满后这条路径只会走一次。
 */
public final class YUPrefixDataSource implements DataSource {

    private final DataSource.Factory upstreamFactory;
    private final YUPrefixStore store;
    private final String vid;
    private final String realUrl;
    private final YUUrlRenewer renewer;

    private DataSpec dataSpec;
    private DataSource current;
    private boolean currentIsLocal;
    private long position;
    private long localEnd;
    private long end = C.LENGTH_UNSET;
    private boolean opened;

    /** 本地可续读的最小区间：太短就来回切 DataSource，不如直接走网络 */
    private static final long MIN_LOCAL_CHUNK = 64 * 1024;

    /** 日志编号与创建时刻：ExoPlayer 释放时会 close，未 close 的就是漏网的数据源 */
    private final int logId = YULog.nextId();
    private final long createdAt = System.currentTimeMillis();
    private boolean closed;

    public YUPrefixDataSource(DataSource.Factory upstreamFactory,
                            YUPrefixStore store,
                            String vid,
                            String realUrl,
                            YUUrlRenewer renewer) {
        this.upstreamFactory = upstreamFactory;
        this.store = store;
        this.vid = vid;
        this.realUrl = realUrl;
        this.renewer = renewer;
        // 登记为「这条 vid 的写者」：预取靠它判断要不要让路，
        // store 也靠它判断现在能不能从共享表里摘掉
        YUPrefixCache.beginWrite(vid);
        YULog.birth("数据源", logId, "url=" + shortUrl(realUrl));
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        // 由我们自己创建的 DataSource 逐个转发
        this.listener = transferListener;
    }

    @Nullable
    private TransferListener listener;

    @Override
    public long open(DataSpec spec) throws IOException {
        this.dataSpec = spec;

        long total = store.totalLength();
        long offset = spec.position;
        end = spec.length == C.LENGTH_UNSET ? total : Math.min(offset + spec.length, total);

        if (total <= 0 || offset < 0 || end <= offset) {
            throw new IOException("invalid range: offset=" + offset + " end=" + end);
        }

        position = offset;

        long limit = store.limit();
        long downloaded = store.downloaded();
        localEnd = offset;
        if (offset < downloaded && offset < limit) {
            localEnd = Math.min(end, Math.min(downloaded, limit));
        }

        if (localEnd > offset) {
            current = openLocal(offset, localEnd - offset);
            currentIsLocal = true;
        } else {
            current = openNetwork(offset, end);
            currentIsLocal = false;
        }

        opened = true;
        return end - offset;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (!opened) throw new IllegalStateException("not opened");
        if (position >= end) return C.RESULT_END_OF_INPUT;

        int read = current.read(buffer, offset, length);

        if (read == C.RESULT_END_OF_INPUT) {
            if (currentIsLocal) {
                closeCurrent();

                // 本地这段读完了。此刻再看一眼共享 store 的进度：
                // 同一个 vid 的另一个播放器可能刚把后面的区间写进来，能接着读磁盘就别走网络。
                long nextLocalEnd = Math.min(end, Math.min(store.downloaded(), store.limit()));
                // 太短的一段不值得来回切 DataSource，交给网络更划算
                if (nextLocalEnd >= end || nextLocalEnd - position >= MIN_LOCAL_CHUNK) {
                    localEnd = nextLocalEnd;
                    current = openLocal(position, nextLocalEnd - position);
                    currentIsLocal = true;
                    return read(buffer, offset, length);
                }

                // 本地读完，无缝切到网络继续
                current = openNetwork(position, end);
                currentIsLocal = false;
                return read(buffer, offset, length);
            }
            return C.RESULT_END_OF_INPUT;
        }

        position += read;

        if (!currentIsLocal) {
            long start = position - read;
            if (start < store.limit()) {
                store.append(buffer, offset, read, start);
            }
        }
        return read;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return current == null ? null : current.getUri();
    }

    @Override
    public void close() throws IOException {
        // ExoPlayer 换源 / 释放时会 close，重复调用是常态，只记第一次
        if (!closed) {
            closed = true;
            YULog.death("数据源", logId,
                    YULog.alive(createdAt) + " url=" + shortUrl(realUrl));
            // 归还写者名额：归零后这条若也没人播、没预取任务，store 才会被摘掉
            YUPrefixCache.endWrite(vid);
        }
        closeCurrent();
        opened = false;
    }

    /** 日志里显示的短名：签名地址很长，取路径末段即可定位 */
    private static String shortUrl(String url) {
        if (url == null || url.isEmpty()) return "-";
        int cut = url.indexOf('?');
        String path = cut >= 0 ? url.substring(0, cut) : url;
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.isEmpty()) return "-";
        return name.length() > 24 ? name.substring(0, 24) : name;
    }

    // ---- 内部 ----

    private DataSource openLocal(long filePosition, long length) throws IOException {
        FileDataSource local = new FileDataSource();
        if (listener != null) local.addTransferListener(listener);
        DataSpec localSpec = new DataSpec.Builder()
                .setUri(Uri.fromFile(store.dataFile()))
                .setPosition(filePosition)
                .setLength(length)
                .build();
        local.open(localSpec);
        return local;
    }

    private DataSource openNetwork(long from, long to) throws IOException {
        DataSource upstream = upstreamFactory.createDataSource();
        if (listener != null) upstream.addTransferListener(listener);

        String url = renewer == null ? realUrl : renewer.renew(realUrl);
        DataSpec.Builder builder = dataSpec.buildUpon()
                .setUri(Uri.parse(url))
                .setPosition(from)
                .setLength(to - from);

        upstream.open(builder.build());
        return upstream;
    }

    private void closeCurrent() {
        if (current == null) return;
        try {
            current.close();
        } catch (IOException ignored) {
        }
        current = null;
    }

    // ---- Factory ----

    public static final class Factory implements DataSource.Factory {

        private final DataSource.Factory upstreamFactory;
        private final YUPrefixStore store;
        private final String vid;
        private final String realUrl;
        private final YUUrlRenewer renewer;

        public Factory(DataSource.Factory upstreamFactory,
                       YUPrefixStore store,
                       String vid,
                       String realUrl,
                       YUUrlRenewer renewer) {
            this.upstreamFactory = upstreamFactory;
            this.store = store;
            this.vid = vid;
            this.realUrl = realUrl;
            this.renewer = renewer;
        }

        @Override
        public DataSource createDataSource() {
            return new YUPrefixDataSource(upstreamFactory, store, vid, realUrl, renewer);
        }
    }
}
