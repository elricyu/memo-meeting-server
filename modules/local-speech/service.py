"""Standalone GPU WhisperX with Community-1 speaker diarization."""
import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timezone
import hashlib
import importlib.metadata
import json
import logging
import os
from pathlib import Path
import resource
import re
import sqlite3
import time
import uuid
import hmac
import shutil
import unicodedata

ROOT = Path(__file__).resolve().parent
os.environ.setdefault("TORCH_HOME", str(ROOT / "models" / "torch"))
os.environ.setdefault("PYANNOTE_METRICS_ENABLED", "0")
os.environ.setdefault("HF_HOME", str(ROOT / "models" / "huggingface"))
os.environ.setdefault("OMP_NUM_THREADS", "4")
os.environ.setdefault("MKL_NUM_THREADS", "4")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import soundfile as sf
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import PlainTextResponse

DATA = ROOT / "runtime" / "jobs"
DATA.mkdir(parents=True, exist_ok=True)
DB = DATA / "jobs.sqlite3"
THREADS = int(os.getenv("ASR_CPU_THREADS", "4"))
MAX_BYTES = int(os.getenv("ASR_MAX_UPLOAD_BYTES", str(128 * 1024 * 1024)))
MAX_SECONDS = int(os.getenv("ASR_MAX_AUDIO_SECONDS", "3600"))
MAX_PENDING = 8
MODELS = {"asr": "SYSTRAN/faster-whisper-large-v3", "alignment": "Chinese wav2vec2",
          "speaker": "pyannote/speaker-diarization-community-1", "vad": "silero"}

log = logging.getLogger("funasr-service")
engine = None
load_error = None
queue = asyncio.Queue(maxsize=MAX_PENDING)


def now():
    return datetime.now(timezone.utc).isoformat()


def db():
    connection = sqlite3.connect(DB, timeout=30)
    connection.row_factory = sqlite3.Row
    return connection


def initialize():
    with db() as conn:
        conn.execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, filename TEXT, status TEXT, created_at TEXT, updated_at TEXT, error TEXT, speaker_count INTEGER, hotwords TEXT)")
        conn.execute("UPDATE jobs SET status='failed', error='Service restarted before completion', updated_at=? WHERE status IN ('queued','running')", (now(),))
        known = {row[0] for row in conn.execute("SELECT id FROM jobs")}
    # A crash during upload can leave a UUID directory without a durable job.
    for directory in DATA.iterdir():
        if not directory.is_dir() or directory.name in known:
            continue
        try:
            uuid.UUID(directory.name)
        except ValueError:
            continue
        shutil.rmtree(directory)


def update(job_id, status, error=None):
    with db() as conn:
        conn.execute("UPDATE jobs SET status=?, error=?, updated_at=? WHERE id=?", (status, error, now(), job_id))


def read_job(job_id):
    with db() as conn:
        row = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
    if row is None:
        raise HTTPException(404, "Unknown job")
    return dict(row)


def load_engine():
    from whisper_engine import WhisperEngine
    return WhisperEngine(ROOT)


def normalize_segments(raw, duration_ms):
    segments = []
    for item in raw.get("sentence_info", []):
        text = clean_text(str(item.get("text", item.get("sentence", ""))))
        if not text or not any(unicodedata.category(c)[0] in "LN" for c in text):
            continue
        original = item.get("spk")
        speaker = None
        if original is not None:
            key = str(original)
            speaker = key
        start = max(0, min(duration_ms, int(item["start"])))
        end = max(start, min(duration_ms, int(item["end"])))
        segments.append({"id": len(segments), "startMs": start, "endMs": end,
                         "text": text, "speakerId": speaker, "timingEstimated": bool(item.get("timingEstimated", False))})
    return segments


def clean_text(text):
    # The VAD path returns token-separated Chinese; leave English word spacing intact.
    return re.sub(r"(?<=[\u3400-\u9fff])\s+(?=[\u3400-\u9fff])", "", text.strip())


def read_result(job_id):
    result = json.loads((DATA / job_id / "result.json").read_text())
    for segment in result["segments"]:
        segment["text"] = clean_text(segment["text"])
    result["textPostprocessing"] = "Remove token separator spaces between Chinese characters; preserve English spacing"
    return result


def render_transcript(result):
    return "\n".join(f"[{s['startMs']/1000:.2f}–{s['endMs']/1000:.2f}] {s['speakerId']}: {s['text']}"
                     for s in result["segments"]) + "\n"


