"""Offline first-frame probe for the Earclean hotspot, using Python's standard library."""

import ipaddress
from pathlib import Path
import socket
import struct
import time


# Commands documented by SeanPesce/Spade-Web-Viewer.
START_STREAM = struct.pack("<HH20x", 0x9999, 1)
STOP_STREAM = struct.pack("<HH20x", 0x9999, 2)
GET_REMOTE_KEY = struct.pack("<6sIHH4x", b"SETCMD", 1235, 0x90, 4)
CAMERA_HOST = "192.168.0.123"
VIDEO_PORT = 8031
# The 0x66 frame header matches the Otoscope FrameAssembler documentation.
STREAM_HEADER = struct.Struct("<BxBxI4xHH8x")
JPEG_START = b"\xff\xd8\xff"
JPEG_END = b"\xff\xd9"


def probe(host, port, log, timeout=5):
    frame = bytearray()
    frame_size = 0
    next_chunk = 0
    packets = 0
    jpeg_markers = 0
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as stream:
        stream.connect((host, port))
        stream.send(START_STREAM)
        deadline = time.monotonic() + timeout
        try:
            while (remaining := deadline - time.monotonic()) > 0:
                stream.settimeout(remaining)
                data = stream.recv(65535)
                packets += 1
                jpeg_markers += data.count(JPEG_START)
                if packets <= 4:
                    log(f"UDP {port}: packet {packets}, {len(data)} bytes, "
                        f"header {data[:STREAM_HEADER.size].hex(' ')}, JPEG start {data.find(JPEG_START)}")
                if len(data) < STREAM_HEADER.size:
                    continue
                magic, encoding, size, chunk, length = STREAM_HEADER.unpack_from(data)
                available = len(data) - STREAM_HEADER.size
                if (magic, encoding) != (0x66, 1) or not 0 < length <= available:
                    continue
                payload = data[STREAM_HEADER.size:STREAM_HEADER.size + length]
                if payload.startswith(JPEG_START):
                    frame.clear()
                    frame_size = size if 0 < size <= 4 * 1024 * 1024 else 0
                    next_chunk = chunk
                if not frame_size or size != frame_size or chunk != next_chunk:
                    frame_size = 0
                    continue
                frame.extend(payload)
                next_chunk += 1
                if len(frame) >= frame_size:
                    jpeg = bytes(frame[:frame_size])
                    frame_size = 0
                    if jpeg.endswith(JPEG_END):
                        log(f"UDP {port}: assembled JPEG frame, {len(jpeg)} bytes")
                        return jpeg
        except (TimeoutError, ConnectionResetError, ConnectionRefusedError):
            pass
        finally:
            try:
                stream.send(STOP_STREAM)
            except OSError:
                pass
    log(f"UDP {port}: {packets} packets, {jpeg_markers} JPEG start markers, no assembled frame")
    return None


def run(log):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as control:
        control.connect((CAMERA_HOST, 50000))
        local_ip = control.getsockname()[0]
        log(f"Route to {CAMERA_HOST}: local address {local_ip}")
        subnet = ipaddress.ip_network(f"{CAMERA_HOST}/24", strict=False)
        if ipaddress.ip_address(local_ip) not in subnet:
            log("Join earclean-Find-6c414c before running this test.")
            return None
        control.settimeout(1.5)
        try:
            control.send(GET_REMOTE_KEY)
            reply = control.recv(4096)
            log(f"UDP 50000: received {len(reply)} bytes")
        except (TimeoutError, ConnectionResetError, ConnectionRefusedError):
            log("UDP 50000: no response")
        return probe(CAMERA_HOST, VIDEO_PORT, log)


def main():
    output = Path(__file__).resolve().parent
    with (output / "earclean-result.txt").open("w", encoding="utf-8") as report:
        def log(message):
            print(message, flush=True)
            print(message, file=report, flush=True)

        try:
            jpeg = run(log)
            if jpeg:
                filename = f"earclean-frame-{time.strftime('%Y%m%d-%H%M%S')}.jpg"
                (output / filename).write_bytes(jpeg)
                log(f"PASS: {filename}")
        except OSError as error:
            log(f"Network or file error: {error}")


if __name__ == "__main__":
    main()
