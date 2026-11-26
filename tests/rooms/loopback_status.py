"""Android room integration fixture. adb reverse tcp:25926 tcp:25926; never bind a public interface."""
import json
import socketserver

def number(stream, first=None):
    value = 0
    for shift in range(0,35,7):
        raw = first if shift == 0 and first is not None else stream.read(1)
        if not raw: raise EOFError()
        b = raw[0]
        value |= (b & 127) << shift
        if b < 128: return value
    raise ValueError("invalid varint")

def var(value):
    out=bytearray()
    while value>=128:
        out.append(128 | (value&127))
        value >>= 7
    out.append(value)
    return bytes(out)

class Status(socketserver.StreamRequestHandler):
    def handle(self):
        self.connection.settimeout(4)
        try:
            first = self.rfile.read(1)
            if first == bytes([254]):
                motd = "Lumen room test§0§1".encode("utf-16be")
                self.wfile.write(bytes([255]) + (len(motd)//2).to_bytes(2,"big") + motd)
                return
            size=number(self.rfile, first)
            if not 2 <= size <= 512: return
            self.rfile.read(size)
            if self.rfile.read(2) != bytes([1,0]): return
            body=json.dumps({"version":{"name":"1.20.1","protocol":763},"players":{"max":1,"online":0},
                "description":{"text":"Lumen room test"}}).encode()
            frame=bytes([0])+var(len(body))+body
            self.wfile.write(var(len(frame))+frame)
            if self.rfile.read(2)==bytes([9,1]):
                token=self.rfile.read(8)
                self.wfile.write(bytes([9,1])+token)
        except (EOFError, ValueError, OSError): pass

if __name__=="__main__":
    with socketserver.ThreadingTCPServer(("127.0.0.1",25926),Status) as server:
        server.serve_forever()
