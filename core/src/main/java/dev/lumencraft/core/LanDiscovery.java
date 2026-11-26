package dev.lumencraft.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Passive Java Edition "Open to LAN" advertisements, scoped to the active Android network. */
public final class LanDiscovery {
    private LanDiscovery() {}
    private static final Pattern ANNOUNCE=Pattern.compile("\\[MOTD\\](.{0,1024}?)\\[/MOTD\\]\\[AD\\]([0-9]{1,5})\\[/AD\\]",Pattern.DOTALL);
    public static final class World {
        public final String address,title;
        private World(String address,String title) {this.address=address;this.title=title;}
    }
    static World decode(DatagramPacket packet) {
        Matcher m=ANNOUNCE.matcher(new String(packet.getData(),packet.getOffset(),packet.getLength(),StandardCharsets.UTF_8));
        if (!m.matches()) return null;
        int port=Integer.parseInt(m.group(2));
        if(port<1 || port>65535)return null;
        return new World(packet.getAddress().getHostAddress()+":"+port,m.group(1));
    }
    public static List<World> listen(NetworkInterface link) throws IOException {
        if(link==null) throw new IOException("当前网络没有可用的局域网接口");
        InetSocketAddress group=new InetSocketAddress(InetAddress.getByName("224.0.2.60"),4445);
        Map<String,World> found=new LinkedHashMap<>();
        try(MulticastSocket socket=new MulticastSocket(null)){
            socket.setReuseAddress(true);socket.bind(new InetSocketAddress(4445));
            socket.setSoTimeout(600);socket.joinGroup(group,link);
            byte[] buf=new byte[4096];
            long start=System.nanoTime();
            while(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(5)){
                if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消监听");
                DatagramPacket packet=new DatagramPacket(buf,buf.length);
                try { socket.receive(packet); } catch(SocketTimeoutException ignored) { continue; }
                World world=decode(packet);if(world!=null)found.put(world.address,world);
            }
        }
        return new ArrayList<>(found.values());
    }
}
