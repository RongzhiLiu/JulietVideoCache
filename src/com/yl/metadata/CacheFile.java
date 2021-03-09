package com.yl.metadata;

import java.io.File;

public class CacheFile {
    File file;
    long offset;
    long nextOffset;
    long videoLength;// 视频总长
    public CacheFile(File file, long offset) {
        this.file = file;
        this.offset = offset;
    }
}
