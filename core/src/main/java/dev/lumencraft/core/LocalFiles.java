package dev.lumencraft.core;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Local instance operations. All input archives are treated as untrusted data. */
public final class LocalFiles {
    private static final long MAX_SAVE = 4L << 30;
    private static final int ENTRY_LIMIT = 100000;
    private LocalFiles() {}

    public static File importJar(InputStream in, File mods) throws Exception {
        mkdir(mods);
        File stage = File.createTempFile("import-", ".tmp", mods);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(stage)), sha)) {
                copy(in, out, 512L << 20);
            }
            try (ZipFile zip = new ZipFile(stage)) {
                if (zip.size() > ENTRY_LIMIT) throw new IOException("JAR 文件条目过多");
                boolean recognized = false;
                for (String key : new String[]{"fabric.mod.json","quilt.mod.json","META-INF/mods.toml","META-INF/neoforge.mods.toml","mcmod.info"})
                    recognized |= zip.getEntry(key) != null;
                if (!recognized) throw new IOException("没有识别到模组元数据");
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT,"%02x", b & 255));
            File target = new File(mods,"local-" + hex + ".jar");
            synchronized (LocalFiles.class) {
                if (target.isFile()) return target;
                if (new File(target + ".disabled").exists()) throw new IOException("此 JAR 已导入并停用，可在模组列表中启用");
                if (!stage.renameTo(target)) throw new IOException("无法发布已验证的 JAR");
            }
            return target;
        } finally { stage.delete(); }
    }

    public static int backup(File saves, OutputStream output) throws IOException {
        int[] count = {0};
        long[] bytes = {0};
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
            walk(saves.getCanonicalFile(), "", zip, count, bytes);
            if (count[0] == 0) throw new IOException("没有可备份的存档文件");
        }
        return count[0];
    }
    private static void walk(File current, String prefix, ZipOutputStream zip, int[] count, long[] bytes) throws IOException {
        File[] children = current.listFiles();
        if (children == null) throw new IOException("无法读取存档目录");
        for (File f : children) {
            if (!f.getAbsoluteFile().equals(f.getCanonicalFile())) throw new IOException("存档包含符号链接");
            String path = prefix + f.getName();
            pathCheck(path);
            if (f.isDirectory()) walk(f, path + "/", zip, count, bytes);
            else if (f.isFile()) {
                if (++count[0] > ENTRY_LIMIT) throw new IOException("存档文件过多");
                zip.putNextEntry(new ZipEntry(path));
                try (InputStream in = new BufferedInputStream(new FileInputStream(f))) { bytes[0] += copy(in, zip, MAX_SAVE - bytes[0]); }
                zip.closeEntry();
            }
        }
    }

    public static synchronized List<String> restore(InputStream in, File saves) throws IOException {
        mkdir(saves);
        File stage = new File(saves.getParentFile(),".restore-" + UUID.randomUUID());
        mkdir(stage);
        List<File> committed = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            long bytes = 0;
            int entries = 0;
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(in))) {
                for (ZipEntry e; (e=zip.getNextEntry()) != null;) {
                    String path=e.getName();
                    if (e.isDirectory() && path.endsWith("/")) path=path.substring(0,path.length()-1);
                    pathCheck(path);
                    if (++entries > ENTRY_LIMIT) throw new IOException("备份条目过多");
                    File f = new File(stage,path);
                    if (e.isDirectory()) mkdir(f);
                    else {
                        mkdir(f.getParentFile());
                        if (!f.createNewFile()) throw new IOException("备份有重名文件");
                        try (OutputStream out=new BufferedOutputStream(new FileOutputStream(f))) { bytes += copy(zip,out,MAX_SAVE - bytes); }
                    }
                }
            }
            File[] worlds = stage.listFiles();
            if (worlds == null || worlds.length == 0) throw new IOException("备份为空");
            for (File world : worlds)
                if (!new File(world,"level.dat").isFile()) throw new IOException("备份须包含 世界目录/level.dat");
            for (File world : worlds) {
                String name = world.getName();
                File target=new File(saves,name);
                for (int i=1; target.exists(); i++) target=new File(saves,name+"-restored-"+i);
                if (!world.renameTo(target)) throw new IOException("无法恢复存档 " + name);
                names.add(target.getName());
                committed.add(target);
            }
            return names;
        } catch (IOException ex) {
            for (File f : committed) discard(f);
            throw ex;
        } finally { discard(stage); }
    }
    private static void pathCheck(String path) throws IOException {
        if (path.isEmpty() || path.length()>1024 || path.contains("\\") || path.contains(":") || path.indexOf(0)>=0)
            throw new IOException("无效备份路径");
        String[] parts = path.split("/", -1);
        if (parts.length>64) throw new IOException("备份目录层级过深");
        for (String part : parts) if (part.isEmpty() || part.equals("..") || part.equals(".")) throw new IOException("无效备份路径");
    }
    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[65536];
        long total=0;
        for (int n; (n=in.read(buf))!=-1;) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("任务已取消");
            if ((total+=n)>limit) throw new IOException("归档超过可处理大小");
            out.write(buf,0,n);
        }
        return total;
    }
    private static void mkdir(File f) throws IOException { if (!f.mkdirs() && !f.isDirectory()) throw new IOException("无法创建目录 " + f.getName()); }
    private static void discard(File f) {
        File[] children=f.listFiles();
        if (children!=null) for (File c:children) discard(c);
        f.delete();
    }
}
