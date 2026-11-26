package dev.lumencraft.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Looper;
import android.os.Process;
import java.io.File;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** ADB-only acceptance test. Run under the installed debug application's UID. */
public final class RuntimeDeviceCheck {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        Object thread=at.getDeclaredMethod("systemMain").invoke(null);
        Context sys=(Context)at.getDeclaredMethod("getSystemContext").invoke(thread);
        Context app=sys.createPackageContext("dev.lumencraft.android",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
        System.out.println("Testing UID " + Process.myUid());
        if ("graphics".equals(args[0])) {
            ClassLoader target = app.getClassLoader();
            Object gles = target.loadClass("dev.lumencraft.runtime.GraphicsProbe").getMethod("inspect").invoke(null);
            Object legacy = target.loadClass("dev.lumencraft.LegacyGraphics").getMethod("check").invoke(null);
            System.out.println(gles);
            System.out.println(legacy);
            if (gles.toString().contains("PASS") && legacy.toString().contains("PASS")) System.out.println("PASS readback");
            return;
        }
        if ("tls".equals(args[0])) {
            URL url = new URL("https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download/jre8-android-x86.tar.xz");
            for (int i=0;i<5;i++) {
                HttpsURLConnection c=RuntimeTls.open(app,url);
                try {
                    int status=c.getResponseCode();
                    System.out.println(url.getHost()+" "+status+" "+c.getCipherSuite());
                    if (status==200) {
                        if (c.getInputStream().read()!=253) throw new Exception("Bad XZ prefix");
                        System.out.println("PASS HTTPS and XZ");
                        return;
                    }
                    url=new URL(url,c.getHeaderField("Location"));
                } finally { c.disconnect(); }
            }
            throw new Exception("Too many redirects");
        }
        if (!"zip".equals(args[0]) || args.length!=3) throw new IllegalArgumentException("tls | zip <file> <java-major>");
        final File area=new File(app.getCacheDir(),"runtime-check-"+System.nanoTime());
        if (!area.mkdirs()) throw new Exception("Cannot create isolated test directory");
        Context check=new ContextWrapper(app) {
            @Override public File getFilesDir() { return area; }
        };
        RuntimeStore.install(check,Uri.fromFile(new File(args[1])),Integer.parseInt(args[2]));
        System.out.println("PASS private runtime import / JVM invocation: "+area);
    }
}

