package com.yl.metadata;


import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ALPreVideo {
    private static volatile ALPreVideo preVideo;
    private final ExecutorService preVideoProcessor;
    private final String TAG = "AL_PRELOAD";
    //正在夹在的视频id-》url
    private final HashMap<String, String> loadingVideos = new HashMap<>();

    private ALPreVideo() {
        preVideoProcessor = Executors.newFixedThreadPool(3);
    }

    public static ALPreVideo instance() {
        if (preVideo == null) {
            synchronized (ALPreVideo.class) {
                if (preVideo == null) {
                    preVideo = new ALPreVideo();
                }
            }
        }
        return preVideo;
    }

    public boolean isLoading(String id) {
        synchronized (loadingVideos) {
            return loadingVideos.containsKey(id);
        }
    }

    public void preload(final String url, final String id) {
        if (!ALVideoServer.instance().isInit()) {
            ALUtil.LogE(TAG, "server is not init!");
            return;
        }
        if (url == null || url.length() < 1) {
            ALUtil.LogE(TAG, "preload error, url is illegal!");
            return;
        }
        if (loadingVideos.containsKey(id) || ALUtil.hasPreLoad(id)) {
            return;
        }
        //先判断文件是否存在，再决定是否需要开线程预缓存
        synchronized (loadingVideos) {
            loadingVideos.put(id, url);
        }
        preVideoProcessor.execute(new Runnable() {
            @Override
            public void run() {
                BufferedInputStream bis = null;
                RandomAccessFile randomAccessFile = null;
                try {
                    HttpURLConnection connection = getConnection(url, 0L, -1);
                    Map<String, List<String>> map = connection.getHeaderFields();

                    for (Map.Entry<String, List<String>> entry : map.entrySet()) {
                        System.out.println(entry.getKey() +
                                ":" + entry.getValue());
                    }

                    int contentLength = connection.getContentLength();
                    ALUtil.LogD(TAG, "video length:" + contentLength);

                    InputStream is = connection.getInputStream();
                    bis = new BufferedInputStream(is);
                    String path = ALUtil.createVideoDir(id, contentLength);
                    File file = new File(path, "0");
                    randomAccessFile = new RandomAccessFile(file, "rw");
                    if (!file.exists()) {
                        file.createNewFile();
                    } else {
                        randomAccessFile.seek(0);
                    }

                    int b;
                    byte[] buff = new byte[8];


                    byte[] len = new byte[4];
                    byte[] box = new byte[4];

                    while ((b = bis.read(buff)) != -1) {
                        //写入到本地
                        randomAccessFile.write(buff, 0, b);


                        for (int i = 0; i < 8; i++) {
                            if (i < 4) {
                                len[i] = buff[i];
                            } else {
                                box[i - 4] = buff[i];
                            }
                        }
                        String boxName = ALUtil.byteArrayToStr(box);
                        int l = ALUtil.byteArrayToInt(len);
                        ALUtil.LogD(TAG, "reading box:" + boxName + "  len:" + l);
                        readData(bis, randomAccessFile, l - 8);
                        if (boxName.equals("moov")) {
                            //已经获取到视频文件了，可以暂停了
                            buff = new byte[1024 * 6];
                            for (int i = 0; i < 120 && b != -1; i++) {
                                b = bis.read(buff);
                                randomAccessFile.write(buff, 0, b);
                            }
                            connection.disconnect();
                            randomAccessFile.close();
                            break;
                        }
                        ALUtil.LogE(TAG, "video preload ok,id:" + id);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    synchronized (loadingVideos){
                        loadingVideos.remove(id);
                    }
                    try {
                        if (bis != null) {
                            bis.close();
                        }
                        if (randomAccessFile != null) {
                            randomAccessFile.close();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

    }

    private void readData(BufferedInputStream bis, RandomAccessFile bos, int len) {
        int defBLen = 1024 * 2;
        if (len < 1024 * 2) {
            defBLen = len;
        }
        byte[] buff = new byte[defBLen];
        int b;
        int loseLen = len;
        try {
            while ((b = bis.read(buff)) != -1) {
                bos.write(buff, 0, b);
                loseLen -= b;
                if (loseLen == 0) return;
                if (loseLen < defBLen) {
                    buff = new byte[loseLen];
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public HttpURLConnection getConnection(String httpUrl, Long range, long length) throws Exception {
        String end = "";
        if (length > 0) {
            end = range + length + "";
        }
        URL url = new URL(httpUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("range", "bytes=" + range + "-" + end);
        connection.setRequestProperty("User-Agent", "Android-YL:" + System.currentTimeMillis());
        connection.setRequestProperty("Icy-MetaData", "1");
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setRequestProperty("Connection", "Keep-Alive");
        connection.connect();
        return connection;
    }

}
