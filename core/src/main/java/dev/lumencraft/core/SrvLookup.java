package dev.lumencraft.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Resolves only _minecraft._tcp through the active network's DNS servers. */
final class SrvLookup {
    private static final SecureRandom RANDOM = new SecureRandom();
    private SrvLookup() {}

    static List<ServerProbe.Endpoint> resolve(String host, List<InetAddress> servers) throws IOException {
        if (servers.isEmpty()) return Collections.emptyList();
        String question = "_minecraft._tcp." + host;
        if (question.length() > 253) return Collections.emptyList();
        // Networks using strict private DNS may suppress direct SRV queries.
        for (int i = 0; i < Math.min(2, servers.size()); i++) {
            try {
                List<Record> records = query(question, servers.get(i));
                if (records.size() == 1 && records.get(0).host.isEmpty())
                    throw new ServiceUnavailable();
                return order(records);
            } catch (ServiceUnavailable e) { throw e; }
              catch (IOException ignored) { /* Try another resolver, then the original address. */ }
        }
        return Collections.emptyList();
    }

    private static final class ServiceUnavailable extends IOException {
        ServiceUnavailable() { super("DNS SRV 声明此服务不可用"); }
    }

    static final class Record {
        final String host;
        final int port, priority, weight;
        Record(String h, int p, int priority, int w) {
            host=h; port=p; this.priority=priority; weight=w;
        }
    }

    static List<ServerProbe.Endpoint> order(List<Record> input) {
        List<Record> left = new ArrayList<>(input);
        List<ServerProbe.Endpoint> result = new ArrayList<>();
        while (!left.isEmpty() && result.size() < 4) {
            int priority = 65535;
            for (Record r : left) priority = Math.min(priority, r.priority);
            List<Record> group = new ArrayList<>();
            int sum = 0;
            for (Record r : left) if (r.priority == priority) { group.add(r); sum += r.weight; }
            Collections.shuffle(group, RANDOM);
            // RFC 2782 puts zero-weight entries at the front before the inclusive draw.
            Collections.sort(group, (a,b) -> Integer.compare(a.weight == 0 ? 0 : 1, b.weight == 0 ? 0 : 1));
            int draw = RANDOM.nextInt(sum + 1);
            for (Record r : group) {
                draw -= r.weight;
                if (draw <= 0) {
                    left.remove(r);
                    if (!r.host.isEmpty() && r.port > 0)
                        result.add(new ServerProbe.Endpoint(r.host, r.port, false));
                    break;
                }
            }
        }
        return result;
    }

    private static List<Record> query(String question, InetAddress server) throws IOException {
        int id = RANDOM.nextInt(65536);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(id); out.writeShort(0x0100); out.writeShort(1);
        out.writeShort(0); out.writeShort(0); out.writeShort(0);
        for (String label : question.split("\\.")) {
            byte[] ascii = label.getBytes(StandardCharsets.US_ASCII);
            out.writeByte(ascii.length); out.write(ascii);
        }
        out.writeByte(0); out.writeShort(33); out.writeShort(1);
        byte[] request = bytes.toByteArray();
        byte[] response;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(server, 53);
            socket.setSoTimeout(1500);
            socket.send(new DatagramPacket(request, request.length));
            DatagramPacket reply = new DatagramPacket(new byte[4096], 4096);
            socket.receive(reply);
            response = Arrays.copyOf(reply.getData(), reply.getLength());
        }
        if (response.length >= 4 && (response[2] & 2) != 0) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(server, 53), 1500);
                socket.setSoTimeout(1500);
                DataOutputStream tcp = new DataOutputStream(socket.getOutputStream());
                tcp.writeShort(request.length); tcp.write(request); tcp.flush();
                DataInputStream data = new DataInputStream(socket.getInputStream());
                response = new byte[data.readUnsignedShort()]; data.readFully(response);
            }
        }
        return parse(response, id, question);
    }

    static List<Record> parse(byte[] packet, int id, String question) throws IOException {
        Reader r = new Reader(packet);
        if (r.u16() != id) throw new IOException("DNS 标识不匹配");
        int flags = r.u16();
        int code = flags & 15;
        if ((flags & 0xfa00) != 0x8000 || (code != 0 && code != 3))
            throw new IOException("DNS 返回错误");
        if (r.u16() != 1) throw new IOException("DNS 问题数无效");
        int answers = r.u16(); r.u16(); r.u16();
        if (!r.name().equalsIgnoreCase(question) || r.u16() != 33 || r.u16() != 1)
            throw new IOException("DNS 问题不匹配");
        List<Record> result = new ArrayList<>();
        if (code == 3) return result;
        for (int i = 0; i < answers; i++) {
            String owner = r.name();
            int type = r.u16(), cls = r.u16();
            r.u16(); r.u16(); // TTL
            int length = r.u16(), end = r.pos + length;
            if (end > packet.length) throw new IOException("DNS 记录长度错误");
            if (owner.equalsIgnoreCase(question) && type == 33 && cls == 1) {
                int priority = r.u16(), weight = r.u16(), port = r.u16();
                String host = r.name();
                if (r.pos != end || (!host.isEmpty() && (port == 0 || !host.matches("[a-zA-Z0-9.-]+"))))
                    throw new IOException("SRV 记录无效");
                result.add(new Record(host, port, priority, weight));
            }
            r.pos = end;
        }
        return result;
    }

    private static final class Reader {
        final byte[] packet;
        int pos;
        Reader(byte[] packet) { this.packet = packet; }
        int u8() throws IOException {
            if (pos >= packet.length) throw new EOFException();
            return packet[pos++] & 255;
        }
        int u16() throws IOException { return (u8()<<8) | u8(); }
        String name() throws IOException {
            int resume = -1;
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < 128; i++) {
                int n = u8();
                if (n == 0) {
                    if (resume >= 0) pos = resume;
                    return name.toString();
                }
                if ((n & 192) == 192) {
                    int target = ((n & 63) << 8) | u8();
                    if (target >= pos - 2) throw new IOException("DNS 压缩指针无效");
                    if (resume < 0) resume = pos;
                    pos = target;
                } else {
                    if (n > 63 || pos+n > packet.length || name.length()+n+1 > 254)
                        throw new IOException("DNS 名称无效");
                    if (name.length() > 0) name.append('.');
                    name.append(new String(packet, pos, n, StandardCharsets.US_ASCII));
                    pos += n;
                }
            }
            throw new IOException("DNS 名称过长");
        }
    }
}
