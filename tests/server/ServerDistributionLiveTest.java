package dev.lumencraft.core;

import java.io.File;
import java.util.jar.JarFile;

public final class ServerDistributionLiveTest {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        ServerDistributions distro = new ServerDistributions(root);
        VanillaInstaller.Progress log = (label,done,total) -> System.out.println(label+" "+done+"/"+total);
        File instance;
        if ("fabric".equals(args[1])) instance = distro.installFabric(args[2],log);
        else if ("paper".equals(args[1])) instance = distro.installPaper(args[2],log);
        else if ("neoforge".equals(args[1]) || "forge".equals(args[1])) instance = distro.installModdedServer(args[2],args[1],log);
        else instance = distro.installPurpur(args[2],log);
        try (JarFile jar = new JarFile(new File(instance,"server.jar"))) {
            String main = jar.getManifest().getMainAttributes().getValue("Main-Class");
            if (main == null) throw new AssertionError("Missing Main-Class");
            System.out.println("PASS "+instance+" "+main);
            if (args[1].equals("fabric")) {
                if (!new File(instance,"vanilla.jar").isFile()) throw new AssertionError("Missing vanilla server");
                for (String relative : jar.getManifest().getMainAttributes().getValue("Class-Path").split(" "))
                    if (!new File(instance,relative).isFile()) throw new AssertionError(relative);
            }
        }
    }
}
