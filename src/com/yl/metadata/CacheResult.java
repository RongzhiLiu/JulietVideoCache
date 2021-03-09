package com.yl.metadata;

import java.io.File;

public class CacheResult {
    //当前已经播放的长度
    long okLength;
    // 下一个缓存的开始点
    long nextOffset;
    //视频是否播放完成
    boolean finish = false;
    //视频总长度
    long allLength;

    String cacheDir;

}
