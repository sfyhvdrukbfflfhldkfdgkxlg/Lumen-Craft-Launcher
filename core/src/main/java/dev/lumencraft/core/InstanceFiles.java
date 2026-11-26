package dev.lumencraft.core;

import java.io.*;
import java.util.UUID;

/** Clone private instance data, retaining receipt references to the shared artifact store. */
public final class InstanceFiles {
    private InstanceFiles() {}
    public static synchronized File duplicate(File root, File source) throws IOException {
        File instances=new File(root,"instances").getCanonicalFile();
        source=source.getCanonicalFile();
        if (!instances.equals(source.getParentFile()) || !new File(source,"install.json").isFile())
            throw new IOException("无法复制此实例");
        String token=UUID.randomUUID().toString();
        File stage=new File(root,".copy-"+token);
        File target=new File(instances,"copy-"+token);
        try {
            cloneInto(source,stage);
            if (!stage.renameTo(target)) throw new IOException("无法提交实例副本");
            return target;
        } finally { discard(stage); }
    }
    private static void cloneInto(File source,File target) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("复制已取消");
        if (!source.getAbsoluteFile().equals(source.getCanonicalFile())) throw new IOException("实例含符号链接");
        if (source.isDirectory()) {
            if (!target.mkdir()) throw new IOException("无法创建副本目录");
            File[] files=source.listFiles();
            if (files==null) throw new IOException("无法读取实例目录");
            for(File f:files) cloneInto(f,new File(target,f.getName()));
        } else {
            try(InputStream in=new BufferedInputStream(new FileInputStream(source));
                OutputStream out=new BufferedOutputStream(new FileOutputStream(target))) {
                VerifiedDownloads.transfer(in,out,source.length());
            }
        }
    }
    private static void discard(File f) {
        File[] children=f.listFiles();
        if(children!=null) for(File child:children)discard(child);
        f.delete();
    }
    public static synchronized File move(File root,File folder,boolean restore) throws IOException {
        File instances=new File(root,"instances").getCanonicalFile();
        File trash=new File(root,"archive").getCanonicalFile();
        File from=restore?trash:instances;
        File to=restore?instances:trash;
        folder=folder.getCanonicalFile();
        if(!from.equals(folder.getParentFile()) || !new File(folder,"install.json").isFile())
            throw new IOException("无法移动此实例");
        VerifiedDownloads.directory(to);
        File target=new File(to,folder.getName());
        if(target.exists() || !folder.renameTo(target)) throw new IOException("同名目录已存在或存储不可写");
        return target;
    }
}