def infer(job):
    path = DATA / job["id"] / "audio.wav"
    info = sf.info(str(path))
    started = time.perf_counter()
    options = dict(hotword=job["hotwords"] or "")
    if job["speaker_count"] is not None:
        options["preset_spk_num"] = job["speaker_count"]
    raw = engine.generate(input=str(path), **options)
    elapsed = time.perf_counter() - started
    duration_ms = round(info.duration * 1000)
    segments = normalize_segments(raw, duration_ms)
    text = str(raw.get("text", "")).strip()
    if text and not segments:
        raise RuntimeError("Model produced text without complete speaker segments")
    sha = hashlib.sha256(path.read_bytes()).hexdigest()
    result = {"jobId": job["id"], "filename": job["filename"], "text": text,
              "segments": segments, "speakers": sorted({s["speakerId"] for s in segments if s["speakerId"] is not None}),
              "durationMs": duration_ms, "processingSeconds": round(elapsed, 3),
              "realTimeFactor": round(elapsed / max(info.duration, 0.001), 4),
              "processPeakRssMiB": round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024, 1),
              "audioSha256": sha, "device": "cuda", "cpuThreads": THREADS,
              "models": MODELS, "modelRevisions": engine.revisions,
              "whisperxVersion": importlib.metadata.version("whisperx"),
              "speakerMode": "word_alignment", "speechDetected": bool(segments),
              "torchVersion": importlib.metadata.version("torch"), "createdAt": now(),
              "speakerCountHint": job["speaker_count"],
              "warnings": ["Speaker IDs are local to this recording; names are not recognized.",
                           "Unaligned words use segment timing; unknown speakers remain unassigned. Overlapping speech is not separated into independent audio tracks.",
                           "RSS is the process lifetime high-water mark, not per-job incremental memory."]}
    result.update(raw.get("metrics", {}))
    result["unassignedSegmentCount"] = sum(s["speakerId"] is None for s in segments)
    target = DATA / job["id"] / "result.json"
    temporary = target.with_suffix(".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    temporary.replace(target)
    (target.parent / "transcript.txt").write_text(render_transcript(result))


async def worker():
    global engine, load_error
    try:
        engine = await asyncio.to_thread(load_engine)
        log.info("GPU models loaded; accepting jobs")
    except Exception as exc:
        load_error = str(exc)
        log.exception("Model initialization failed")
        return
    while True:
        job_id = await queue.get()
        try:
            update(job_id, "running")
            await asyncio.to_thread(infer, read_job(job_id))
            update(job_id, "completed")
        except Exception as exc:
            log.exception("Job %s failed", job_id)
            update(job_id, "failed", str(exc))
        finally:
            # Only copied uploads are removed. Source MemoMeet files are never opened for writing.
            (DATA / job_id / "audio.wav").unlink(missing_ok=True)
            # Release idle PyTorch cache so the server's existing GPU apps retain headroom.
            def release_cache():
                import gc
                import torch
                gc.collect()
                torch.cuda.empty_cache()
            await asyncio.to_thread(release_cache)
            queue.task_done()


@asynccontextmanager
async def lifespan(app):
    initialize()
    task = asyncio.create_task(worker())
    yield
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


app = FastAPI(title="GPU WhisperX + Community-1", version="1.1.0", lifespan=lifespan)

@app.middleware("http")
async def authenticate(request, call_next):
    token = os.environ.get("LOCAL_SPEECH_TOKEN", "")
    if token and not hmac.compare_digest(request.headers.get("authorization", ""), "Bearer " + token):
        from fastapi.responses import JSONResponse
        return JSONResponse({"detail": "Authentication required"}, status_code=401)
    return await call_next(request)


@app.get("/health")
def health():
    return {"status": "failed" if load_error else "ready" if engine else "loading",
            "device": "cuda", "cpuThreads": THREADS, "models": MODELS,
            "queueSize": queue.qsize(), "error": load_error}


@app.post("/v1/jobs", status_code=202)
async def submit(file: UploadFile = File(...), speaker_count: int | None = Form(None), hotwords: str = Form(""), request_id: str | None = Form(None)):
    if engine is None:
        raise HTTPException(503, "Models failed to load" if load_error else "Models are loading")
    if speaker_count is not None and not 1 <= speaker_count <= 20:
        raise HTTPException(422, "speaker_count must be between 1 and 20")
    if len(hotwords) > 2000:
        raise HTTPException(422, "hotwords is limited to 2000 characters")
    try:
        job_id = str(uuid.UUID(request_id)) if request_id else str(uuid.uuid4())
    except ValueError:
        raise HTTPException(422, "request_id must be a UUID")
    with db() as conn:
        existing = conn.execute("SELECT status FROM jobs WHERE id=?", (job_id,)).fetchone()
    if existing:
        await file.close()
        return {"jobId": job_id, "status": existing[0], "statusUrl": f"/v1/jobs/{job_id}"}
    directory = DATA / job_id
    try:
        directory.mkdir()
    except FileExistsError:
        raise HTTPException(409, "An upload with this request_id is in progress")
    path = directory / "audio.wav"
    size = 0
    try:
        with path.open("wb") as output:
            while chunk := await file.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_BYTES:
                    raise HTTPException(413, "Audio exceeds upload limit")
                output.write(chunk)
        try:
            info = sf.info(str(path))
        except Exception:
            raise HTTPException(422, "Cannot decode audio; upload WAV, FLAC or another libsndfile-supported file")
        if info.format != "WAV" or info.samplerate != 16000 or info.channels != 1 or info.subtype != "PCM_16":
            raise HTTPException(422, "This service accepts 16 kHz mono PCM16 WAV; convert input before uploading")
        if not 0 < info.duration <= MAX_SECONDS:
            raise HTTPException(422, f"Audio duration must be between 0 and {MAX_SECONDS} seconds")
        if queue.full():
            raise HTTPException(429, "Job queue is full")
        with db() as conn:
            conn.execute("INSERT INTO jobs VALUES(?,?,?,?,?,?,?,?)",
                         (job_id, Path(file.filename or "audio.wav").name, "queued", now(), now(), None, speaker_count, hotwords))
        queue.put_nowait(job_id)
    except Exception:
        path.unlink(missing_ok=True)
        directory.rmdir()
        raise
    finally:
        await file.close()
    return {"jobId": job_id, "status": "queued", "statusUrl": f"/v1/jobs/{job_id}"}


@app.get("/v1/jobs/{job_id}")
def status(job_id: str):
    job = read_job(job_id)
    if job["status"] == "completed":
        job["result"] = read_result(job_id)
    return job


@app.get("/v1/jobs/{job_id}/transcript")
def transcript(job_id: str):
    if read_job(job_id)["status"] != "completed":
        raise HTTPException(409, "Transcript is not ready")
    return PlainTextResponse(render_transcript(read_result(job_id)))
