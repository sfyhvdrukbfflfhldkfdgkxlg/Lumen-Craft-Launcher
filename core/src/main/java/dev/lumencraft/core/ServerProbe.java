package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Java Edition status handshake (1.7+). An address must come from the user's selection. */
public final class ServerProbe {
    private ServerProbe() {}

    public static final class Status {
        public final String address, versionName, motd, loaderHint;
        public final int protocol, online, max;
        public final long latencyMs;
        public final List<String> advertisedMods;

        private Status(String address, JSONObject data, long latency) throws JSONException {
            this.address = address;
            JSONObject version = data.getJSONObject("version");
            protocol = version.getInt("protocol");
            versionName = version.getString("name");
            JSONObject players = data.optJSONObject("players");
            online = players == null ? -1 : players.optInt("online", -1);
            max = players == null ? -1 : players.optInt("max", -1);
            StringBuilder description = new StringBuilder();
            flatten(data.opt("description"), description, 0);
            motd = description.toString().replaceAll("§[0-9a-fk-orA-FK-OR]", "");

            List<String> mods = new ArrayList<>();
            String hint = "";
            JSONObject forgeData = data.optJSONObject("forgeData");
            JSONObject modinfo = data.optJSONObject("modinfo");
            if (forgeData != null) {
                int fmlNet = forgeData.optInt("fmlNetworkVersion", 2);
                hint = fmlNet >= 3 ? "Forge/NeoForge (FML3+)" : "Forge/FML";
                JSONArray list = forgeData.optJSONArray("mods");
                if (list != null) {
                    for (int i = 0; i < Math.min(list.length(), 256); i++) {
                        JSONObject m = list.optJSONObject(i);
                        if (m == null) continue;
                        String modId = m.optString("modId", "");
                        String modVer = m.optString("modmarker", "");
                        if (!modId.isEmpty() && !"minecraft".equals(modId) && !"forge".equals(modId)) {
                            mods.add(modVer.isEmpty() ? modId : modId + "@" + modVer);
                        }
                    }
                }
            } else if (modinfo != null) {
                hint = "Forge/FML";
                JSONArray list = modinfo.optJSONArray("modList");
                if (list != null) {
                    for (int i = 0; i < Math.min(list.length(), 256); i++) {
                        JSONObject m = list.optJSONObject(i);
                        if (m == null) continue;
                        String modId = m.optString("modid", "");
                        String modVer = m.optString("version", "");
                        if (!modId.isEmpty() && !"minecraft".equals(modId) && !"FML".equals(modId) && !"Forge".equals(modId)) {
                            mods.add(modVer.isEmpty() ? modId : modId + "@" + modVer);
                        }
                    }
                }
            } else {
                String low = versionName.toLowerCase(Locale.ROOT);
                if (low.contains("neoforge")) hint = "NeoForge";
                else if (low.contains("fabric")) hint = "Fabric";
                else if (low.contains("quilt")) hint = "Quilt";
                else if (low.contains("purpur")) hint = "Purpur";
                else if (low.contains("paper")) hint = "Paper";
            }
            loaderHint = hint;
            advertisedMods = Collections.unmodifiableList(mods);
            latencyMs = latency;
        }

        /** Server-advertised name must match one entire official id. Proxies and ranges need user input. */
        public String exactVersion(Collection<String> officialIds) {
            return officialIds.contains(versionName) ? versionName : null;
        }

        private static void flatten(Object part, StringBuilder out, int depth) {
            if (depth > 16 || out.length() >= 2048) return;
            if (part instanceof JSONObject) {
                JSONObject obj = (JSONObject)part;
                flatten(obj.opt("text"), out, depth + 1);
                flatten(obj.optJSONArray("extra"), out, depth + 1);
            } else if (part instanceof JSONArray) {
                JSONArray array = (JSONArray)part;
                for (int i = 0; i < array.length() && out.length() < 2048; i++)
                    flatten(array.opt(i), out, depth + 1);
            } else if (part instanceof String) {
                String s = (String)part;
                out.append(s, 0, Math.min(s.length(), 2048 - out.length()));
            }
        }
    }

    static final class Endpoint {
        final String host;
        final int port;
        final boolean lookup;
        Endpoint(String host, int port, boolean lookup) {
            this.host = host; this.port = port; this.lookup = lookup;
        }
    }

