# Minimal fake Proxmox API over HTTPS with a self-signed cert. Args: port certfile keyfile
import http.server, json, ssl, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get("Authorization") != "PVEAPIToken=me@pve!phone=secret":
            self.send_response(401); self.end_headers(); return
        if self.path.endswith("/version"): data = {"version": "9.1.2"}
        elif self.path.endswith("/cluster/resources"):
            data = [{"type": "node", "node": "pve1", "status": "online", "cpu": 0.01, "maxcpu": 8, "mem": 1, "maxmem": 2},
                    {"type": "qemu", "node": "pve1", "vmid": 202, "name": "docker", "status": "running", "tags": "a;b"}]
        else: self.send_response(404); self.end_headers(); return
        b = json.dumps({"data": data}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def log_message(self, *a): pass
s = http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(sys.argv[2], sys.argv[3])
s.socket = ctx.wrap_socket(s.socket, server_side=True)
s.serve_forever()
