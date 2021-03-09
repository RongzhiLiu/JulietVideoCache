package com.yl.metadata;

import java.io.File;
import java.io.IOException;

public class ALUtil {
    public static String cacheRoot = "/Users/liurongzhi/Downloads/" + "videocache";

    public static int byteArrayToInt(byte[] bytes) {
        int value = 0;
        //由高位到低位
        for (int i = 0; i < 4; i++) {
            int shift = (4 - 1 - i) * 8;
            value += (bytes[i] & 0x000000FF) << shift;//往高位游
        }
        return value;
    }

    public static String byteArrayToStr(byte[] bytes) {
        return new String(bytes);
    }


    /*
        单个文件夹命名 time-start  创建时间-起始位置
        视频对应的文件夹        单个视频文件片段
     videoID-time-length   ->  start
     */
    public static String createVideoDir(String videoID, long length) {
        File root = new File(cacheRoot);
        root.setExecutable(true);
        root.setReadable(true);
        root.setWritable(true);
        if (!root.exists()) {
            root.mkdirs();
        }
        //先查找是否存在
        File[] dirs = root.listFiles();
        if (dirs != null && dirs.length > 0) {
            for (File file : dirs) {
                if (file.getName().startsWith(videoID)) {
                    return file.getAbsolutePath();
                }
            }
        }

        //创建视频所属文件夹
        File dir = new File(root, videoID + "-" + System.currentTimeMillis() + "-" + length);
        dir.setExecutable(true);
        dir.setReadable(true);
        dir.setWritable(true);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir.getAbsolutePath();
    }

    //本地有缓存文件，切大于300kb 则当作有缓存
    public static boolean hasPreLoad(String videoID) {
        File root = new File(cacheRoot);
        root.setExecutable(true);
        root.setReadable(true);
        root.setWritable(true);
        if (!root.exists()) {
            root.mkdirs();
        }

        //查找file是否存在
        File[] dirs = root.listFiles();
        File dir = null;
        if (dirs != null && dirs.length > 0) {
            for (File file : dirs) {
                if (file.getName().startsWith(videoID)) {
                    dir = file;
                    break;
                }
            }
        }
        if (dir == null) {
            return false;
        }
        File[] videoFiles = dir.listFiles();

        if (videoFiles != null && videoFiles.length > 0) {
            for (File file : videoFiles) {
                if (file.getName().startsWith("0") && file.length() > 300 * 1024) {
                    return true;
                }
            }
        }
        return false;

    }

    /*
        单个文件夹命名 time-start  创建时间-起始位置
        视频对应的文件夹        单个视频文件片段
     videoID-time-length   ->  start
     */
    public static File createVideoFile(String videoID, long length, long start) {
        File resultFile = null;
        String parent = createVideoDir(videoID, length);
        //创建视频所属文件夹
        resultFile = new File(parent, start + "");
        resultFile.setExecutable(true);
        resultFile.setReadable(true);
        resultFile.setWritable(true);
        if (!resultFile.exists()) {
            try {
                resultFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        return resultFile;
    }

    public static void LogE(String tag, String msg) {
        System.out.println(tag + ":" + msg);
    }
    public static void LogD(String tag, String msg) {
        System.out.println(tag + ":" + msg);
    }
}
