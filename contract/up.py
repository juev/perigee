#!/usr/bin/env python3
"""Brings up the contract bookOrbit server and seeds it. Safe to re-run.

Pass --reset to drop the server's data first; contract runs leave progress and
highlights behind.

Generates the corpus, starts docker compose, runs first-time setup, creates the
library over the corpus and waits until every book is scanned.
"""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
BASE = "http://127.0.0.1:3070/api/v1"
SETUP_TOKEN = "contract-test-setup-token"
USER = {"username": "reader", "name": "Reader", "email": "reader@example.invalid", "password": "Contract-Test-1"}
BOOKS = 4


def call(method, path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("content-type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode()
            return resp.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        return e.code, json.loads(text) if text else None


def wait(what, probe, seconds=180):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            if probe():
                return
        except (urllib.error.URLError, ConnectionError):
            pass
        time.sleep(2)
    sys.exit(f"timed out waiting for {what}")


def main():
    books = HERE / ".work" / "books"
    subprocess.run([sys.executable, str(HERE / "make_corpus.py"), str(books)], check=True)
    # The server runs as a user of its own and files uploaded books here: on
    # Linux the mount keeps the host's owner, so the folders are opened to everyone.
    for folder in [books, *(d for d in books.rglob("*") if d.is_dir())]:
        folder.chmod(0o777)
    if "--reset" in sys.argv[1:]:
        subprocess.run(["docker", "compose", "down", "-v"], cwd=HERE, check=True)
    subprocess.run(["docker", "compose", "up", "-d"], cwd=HERE, check=True)
    wait("server health", lambda: call("GET", "/health")[0] == 200)

    if call("GET", "/auth/setup-status")[1]["needsSetup"]:
        status, body = call("POST", "/auth/setup", USER, {"x-setup-token": SETUP_TOKEN})
        if status != 201:
            sys.exit(f"setup failed: {status} {body}")

    status, login = call("POST", "/auth/login", {"username": USER["username"], "password": USER["password"]})
    if status not in (200, 201):
        sys.exit(f"login failed: {status} {login}")
    auth = {"authorization": "Bearer " + login["accessToken"]}

    if not call("GET", "/libraries", headers=auth)[1]:
        status, body = call("POST", "/libraries", {"name": "Contract", "icon": "book", "folders": ["/books"]}, auth)
        if status != 201:
            sys.exit(f"library creation failed: {status} {body}")

    query = {"pagination": {"page": 0, "size": 50}}
    wait("library scan", lambda: call("POST", "/books/query", query, auth)[1]["total"] >= BOOKS)
    print("contract server is ready at", BASE)


if __name__ == "__main__":
    main()
