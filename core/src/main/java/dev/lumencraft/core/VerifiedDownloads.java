package dev.lumencraft.core;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Content-addressed download transport. A target becomes visible only after verification. */
public final class VerifiedDownloads {
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final Object[] LOCKS = new Object[64];
    static { for (int n = 0; n < LOCKS.length; n++) LOCKS[n] = new Object(); }

    interface Opener { HttpURLConnection open(URL url) throws IOException; }
    private final Opener opener;
    private final boolean useMirrors;

    public VerifiedDownloads() { this(url -> (HttpURLConnection) url.openConnection(), true); }
    VerifiedDownloads(Opener opener) { this(opener, false); }
    private VerifiedDownloads(Opener opener, boolean useMirrors) {
        this.opener = opener;
        this.useMirrors = useMirrors;
    }

    static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已取消");
    }

    static void directory(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
            throw new IOException("无法创建目录 " + dir);
    }

    static void replace(File src, File dst) throws IOException {
        if (src.renameTo(dst)) return; // Atomic same-filesystem rename on Android.
        if (File.separatorChar == '\\') { WindowsFiles.replace(src, dst); return; }
        throw new IOException("无法提交文件 " + dst);
    }

    // Isolated so API 23 never loads java.nio.file classes. Also used by the Windows JVM test suite.
    private static final class WindowsFiles {
        static void replace(File src, File dst) throws IOException {
            java.nio.file.Files.move(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private HttpURLConnection open(String address, long offset) throws IOException {
        URL url = new URL(address);
        for (int hop = 0; hop <= 5; hop++) {
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null)
                throw new IOException("下载地址必须使用 HTTPS");
            HttpURLConnection conn = opener.open(url);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "LumenCraftAndroid/Alpha1.2.0 (https://github.com/sfyhvdrukbfflfhldkfdgkxlg/Lumen-Craft-Launcher)");
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) conn.setRequestProperty("Range", "bytes=" + offset + "-");
            try {
                int status = conn.getResponseCode();
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308)
                    return conn;
                String location = conn.getHeaderField("Location");
                if (location == null) throw new IOException("重定向缺少目标地址");
                url = new URL(url, location);
            } catch (IOException ex) {
                conn.disconnect();
                throw ex;
            }
            conn.disconnect();
        }
        throw new IOException("重定向过多");
    }

    byte[] metadata(String url, int limit) throws IOException {
        interrupted();
        HttpURLConnection conn = open(url, 0);
        try {
            if (conn.getResponseCode() != 200) throw new IOException("元数据 HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                transfer(in, out, limit);
                return out.toByteArray();
            }
        } finally { conn.disconnect(); }
    }

    static long transfer(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[32768];
        long written = 0;
        int len;
        while ((len = in.read(buffer)) != -1) {
            interrupted();
            written += len;
            if (written > limit) throw new IOException("文件超过预期大小");
            out.write(buffer, 0, len);
        }
        return written;
    }

    public static String digest(File f, String algorithm) throws IOException {
        final MessageDigest md;
        try { md = MessageDigest.getInstance(algorithm); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                interrupted();
                md.update(buf, 0, n);
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte b : md.digest()) result.append(Integer.toHexString((b & 255) | 256).substring(1));
        return result.toString();
    }

    /** Use size -1 for a digest-pinned metadata or Maven file. The transfer is then capped at 8 MiB. */
    public File fetch(String address, File target, String expected, long size) throws IOException {
        if (expected == null || !expected.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})") || size < -1)
            throw new IOException("缺少有效的文件校验信息");
        String hash = expected.toLowerCase(Locale.ROOT);
        String algorithm = hash.length() == 40 ? "SHA-1" : "SHA-256";
        File file = target.getCanonicalFile();
        synchronized (LOCKS[file.hashCode() & (LOCKS.length - 1)]) {
            interrupted();
            if (valid(file, hash, algorithm, size)) return file;
            directory(file.getParentFile());
            File part = new File(file.getParentFile(), file.getName() + ".part");
            IOException last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                interrupted();
                String candidate = (useMirrors && attempt == 0) ? MirrorBench.rewrite(address) : address;
                try {
                    if (size < 0 || !part.exists() || part.length() != size) {
                        download(candidate, part, size);
                    }
                    if (!valid(part, hash, algorithm, size)) {
                        discard(part);
                        throw new IOException("完整性校验失败 " + file.getName());
                    }
                    replace(part, file);
                    return file;
                } catch (IOException ex) {
                    interrupted();
                    last = ex;
                    if (attempt < 2) {
                        try { Thread.sleep(250L << attempt); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); interrupted(); }
                    }
                }
            }
            throw last;
        }
    }

    private static boolean valid(File file, String hash, String algorithm, long size) throws IOException {
        return file.isFile() && (size < 0 ? file.length() <= 67108864L : file.length() == size)
                && hash.equals(digest(file, algorithm));
    }

    private static void discard(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("无法重置文件 " + file);
    }

    private void download(String address, File part, long size) throws IOException {
        long limit = size == -1 ? 67108864L : size;
        long offset = part.length();
        if (offset >= limit && offset > 0) { discard(part); offset = 0; }
        HttpURLConnection conn = open(address, offset);
        try {
            int status = conn.getResponseCode();
            boolean append = status == 206;
            if (append) {
                String range = conn.getHeaderField("Content-Range");
                Matcher m = RANGE.matcher(range == null ? "" : range);
                boolean consistent = false;
                if (m.matches()) {
                    try {
                        long first = Long.parseLong(m.group(1));
                        long end = Long.parseLong(m.group(2));
                        long total = Long.parseLong(m.group(3));
                        consistent = first == offset && end >= first && end < total &&
                                total <= limit && (size < 0 || size == total);
                    } catch (NumberFormatException ignored) { }
                }
                if (!consistent) { discard(part); throw new IOException("续传范围不匹配"); }
            } else if (status != 200) {
                if (status == 416) discard(part);
                throw new IOException("下载 HTTP " + status + " · " + part.getName());
            }
            if (!append) offset = 0;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                transfer(in, out, limit - offset);
            }
            if (size >= 0 && part.length() != size) throw new EOFException("下载未完成");
        } finally { conn.disconnect(); }
    }
}
