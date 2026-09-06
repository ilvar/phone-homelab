#!/usr/bin/env python3
"""Local emulator smoke-test backend. Never use as a real Portainer server.
Run: python3 tools/mock_portainer.py --port 19000
adb reverse tcp:9000 tcp:19000; connect with API key demo-key.
"""
import argparse
import json
import struct
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

containers = [
    {"Id": "nginx1", "Names": ["/Home dashboard"], "Image": "nginx:alpine", "State": "running", "Status": "Up 2 hours", "Ports": [{"IP": "0.0.0.0", "PrivatePort": 80, "PublicPort": 8080, "Type": "tcp"}]},
    {"Id": "gitea1", "Names": ["/Gitea"], "Image": "gitea/gitea:latest", "State": "running", "Status": "Up 45 minutes", "Ports": [{"IP": "0.0.0.0", "PrivatePort": 3000, "PublicPort": 3000, "Type": "tcp"}]},
    {"Id": "redis1", "Names": ["/Redis"], "Image": "redis:alpine", "State": "exited", "Status": "Exited (0) 1 hour ago", "Ports": []},
]
class Handler(BaseHTTPRequestHandler):
    def reply(self, data, status=200, content_type="application/json"):
        body = data if isinstance(data, bytes) else json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/endpoints":
            return self.reply([{"Id": 1, "Name": "Phone homelab", "Type": 1}])
        if path.endswith("/containers/json"):
            return self.reply(containers)
        if path == "/api/stacks":
            return self.reply([{"Id": 1, "Name": "Monitoring", "Type": 2, "EndpointId": 1, "Status": 1}])
        if path.endswith("/logs"):
            messages = [b"Service ready on port 80\n", b"GET / 200 OK\n"]
            return self.reply(b"".join(struct.pack(">BxxxI", 1, len(line)) + line for line in messages), content_type="application/vnd.docker.raw-stream")
        if "/containers/" in path and path.endswith("/json"):
            return self.reply({"Config": {"Tty": False}})
        self.reply({"message": "Unknown mock route"}, 404)
    def do_POST(self):
        parsed = urlparse(self.path)
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if parsed.path == "/api/auth":
            return self.reply({"jwt": "demo-jwt"})
        if parsed.path.endswith("/images/create"):
            return self.reply(b'{"status":"Pulling image"}\n{"status":"Download complete"}\n', content_type="application/json")
        if parsed.path.endswith("/containers/create"):
            data = json.loads(body)
            cid = "created" + str(len(containers))
            containers.append({"Id": cid, "Names": ["/" + parse_qs(parsed.query)["name"][0]], "Image": data["Image"], "State": "created", "Status": "Created", "Ports": []})
            return self.reply({"Id": cid}, 201)
        for c in containers:
            if "/" + c["Id"] + "/" in parsed.path:
                action = parsed.path.rsplit("/", 1)[-1]
                c["State"] = "exited" if action == "stop" else "running"
                c["Status"] = "Exited (0) just now" if action == "stop" else "Up a few seconds"
        self.reply({})
    def do_DELETE(self):
        cid = urlparse(self.path).path.rsplit("/", 1)[-1]
        containers[:] = [c for c in containers if c["Id"] != cid]
        self.reply({})
    def log_message(self, format, *args):
        print(format % args, flush=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=19000)
    args = parser.parse_args()
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
