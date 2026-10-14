#!/usr/bin/env python3
"""Upload local WAVs and save actual service responses; no MemoMeet API access."""
import argparse
import json
from pathlib import Path
import time
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("audio", nargs="+", type=Path)
parser.add_argument("--url", default="http://127.0.0.1:18092")
parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "results")
parser.add_argument("--speaker-count", type=int)
parser.add_argument("--hotwords", default="")
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
for audio in args.audio:
    boundary = uuid.uuid4().hex
    body = bytearray()
    for name, value in (("speaker_count", args.speaker_count), ("hotwords", args.hotwords)):
        if value is not None:
            body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{audio.name}"\r\nContent-Type: audio/wav\r\n\r\n'.encode())
    body.extend(audio.read_bytes())
    body.extend(f'\r\n--{boundary}--\r\n'.encode())
    request = urllib.request.Request(args.url + "/v1/jobs", data=bytes(body), headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(request, timeout=120) as response:
        job = json.load(response)
    print(audio.name, job["jobId"], "submitted", flush=True)
    deadline = time.monotonic() + 7200
    while time.monotonic() < deadline:
        with urllib.request.urlopen(args.url + job["statusUrl"], timeout=30) as response:
            status = json.load(response)
        if status["status"] == "failed":
            raise RuntimeError(status["error"])
        if status["status"] == "completed":
            result = status["result"]
            (args.output / (audio.stem + ".json")).write_text(json.dumps(result, ensure_ascii=False, indent=2))
            with urllib.request.urlopen(args.url + job["statusUrl"] + "/transcript", timeout=30) as response:
                (args.output / (audio.stem + ".txt")).write_bytes(response.read())
            print(audio.name, "completed:", result["processingSeconds"], "seconds;", len(result["speakers"]), "speakers", flush=True)
            break
        time.sleep(2)
    else:
        raise TimeoutError("Job did not finish within two hours; inspect service status before resubmitting")
