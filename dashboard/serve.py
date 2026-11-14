#!/usr/bin/env python3
"""
serve.py — Static Dashboard Server with Dynamic Environment Variable Injection.

Usage:
    export PACT_BACKEND_URL=https://quickness-shining-latticed.ngrok-free.dev
    python3 dashboard/serve.py [PORT]

Replaces 'python3 -m http.server 8080 --directory dashboard'.
Automatically injects PACT_BACKEND_URL / BACKEND_URL into env.js on the fly.
"""
from __future__ import annotations

import os
import sys
from http.server import HTTPServer, SimpleHTTPRequestHandler
from pathlib import Path

DASHBOARD_DIR = Path(__file__).resolve().parent
ENV_JS_PATH = DASHBOARD_DIR / "env.js"

class DynamicEnvHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(DASHBOARD_DIR), **kwargs)

    def do_GET(self):
        # Dynamically inject current process environment variable on GET /env.js
        if self.path.split("?")[0] in ("/env.js", "/env.js/"):
            backend_url = (
                os.environ.get("PACT_BACKEND_URL")
                or os.environ.get("BACKEND_URL")
                or os.environ.get("BACKEND_BASE_URL")
                or ""
            )
            # If empty in process env, fall back to file content on disk
            if not backend_url and ENV_JS_PATH.exists():
                return super().do_GET()

            content = (
                f"/** Dynamically generated from process.env **/\n"
                f"window.__ENV__ = Object.assign(\n"
                f"  {{\n"
                f'    PACT_BACKEND_URL: "{backend_url}",\n'
                f"  }},\n"
                f"  window.__ENV__ || {{}}\n"
                f");\n"
            ).encode("utf-8")

            self.send_response(200)
            self.send_header("Content-Type", "application/javascript; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(content)
            return

        super().do_GET()


def main():
    port = 8080
    if len(sys.argv) > 1 and sys.argv[1].isdigit():
        port = int(sys.argv[1])

    backend_url = (
        os.environ.get("PACT_BACKEND_URL")
        or os.environ.get("BACKEND_URL")
        or os.environ.get("BACKEND_BASE_URL")
        or ""
    )

    # Sync to env.js on disk as well so plain http.server also works
    if backend_url:
        try:
            with open(ENV_JS_PATH, "w", encoding="utf-8") as f:
                f.write(
                    f"/** Auto-synced from environment variable **/\n"
                    f"window.__ENV__ = Object.assign(\n"
                    f"  {{\n"
                    f'    PACT_BACKEND_URL: "{backend_url}",\n'
                    f"  }},\n"
                    f"  window.__ENV__ || {{}}\n"
                    f");\n"
                )
        except Exception as e:
            print(f"[WARN] Could not update {ENV_JS_PATH}: {e}")

    server = HTTPServer(("0.0.0.0", port), DynamicEnvHandler)
    print("=" * 65)
    print(f"  PACT DASHBOARD LOCAL SERVER")
    print(f"  Local Address : http://localhost:{port}")
    print(f"  Backend URL   : {backend_url if backend_url else '(using env.js on disk or relative /api)'}")
    print("=" * 65)
    print("Serving files... Press Ctrl+C to stop.\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nServer stopped.")


if __name__ == "__main__":
    main()
