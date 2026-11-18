#!/usr/bin/env python3
"""Expose the local Rex Unix socket on 127.0.0.1 so an Android emulator can reach it at http://10.0.2.2:PORT.

Rex identifies Unix-socket callers by their peer UID, so connections through this bridge act as the
user who runs it. It binds to loopback only and is meant for development, not for remote access.
"""
import argparse
import asyncio
import os
from urllib.parse import unquote, urlparse


def default_socket():
    server = os.environ.get("REX_SERVER", "")
    if server.startswith("unix://"):
        return unquote(urlparse(server).path)
    return os.path.expanduser("~/Library/Application Support/rex/server.sock")


async def pipe(reader, writer):
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    finally:
        writer.close()


async def main(port, socket_path):
    async def handle(client_reader, client_writer):
        server_reader, server_writer = await asyncio.open_unix_connection(socket_path)
        await asyncio.gather(pipe(client_reader, server_writer), pipe(server_reader, client_writer), return_exceptions=True)

    server = await asyncio.start_server(handle, "127.0.0.1", port)
    print(f"Rex bridge: http://127.0.0.1:{port} -> {socket_path} (emulator: http://10.0.2.2:{port})", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=7777)
    parser.add_argument("--socket", default=default_socket())
    args = parser.parse_args()
    asyncio.run(main(args.port, args.socket))
