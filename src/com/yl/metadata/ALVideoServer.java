package com.yl.metadata;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ALVideoServer {
    private static volatile ALVideoServer server;
    private static final Pattern RANGE_HEADER_PATTERN = Pattern.compile("[R,r]ange:[ ]?bytes=(\\d*)-");
    private static int maxCache = 20;
    private static long maxCacheTime = 6 * 60 * 60 * 1000;
    private final ExecutorService socketProcessor;
    private static int port = -1;
    private volatile boolean isInit = false;

    private final String TAG = "AL_SERVER";

    private ALVideoServer() {
        socketProcessor = Executors.newFixedThreadPool(4);
    }

    public synchronized boolean isInit() {
        return isInit;
    }

    public static ALVideoServer instance() {
        if (server == null) {
            synchronized (ALVideoServer.class) {
                if (server == null) {
                    server = new ALVideoServer();
                }
            }
        }
        return server;
    }

    public synchronized void initServer() {
        if (port != -1) {
            //不可重复初始化server
            isInit = true;
            return;
        }
        socketProcessor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ServerSocket serverSocket = new ServerSocket(0, 5);
                    port = serverSocket.getLocalPort();
                    isInit = true;
                    ALUtil.LogD(TAG, "server start,port:" + port);
                    while (true) {
                        final Socket socket = serverSocket.accept();
                        socketProcessor.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    acceptSocket(socket);
                                } catch (Exception e) {
                                    ALUtil.LogE(TAG, "server error");
                                    e.printStackTrace();
                                }
                            }
                        });
                    }
                } catch (Exception e) {
                    isInit = false;
                    e.printStackTrace();
                }
            }
        });
    }

    private void acceptSocket(final Socket socket) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String clientInputStr;
        long range = 0;
        Map<String, String> heads = new HashMap<>();
        String url = "";
        String videoID = "";
        while ((clientInputStr = input.readLine()) != null && clientInputStr.length() > 0) {
            ALUtil.LogD(TAG, clientInputStr);
            if (clientInputStr.contains("bytes=")) {
                range = findRangeOffset(clientInputStr);
            }
            if (clientInputStr.startsWith("GET")) {
                url = clientInputStr.split(" ")[1];
            } else {
                String[] head = clientInputStr.split(": ");
                if (head.length > 1) {
                    heads.put(head[0], head[1]);
                }
            }
        }
        Map<String, String> params = getUrlPramNameAndValue(url);
        videoID = params.get("videoid");
        String host = params.get("host");
        String is_SSL = params.get("s");
        if (host == null || host.length() < 1) {
            socket.close();
            ALUtil.LogE(TAG, "request error,url is illegal :" + url);
            return;
        }

        heads.remove("host");
        heads.remove("Host");
        heads.put("host", host);
        if ("1".equals(is_SSL)) {
            url = "https://" + host + url;
        }else {
            url = "http://" + host + url;
        }
        if (videoID == null || videoID.length() < 1) {
            ALUtil.LogE(TAG, "request error,id is illegal :" + videoID);
            socket.close();
            return;
        }
        writeVideoData(socket, range, url, heads, videoID);
    }

    private void writeVideoData(final Socket socket, final long rang, final String httpUrl, final Map<String, String> heads, String videoID) {

        InputStream input = null;
        OutputStream outputStream = null;
        try {
            input = socket.getInputStream();
            outputStream = socket.getOutputStream();
            long nextRang = rang;
            while (true) {
                CacheResult result = findAndWriteCache(outputStream, videoID, nextRang, nextRang == rang);
                //表示视频播放完成了
                if (result.finish) break;
                long end = requestAndWriteNet(outputStream, videoID, httpUrl, heads, nextRang, result);
                if (end == 0) {
                    //表示视频播放完成了
                    break;
                }
                nextRang = end;
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (input != null) {
                    input.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private CacheResult findAndWriteCache(OutputStream outputStream, String videoID, long rang, boolean needHeader) throws Exception {
        CacheResult result = new CacheResult();
        //记录已缓存的大小
        long cacheLen = 0;
        boolean hasWriteHeader = false;
        if (!needHeader) hasWriteHeader = true;
        long startFind = rang;
        //全部缓存完毕
        CacheFile lastFile = null;
        RandomAccessFile file = null;
        byte[] buff = null;

        try {
            while (true) {
                CacheFile f = findCache(videoID, startFind);
                if (f == null) {
                    ALUtil.LogE(TAG, "没有缓存 startFind：" + startFind);
                    break;
                }
                long length = f.file.length();
                cacheLen = length + f.offset;
                ALUtil.LogE(TAG, "start length：" + cacheLen + "   " + length);
                file = new RandomAccessFile(f.file, "rw");
                long readLength = 0;
                if (!hasWriteHeader && rang >= 0) {
                    file.seek(rang - f.offset);
                    readLength += (rang - f.offset);
                } else if (lastFile != null) {
                    file.seek(lastFile.offset + lastFile.file.length() - f.offset);
                    readLength += (lastFile.offset + lastFile.file.length() - f.offset);
                }

                if (!hasWriteHeader) {
                    outputStream.
                            write(((rang >= 0 ? "HTTP/1.1 206 PARTIAL CONTENT" : "HTTP/1.1 200 OK") + "\r\n" +
                                    "Accept-Ranges: bytes" + "\r\n" +
                                    "Content-Type: video/mp4\r\n" +
                                    "Content-Length: " + (rang >= 0 ? f.videoLength - rang : f.videoLength) + "\r\n" +
                                    "Content-Range: bytes " + (rang >= 0 ? rang : 0) + "-" + (f.videoLength - 1) + "/" + f.videoLength + "\r\n" +
                                    "\r\n").getBytes());
                    hasWriteHeader = true;
                }

                if (buff == null) {
                    buff = new byte[1024 * 8];
                }
                int b;

                while (((b = file.read(buff)) != -1)) {
                    outputStream.write(buff, 0, b);
                    readLength += b;
                }
                cacheLen = f.offset + readLength;
                ALUtil.LogE(TAG, "read length：" + cacheLen + "  " + readLength);
                file.close();
                startFind = cacheLen;
                result.finish = cacheLen >= f.videoLength;
                lastFile = f;
                result.nextOffset = lastFile.nextOffset;
                result.okLength = cacheLen;
                result.allLength = lastFile.videoLength;
                result.cacheDir = lastFile.file.getParent();
                ALUtil.LogD(TAG, "cache write complete," + f.file.getName() + ":" + f.offset + "->" + cacheLen);
            }
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            if (file != null) {
                file.close();
            }
        }
    }

    private long requestAndWriteNet(OutputStream outputStream, String videoID, String httpUrl, Map<String, String> heads, long rang, CacheResult result) throws Exception {
        //请求网络数据
        InputStream is = null;
        try {
            if (result == null) result = new CacheResult();
            URL url = new URL(httpUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            String end = "";
            long start = rang;
            if (result.okLength > 0 && heads.containsKey("Range")) {
                if (result.nextOffset > 1) end = (result.nextOffset - 1) + "";
                heads.put("Range", "bytes=" + (result.okLength) + "-" + end);
                start = result.okLength;
            }

            ALUtil.LogD(TAG, "reading from net head -------");
            for (Map.Entry<String, String> entry : heads.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
                System.out.println("        AL_SERVER http:-> " + entry.getKey() + ": " + entry.getValue());
            }

            connection.connect();

            if (result.okLength <= 0) {
                long length = connection.getContentLength();
                length = length + (rang >= 0 ? rang : 0);
                outputStream.
                        write(((rang >= 0 ? "HTTP/1.1 206 PARTIAL CONTENT" : "HTTP/1.1 200 OK") + "\r\n" +
                                "Accept-Ranges: bytes" + "\r\n" +
                                "Content-Type: video/mp4\r\n" +
                                "Content-Length: " + (rang >= 0 ? length - rang : length) + "\r\n" +
                                "Content-Range: bytes " + (rang >= 0 ? rang : 0) + "-" + (length - 1) + "/" + length + "\r\n" +
                                "\r\n").getBytes());
                result.allLength = length;
                result.cacheDir = ALUtil.createVideoDir(videoID, length);
            }
            byte[] buff = new byte[1024 * 8];
            int b;
            is = connection.getInputStream();

            File file = ALUtil.createVideoFile(videoID, result.allLength, start);
            RandomAccessFile file1 = null;
            if (file != null) {
                file1 = new RandomAccessFile(file, "rw");
            }
            long length = 0;
            while (((b = is.read(buff)) != -1)) {
                outputStream.write(buff, 0, b);
                length += b;
                if (file1 != null) {
                    file1.write(buff, 0, b);
                }
            }
            if (end.length() > 0) {
                return result.nextOffset;
            } else {
                return 0;
            }
        } catch (Exception e) {
            ALUtil.LogD(TAG, "request net error:" + e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            if (is != null) {
                is.close();
            }
        }

    }

    private long findRangeOffset(String request) {
        Matcher matcher = RANGE_HEADER_PATTERN.matcher(request);
        if (matcher.find()) {
            String rangeValue = matcher.group(1);
            return Long.parseLong(rangeValue);
        }
        return -1;
    }

    /*
        单个文件夹命名 time-start  创建时间-起始位置
        视频对应的文件夹        单个视频文件片段
     videoID-time-length   ->  start
     */
    private CacheFile findCache(String videoID, long range) {
        CacheFile file = null;
        File root = new File(ALUtil.cacheRoot);
        if (root.exists()) {
            File[] videoDirs = root.listFiles();
            if (videoDirs == null || videoDirs.length < 1) {
                return null;
            }
            File videoDir = null;
            for (int i = 0; i < videoDirs.length; i++) {
                File vd = videoDirs[i];
                if (vd == null) continue;
                if (vd.getName().startsWith(videoID)) {
                    videoDir = videoDirs[i];
                    break;
                }
            }

            //检测过期文件，并删除
            int num = videoDirs.length;
            if (videoDirs.length > maxCache) {
                boolean forceDelete = num >= maxCache * 2;
                for (int i = 0; i < videoDirs.length; i++) {
                    File vd = videoDirs[i];
                    if (vd == null) continue;
                    if (videoDir != null && videoDir.getName().equals(vd.getName())) {
                        continue;
                    }

                    String[] strs = vd.getName().split("-");
                    if (strs.length < 3) {
                        //文件名非法，直接删除
                        deleteFile(vd);
                        num -= 1;
                        continue;
                    }
                    //缓存时间过期，删除
                    if (System.currentTimeMillis() - Long.parseLong(strs[1]) > maxCacheTime) {
                        ALUtil.LogD(TAG, "video is expired，name:" + vd.getName());
                        deleteFile(vd);
                        num -= 1;
                        continue;
                    }
                    //文件数量大于设定的2倍，则强制删除
                    if (forceDelete && num > maxCache * 0.8f) {
                        deleteFile(vd);
                        num -= 1;
                    }
                }
            }
            if (videoDir == null) {
                return null;
            }
            File[] videoFiles = videoDir.listFiles();
            if (videoFiles == null || videoFiles.length < 1) {
                return null;
            }

            String[] strs = videoDir.getName().split("-");
            long videoLength = Long.parseLong(strs[2]);

            for (int i = 0; i < videoFiles.length - 1; i++) {
                //第i趟比较
                for (int j = 0; j < videoFiles.length - i - 1; j++) {
                    //开始进行比较，如果arr[j]比arr[j+1]的值大，那就交换位置
                    if (!videoFiles[j].exists() || videoFiles[j].getName().equals("") || videoFiles[j].getName().contains("."))
                        continue;
                    long offsetJ = Long.parseLong(videoFiles[j].getName());
                    long offsetJP = Long.parseLong(videoFiles[j + 1].getName());
                    if (offsetJ > offsetJP) {
                        File temp = videoFiles[j];
                        videoFiles[j] = videoFiles[j + 1];
                        videoFiles[j + 1] = temp;
                    }
                }
            }

            // 开始寻找对应的文件
            for (int i = 0; i < videoFiles.length; i++) {
                File f = videoFiles[i];
                if (!f.exists() || f.getName().equals("") || f.getName().contains(".")) continue;
                long fr = Long.parseLong(f.getName());
                if (f.length() - 1 + fr > range && fr <= range) {
                    file = new CacheFile(f, fr);
                    file.videoLength = videoLength;
                    if (i + 1 < videoFiles.length) {
                        File next = videoFiles[i + 1];
                        if (f.exists() && !f.getName().equals("") && !f.getName().contains(".")) {
                            file.nextOffset = Long.parseLong(next.getName());
                        }
                    }
                    break;
                }
            }
        } else {
            return null;
        }
        return file;
    }


    //http://192.168.18.142:50349/39d6/20210112/510e5a6457f05028e5ff1660e485ab13?videoid=12122&host=vv.qianpailive.com
    public String getProxyUrl(String url, String videoID) {
        if (!ALVideoServer.instance().isInit()) {
            ALUtil.LogE(TAG, "server is not init!");
            return url;
        }
        try {
            URL u = new URL(url);
            String host = u.getHost();
            String s = url.replace(host, "127.0.0.1:" + port);
            if (s.contains("https://")) {
                s = s.replace("https://", "http://");
                if (url.contains("?")) {
                    s = s + "&s=" + 1;
                } else {
                    s = s + "?s=" + 1;
                }
            }
            if (url.contains("?")) {
                s = s + "&videoid=" + videoID + "&host=" + host;
            } else {
                s = s + "?videoid=" + videoID + "&host=" + host;
            }
            return s;
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return url;
    }

    /**
     * 删除文件夹
     *
     * @param dirFile
     * @return
     */

    public static boolean deleteFile(File dirFile) {
        // 如果dir对应的文件不存在，则退出
        if (!dirFile.exists()) {
            return false;
        }
        if (dirFile.isFile()) {
            return dirFile.delete();
        } else {
            File[] files = dirFile.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteFile(file);
                }
            }
        }
        return dirFile.delete();
    }

    /**
     * 获取URL中的参数名和参数值的Map集合
     *
     * @param url
     * @return
     */
    private Map<String, String> getUrlPramNameAndValue(String url) {
        String regEx = "(\\?|&+)(.+?)=([^&]*)";//匹配参数名和参数值的正则表达式
        Pattern p = Pattern.compile(regEx);
        Matcher m = p.matcher(url);
        // LinkedHashMap是有序的Map集合，遍历时会按照加入的顺序遍历输出
        Map<String, String> paramMap = new LinkedHashMap<>();
        while (m.find()) {
            String paramName = m.group(2);//获取参数名
            String paramVal = m.group(3);//获取参数值
            paramMap.put(paramName, paramVal);
        }
        return paramMap;
    }

    public static class Builder {
        private String rootPath;
        private long maxCacheTime = 6 * 60 * 60 * 1000;
        private int maxCacheNum = 20;

        public Builder maxTime(long time) {
            this.maxCacheTime = time;
            return this;
        }

        public Builder maxNum(int num) {
            this.maxCacheNum = num;
            return this;
        }

        public Builder path(String path) {
            this.rootPath = path;
            return this;
        }

        public void init() {
            ALUtil.cacheRoot = this.rootPath;
            maxCache = maxCacheNum;
            ALVideoServer.maxCacheTime = maxCacheTime;
            ALVideoServer.instance().initServer();
        }
    }
}
