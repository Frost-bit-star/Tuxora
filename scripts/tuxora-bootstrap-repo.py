#!/usr/bin/env python3
"""
Local APT-repository proxy used to build self-contained Tuxora bootstraps.

termux-packages' `generate-bootstraps.sh` only understands a single repository,
but the Tuxora desktop needs packages from two Termux repositories:

  * termux-main  (core + desktop libs)
  * termux-x11   (openbox, termux-x11, thunar, gecko/netsurf, ...)

This server exposes one virtual repository rooted at `/` that merges the
package indexes of both upstream repositories and rewrites every `Filename:`
to a repo-tagged path (`m/...` for main, `x/...` for x11). Downloads of those
paths are streamed/proxied from the correct upstream. That lets the stock,
unmodified `generate-bootstraps.sh` pull from both repos via
`--repository http://127.0.0.1:<port>`.
"""

import argparse
import http.server
import socketserver
import sys
import urllib.request
import urllib.error

MAIN = "https://packages-cf.termux.dev/apt/termux-main"
X11 = "https://packages-cf.termux.dev/apt/termux-x11"

REPOS = {"m": MAIN, "x": X11}

# arch -> merged Packages text
_index_cache = {}


def _download(url):
    req = urllib.request.Request(url, headers={"User-Agent": "tuxora-bootstrap/1.0"})
    with urllib.request.urlopen(req, timeout=180) as resp:
        return resp.read()


def _rewrite(blocks_text, tag):
    """Prefix every Filename: value with the repo tag so the proxy can route it."""
    out = []
    for line in blocks_text.splitlines():
        if line.startswith("Filename:"):
            value = line.split(":", 1)[1].strip().lstrip("./")
            line = "Filename: %s/%s" % (tag, value)
        out.append(line)
    return "\n".join(out)


def merged_index(arch):
    if arch in _index_cache:
        return _index_cache[arch]
    chunks = []
    for tag, base in (("m", MAIN), ("x", X11)):
        url = "%s/dists/%s/main/binary-%s/Packages" % (
            base,
            "stable" if tag == "m" else "x11",
            arch,
        )
        try:
            raw = _download(url).decode("utf-8", "replace")
        except urllib.error.HTTPError:
            continue
        chunks.append(_rewrite(raw, tag))
    # Blank line terminates each stanza; keep stanzas separated.
    text = "\n\n".join(chunks)
    _index_cache[arch] = text
    return text


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[repo] " + fmt % args + "\n")

    def _send(self, code, body, ctype="text/plain"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?", 1)[0]

        if path.startswith("/dists/"):
            parts = path.split("/")
            # /dists/stable/main/binary-<arch>/Packages
            if len(parts) == 6 and parts[4].startswith("binary-") and parts[5] == "Packages":
                arch = parts[4][len("binary-"):]
                if arch == "all":
                    self._send(404, b"", "text/plain")
                    return
                try:
                    body = merged_index(arch).encode("utf-8")
                except Exception as exc:  # pragma: no cover - network errors
                    self._send(502, ("index error: %s" % exc).encode())
                    return
                self._send(200, body)
                return
            self._send(404, b"", "text/plain")
            return

        # /<tag>/pool/... -> upstream deb
        seg = path.lstrip("/").split("/", 1)
        if len(seg) == 2 and seg[0] in REPOS:
            url = "%s/%s" % (REPOS[seg[0]], seg[1])
            try:
                data = _download(url)
            except urllib.error.HTTPError as exc:
                self._send(exc.code, b"", "application/octet-stream")
                return
            except Exception as exc:  # pragma: no cover
                self._send(502, ("proxy error: %s" % exc).encode())
                return
            self._send(200, data, "application/octet-stream")
            return

        self._send(404, b"", "text/plain")


class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8765)
    args = ap.parse_args()
    with ThreadingServer((args.host, args.port), Handler) as httpd:
        sys.stderr.write("[repo] serving merged main+x11 repo on http://%s:%d\n" % (args.host, args.port))
        httpd.serve_forever()


if __name__ == "__main__":
    main()
