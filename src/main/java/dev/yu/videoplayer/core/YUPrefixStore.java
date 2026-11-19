package dev.yu.videoplayer.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * 前缀缓存的文件存储。
 *
 * 目录结构：
 *   MediaCache/{vid}/prefix.dat   从 0 开始连续的一段字节
 *   MediaCache/{vid}/meta.json    总长、单条上限、已下载字节
 *
 * 只支持顺序追加，因为单文件方案无法表达空洞。
 * 这也是"边播边写"能成立的前提：播放总是从某一位置连续往后读。
 */
public final class YUPrefixStore {

    private static final int COPY_BUFFER = 256 * 1024;

    private final File dir;
    private final File dataFile;
    private final File metaFile;
    private YUPrefixMeta meta;

    private YUPrefixStore(File dir, YUPrefixMeta meta) throws IOException {
        this.dir = dir;
        this.dataFile = new File(dir, "prefix.dat");
        this.metaFile = new File(dir, "meta.json");
        this.meta = meta;
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
    }

    // ---- 打开 ----

    static YUPrefixStore open(File dir) {
        File metaFile = new File(dir, "meta.json");
        if (!metaFile.exists()) return null;
        try {
            String text = readFully(metaFile);
            YUPrefixStore store = new YUPrefixStore(dir, YUPrefixMeta.fromJson(text));
            store.validate();
            return store;
        } catch (Exception e) {
            return null;
        }
    }

    /** 只读 meta，不做一致性校验，不产生副作用（列表用） */
    static YUPrefixMeta readMeta(File dir) {
        File metaFile = new File(dir, "meta.json");
        if (!metaFile.exists()) return null;
        try {
            return YUPrefixMeta.fromJson(readFully(metaFile));
        } catch (Exception e) {
            return null;
        }
    }

    static YUPrefixStore create(File dir, long totalLength, long limitBytes, String etag) {
        try {
            YUPrefixStore store = new YUPrefixStore(dir, YUPrefixMeta.create(totalLength, limitBytes, etag));
            //noinspection ResultOfMethodCallIgnored
            store.dataFile.delete();
            store.meta.downloaded = 0;
            store.persistMeta();
            return store;
        } catch (IOException e) {
            return null;
        }
    }

    /** 文件与 meta 不一致时整体作废，避免读到残缺数据 */
    private void validate() {
        long size = dataFile.exists() ? dataFile.length() : 0;
        if (size == meta.downloaded) return;
        meta.downloaded = 0;
        //noinspection ResultOfMethodCallIgnored
        dataFile.delete();
        persistMeta();
    }

    // ---- 读 ----

    File dataFile() {
        return dataFile;
    }

    synchronized YUPrefixMeta meta() {
        return meta;
    }

    synchronized long downloaded() {
        return meta.downloaded;
    }

    synchronized long limit() {
        return meta.limit();
    }

    synchronized long totalLength() {
        return meta.totalLength;
    }

    /**
     * 只有整个区间都落在已下载范围内才返回 true，调用方据此决定读盘还是回源。
     */
    synchronized boolean covers(long offset, long length) {
        if (offset < 0 || length <= 0) return false;
        long end = offset + length;
        return end <= meta.downloaded && end <= meta.limit();
    }

    /** 从文件中读一段；读不到返回 -1 */
    synchronized int read(long position, byte[] buffer, int offset, int length) {
        if (!covers(position, 1)) return -1;
        try (RandomAccessFile raf = new RandomAccessFile(dataFile, "r")) {
            raf.seek(position);
            int read = raf.read(buffer, offset, length);
            return read <= 0 ? -1 : read;
        } catch (IOException e) {
            return -1;
        }
    }

    // ---- 写 ----

    /** 只接受从 downloaded 开始的顺序写入；跳跃或越过 limit 的写入直接丢弃 */
    synchronized void append(byte[] buffer, int offset, int length, long position) {
        if (length <= 0) return;
        if (position != meta.downloaded) return;
        if (position + length > meta.limit()) return;
        try (FileOutputStream out = new FileOutputStream(dataFile, true)) {
            out.write(buffer, offset, length);
            meta.downloaded += length;
            meta.updatedAt = System.currentTimeMillis();
            persistMeta();
        } catch (IOException ignored) {
        }
    }

    /** 把一批顺序数据文件追加进来（预取续传用），完成后删除临时文件 */
    synchronized void appendFile(File source) {
        byte[] buffer = new byte[COPY_BUFFER];
        try (InputStream in = new FileInputStream(source)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                long position = meta.downloaded;
                if (position + read > meta.limit()) {
                    read = (int) Math.max(0, meta.limit() - position);
                    if (read <= 0) break;
                }
                append(buffer, 0, read, position);
            }
        } catch (IOException ignored) {
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
    }

    /** 首次预取完成后整体替换 */
    synchronized void install(File source) {
        long size = source.length();
        if (size <= 0) return;
        long finalSize = Math.min(size, meta.limit());

        //noinspection ResultOfMethodCallIgnored
        dataFile.delete();
        if (!source.renameTo(dataFile)) {
            // 跨分区时 rename 会失败，退化为复制
            try (InputStream in = new FileInputStream(source);
                 FileOutputStream out = new FileOutputStream(dataFile)) {
                byte[] buffer = new byte[COPY_BUFFER];
                long remaining = finalSize;
                while (remaining > 0) {
                    int want = (int) Math.min(buffer.length, remaining);
                    int read = in.read(buffer, 0, want);
                    if (read <= 0) break;
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
            } catch (IOException ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }

        // 服务端忽略 Range 时会返回整片，截断到 limit
        if (dataFile.length() > finalSize) {
            try (RandomAccessFile raf = new RandomAccessFile(dataFile, "rw")) {
                raf.setLength(finalSize);
            } catch (IOException ignored) {
            }
        }

        meta.downloaded = Math.min(dataFile.length(), finalSize);
        meta.updatedAt = System.currentTimeMillis();
        persistMeta();
    }

    // ---- 维护 ----

    synchronized void touch() {
        meta.updatedAt = System.currentTimeMillis();
        persistMeta();
    }

    long diskUsage() {
        return dataFile.exists() ? dataFile.length() : 0;
    }

    void remove() {
        deleteRecursively(dir);
    }

    private void persistMeta() {
        try {
            File tmp = new File(dir, "meta.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(meta.toJson().toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(metaFile)) {
                //noinspection ResultOfMethodCallIgnored
                metaFile.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(metaFile);
            }
        } catch (Exception ignored) {
        }
    }

    // ---- 工具 ----

    private static String readFully(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.max(1024, file.length())];
            int read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    static void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
