package dev.yu.videoplayer.core;

/** 签名续签：每次回源前把 URL 换成当前有效的那一个 */
public interface YUUrlRenewer {
    String renew(String url);
}
