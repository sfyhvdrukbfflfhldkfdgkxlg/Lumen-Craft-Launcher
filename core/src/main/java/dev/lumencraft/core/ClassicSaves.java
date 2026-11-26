package dev.lumencraft.core;

import java.io.*;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/** RubyDung writes a gzip block array in the instance root. Keep three recent, distinct snapshots. */
public final class ClassicSaves {
    private ClassicSaves() {}
    public static synchronized void checkpoint(File instance) throws IOException {
        File level=new File(instance,"level.dat");
        if (!level.isFile()) return;
        File backups=new File(instance,"world-backups");
        VerifiedDownloads.directory(backups);
        String hash=VerifiedDownloads.digest(level,"SHA-256");
        File dest=new File(backups,hash+".dat");
        if (dest.exists()) return;
        File tmp=File.createTempFile("save-",".tmp",backups);
        try {
            try(InputStream in=new FileInputStream(level);OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp))) {
                VerifiedDownloads.transfer(in,out,16L<<20);
            }
            validate(tmp);
            VerifiedDownloads.replace(tmp,dest);
            File[] old=backups.listFiles((dir,name)->name.matches("[0-9a-f]{64}\\.dat"));
            if(old!=null && old.length>3){
                Arrays.sort(old,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
                for(int i=3;i<old.length;i++)old[i].delete();
            }
        } finally {tmp.delete();}
    }
    public static synchronized void restore(File instance,InputStream in) throws IOException {
        File tmp=File.createTempFile("restore-",".tmp",instance);
        try {
            try(OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp))) {VerifiedDownloads.transfer(in,out,16L<<20);}
            validate(tmp);
            File previous=new File(instance,"level.dat");
            if(previous.exists()){
                try {checkpoint(instance);}
                catch(IOException damaged){
                    File preserved=File.createTempFile("previous-world-",".dat",instance);
                    try(InputStream raw=new FileInputStream(previous);OutputStream out=new FileOutputStream(preserved)){
                        VerifiedDownloads.transfer(raw,out,16L<<20);
                    }
                }
            }
            VerifiedDownloads.replace(tmp,previous);
        } finally {tmp.delete();}
    }
    private static void validate(File file) throws IOException {
        long count=0;
        try(InputStream raw=new FileInputStream(file); InputStream gz=new GZIPInputStream(raw)) {
            byte[] b=new byte[32768];
            for(int n;(n=gz.read(b))!=-1;) {
                count+=n;
                if(count>4194304)throw new IOException("存档尺寸与 RubyDung 世界不匹配");
            }
        }
        if(count!=4194304)throw new IOException("存档不包含完整的 RubyDung 方块数组");
    }
}