    static Endpoint parse(String input) throws IOException {
        if (input == null) throw new IOException("请输入服务器地址");
        String raw = input.trim();
        if (raw.isEmpty() || raw.length() > 300 || raw.matches(".*[\\s/\\\\@?#].*"))
            throw new IOException("地址应为域名、IPv4 或 IPv6，可指定端口");
        String host = raw;
        String portText = null;
        if (raw.startsWith("[")) {
            int end = raw.indexOf(']');
            if (end < 2 || raw.substring(1, end).indexOf(':') < 0 ||
                    (end != raw.length()-1 && raw.charAt(end+1) != ':'))
                throw new IOException("IPv6 地址格式不正确");
            host = raw.substring(1, end);
            if (end < raw.length()-1) portText = raw.substring(end+2);
        } else if (raw.indexOf(':') >= 0 && raw.indexOf(':') == raw.lastIndexOf(':')) {
            host = raw.substring(0, raw.indexOf(':'));
            portText = raw.substring(raw.indexOf(':')+1);
        }
        if (host.isEmpty()) throw new IOException("缺少主机名");
        if (host.indexOf(':') >= 0) {
            if (!host.matches("[0-9a-fA-F:.]+")) throw new IOException("IPv6 地址格式不正确");
            InetAddress.getByName(host); // literal parse
        } else {
            try { host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES); }
            catch (IllegalArgumentException ex) { throw new IOException("域名格式不正确", ex); }
            if (host.endsWith(".")) host = host.substring(0, host.length()-1);
            if (host.isEmpty() || host.length() > 253) throw new IOException("域名长度不正确");
        }
        int port = 25565;
        if (portText != null) {
            if (!portText.matches("[0-9]{1,5}")) throw new IOException("端口应为 1 至 65535");
            port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) throw new IOException("端口应为 1 至 65535");
        }
        return new Endpoint(host, port, portText == null && host.contains(".") &&
            host.indexOf(':') < 0 && !host.matches("[0-9.]+"));
    }

    public static Status probe(String address) throws IOException {
        return probe(address, Collections.<InetAddress>emptyList());
    }

    public static Status probe(String address, List<InetAddress> dnsServers) throws IOException {
        Endpoint original = parse(address);
        List<Endpoint> targets = original.lookup ? SrvLookup.resolve(original.host, dnsServers) :
            Collections.<Endpoint>emptyList();
        if (targets.isEmpty()) targets = Collections.singletonList(original);
        IOException last = null;
        for (Endpoint target : targets) {
            try { return exchange(address.trim(), original, target); }
            catch (IOException e) { last = e; }
        }
        throw last;
    }

    private static Status exchange(String address, Endpoint original, Endpoint target) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host, target.port), 3500);
            socket.setSoTimeout(3500);
            socket.setTcpNoDelay(true);
            DataOutputStream network = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(deadlineInput(socket));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream hello = new DataOutputStream(bytes);
            varInt(hello, 0); varInt(hello, -1);
            byte[] host = original.host.getBytes(StandardCharsets.UTF_8);
            varInt(hello, host.length); hello.write(host);
            hello.writeShort(original.port); varInt(hello, 1);
            varInt(network, bytes.size()); bytes.writeTo(network);
            network.writeByte(1); network.writeByte(0); network.flush();

            int length = varInt(in);
            if (length < 3 || length > 131080) throw new IOException("状态数据包长度无效");
            byte[] frame = new byte[length]; in.readFully(frame);
            DataInputStream data = new DataInputStream(new ByteArrayInputStream(frame));
            if (varInt(data) != 0) throw new IOException("状态响应类型错误");
            int size = varInt(data);
            if (size != data.available()) throw new IOException("状态 JSON 长度错误");
            byte[] json = new byte[size]; data.readFully(json);
            long started = System.nanoTime();
            network.writeByte(9); network.writeByte(1); network.writeLong(started); network.flush();
            if (varInt(in) != 9 || varInt(in) != 1 || in.readLong() != started)
                throw new IOException("Ping 响应不匹配");
            long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            return new Status(address, parseStatus(json), latency);
        } catch (JSONException e) { throw new IOException("无法解析服务器状态", e); }
    }

    private static JSONObject parseStatus(byte[] bytes) throws IOException, JSONException {
        String raw = new String(bytes, StandardCharsets.UTF_8);
        boolean string = false, escape = false;
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (string) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') string = false;
            } else if (c == '"') string = true;
            else if (c == '{' || c == '[') {
                if (++depth > 48) throw new IOException("服务器状态嵌套层级过多");
            } else if (c == '}' || c == ']') {
                if (--depth < 0) throw new IOException("状态 JSON 无效");
            } else if (c == '\'' || c == '/' || c == '#') {
                throw new IOException("服务器状态需要标准 JSON");
            }
        }
        if (string || depth != 0) throw new IOException("状态 JSON 未闭合");
        return new JSONObject(raw);
    }

    private static InputStream deadlineInput(final Socket socket) throws IOException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        return new FilterInputStream(socket.getInputStream()) {
            long nextRefresh;
            void refresh() throws IOException {
                long now = System.nanoTime();
                if (now >= deadline) throw new SocketTimeoutException("服务器响应超时");
                if (now >= nextRefresh) {
                    socket.setSoTimeout((int)Math.min(3500, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline-now))));
                    nextRefresh = now + TimeUnit.MILLISECONDS.toNanos(100);
                }
            }
            public int read() throws IOException { refresh(); return in.read(); }
            public int read(byte[] b,int off,int len) throws IOException { refresh(); return in.read(b,off,len); }
        };
    }
    static void varInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~127) != 0) {
            out.writeByte((value & 127) | 128); value >>>= 7;
        }
        out.writeByte(value);
    }

    static int varInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            if (shift == 28 && (b & 0xf0) != 0) throw new IOException("VarInt 溢出");
            value |= (b & 127) << shift;
            if ((b & 128) == 0) return value;
        }
        throw new IOException("VarInt 过长");
    }
}
