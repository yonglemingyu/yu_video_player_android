package dev.yu.videoplayer.core;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 单条视频的前缀缓存元数据。
 * 完全按字节配置：单条上限由外部传入，写入 meta 后固定。
 */
final class YUPrefixMeta {

    static final long DEFAULT_MAX_BYTES_PER_VIDEO = 6L * 1024 * 1024;

    final long totalLength;
    final long limitBytes;
    final String contentType;
    final String etag;

    long downloaded;
    long updatedAt;

    YUPrefixMeta(long totalLength, long limitBytes, long downloaded,
               String contentType, long updatedAt, String etag) {
        this.totalLength = totalLength;
        this.limitBytes = limitBytes;
        this.downloaded = downloaded;
        this.contentType = contentType;
        this.updatedAt = updatedAt;
        this.etag = etag;
    }

    static YUPrefixMeta create(long totalLength, long limitBytes, String etag) {
        return new YUPrefixMeta(totalLength, limitBytes, 0, "public.mp4",
                System.currentTimeMillis(), etag);
    }

    /** 实际生效的上限，不超过总长 */
    long limit() {
        if (totalLength <= 0) return 0;
        return Math.min(Math.max(limitBytes, 0), totalLength);
    }

    /** 前缀是否已下载完整 */
    boolean isComplete() {
        return limit() > 0 && downloaded >= limit();
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("totalLength", totalLength);
        json.put("limitBytes", limitBytes);
        json.put("downloaded", downloaded);
        json.put("contentType", contentType);
        json.put("updatedAt", updatedAt);
        json.put("etag", etag == null ? JSONObject.NULL : etag);
        return json;
    }

    static YUPrefixMeta fromJson(String text) throws JSONException {
        JSONObject json = new JSONObject(text);
        String etag = json.isNull("etag") ? null : json.optString("etag", null);
        return new YUPrefixMeta(
                json.getLong("totalLength"),
                json.getLong("limitBytes"),
                json.optLong("downloaded", 0),
                json.optString("contentType", "public.mp4"),
                json.optLong("updatedAt", 0),
                etag);
    }
}
