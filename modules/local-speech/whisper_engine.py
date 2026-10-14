"""GPU inference and word-level speaker grouping; never substitutes CPU silently."""
import dataclasses
import os
from pathlib import Path
import time
import unicodedata

ASR_ID = "SYSTRAN/faster-whisper-large-v3"
ASR_REV = "edaa852ec7e145841d8ffdb056a99866b5f0a478"
DIAR_ID = "pyannote/speaker-diarization-community-1"
DIAR_REV = "3533c8cf8e369892e6b79ff1bf80f7b0286a54ee"


def word_groups(aligned):
    """Keep every recognized word; split at speaker changes and long pauses."""
    output = []
    fallback_words = 0
    for sentence in aligned["segments"]:
        words = sentence.get("words") or [{"word": sentence["text"], "start": sentence["start"],
                                          "end": sentence["end"], "speaker": sentence.get("speaker")}]
        group = None
        for word in words:
            text = word.get("word", "")
            if not text:
                continue
            estimated = "start" not in word or "end" not in word
            if estimated:
                fallback_words += 1
            start = float(word.get("start", group["end"]/1000 if group else sentence["start"]))
            end = max(start, float(word.get("end", start)))
            # Segment-level speaker is only used for a word lacking its own timestamps.
            speaker = word.get("speaker", sentence.get("speaker") if estimated else None)
            if group is None or speaker != group["spk"] or start*1000-group["end"] > 1500:
                if group:
                    output.append(group)
                group = {"start": round(start*1000), "end": round(end*1000), "text": text,
                         "spk": speaker, "timingEstimated": estimated}
            else:
                # Preserve spaces between English words, but concatenate Chinese characters.
                previous = group["text"][-1:] if group["text"] else ""
                separator = " " if previous.isascii() and previous.isalnum() and text[:1].isascii() and text[:1].isalnum() else ""
                group["text"] += separator + text
                group["end"] = max(group["end"], round(end*1000))
                group["timingEstimated"] |= estimated
        if group:
            output.append(group)
    return output, fallback_words


class WhisperEngine:
    def __init__(self, root):
        import torch
        import whisperx
        from huggingface_hub import snapshot_download
        from whisperx.diarize import DiarizationPipeline
        if not torch.cuda.is_available():
            raise RuntimeError("CUDA is unavailable; refusing CPU fallback")
        self.revisions = {"asr": ASR_REV, "speaker": DIAR_REV}
        self.device = "cuda"
        asr_path = snapshot_download(ASR_ID, revision=ASR_REV, local_dir=root / "models" / "whisper-large-v3")
        self.asr = whisperx.load_model(asr_path, "cuda", compute_type="float16", language="zh", vad_method="silero")
        self.align, self.align_metadata = whisperx.load_align_model(language_code="zh", device="cuda", model_dir=str(root / "models" / "alignment"))
        token_path = Path(os.getenv("HF_TOKEN_FILE", str(Path.home() / ".cache/huggingface/token")))
        if not token_path.is_file():
            raise RuntimeError("Community-1 authorization required: save a read token in ~/.cache/huggingface/token")
        token = token_path.read_text().strip()
        diar_path = snapshot_download(DIAR_ID, revision=DIAR_REV, token=token, local_dir=root / "models" / "community-1")
        self.diarizer = DiarizationPipeline(model_name=str(diar_path), token=token, device="cuda")
        self.batch_size = int(os.getenv("WHISPER_BATCH_SIZE", "1"))

    def generate(self, input, hotword="", preset_spk_num=None):
        import numpy as np
        import soundfile as sf
        import torch
        import whisperx
        from whisperx.diarize import assign_word_speakers
        audio, rate = sf.read(input, dtype="float32")
        if rate != 16000 or audio.ndim != 1:
            raise ValueError("Expected 16 kHz mono audio")
        torch.cuda.synchronize()
        torch.cuda.reset_peak_memory_stats()
        timings = {}
        started = time.perf_counter()
        original_options = self.asr.options
        try:
            if hotword:
                if hasattr(original_options, "_replace"):
                    self.asr.options = original_options._replace(hotwords=hotword)
                else:
                    self.asr.options = dataclasses.replace(original_options, hotwords=hotword)
            result = self.asr.transcribe(audio, batch_size=self.batch_size, language="zh")
        finally:
            self.asr.options = original_options
        torch.cuda.synchronize()
        timings["asrSeconds"] = round(time.perf_counter()-started, 3)
        if not result["segments"]:
            return {"text": "", "sentence_info": [], "metrics": {"stageTimings": timings, "gpuName": torch.cuda.get_device_name()}}
        started = time.perf_counter()
        aligned = whisperx.align(result["segments"], self.align, self.align_metadata, audio, "cuda", return_char_alignments=False)
        torch.cuda.synchronize()
        timings["alignmentSeconds"] = round(time.perf_counter()-started, 3)
        started = time.perf_counter()
        diarization = self.diarizer(audio, num_speakers=preset_spk_num)
        torch.cuda.synchronize()
        timings["diarizationSeconds"] = round(time.perf_counter()-started, 3)
        annotated = assign_word_speakers(diarization, aligned, fill_nearest=False)
        groups, fallback_words = word_groups(annotated)
        return {"text": "".join(s["text"] for s in result["segments"]), "sentence_info": groups,
                "metrics": {"stageTimings": timings, "gpuName": torch.cuda.get_device_name(),
                            "gpuPeakAllocatedMiB": round(torch.cuda.max_memory_allocated()/1024**2, 1),
                            "gpuPeakReservedMiB": round(torch.cuda.max_memory_reserved()/1024**2, 1),
                            "unalignedWordCount": fallback_words, "batchSize": self.batch_size,
                            "computeType": "float16", "language": "zh",
                            "diarizationTurns": [{"startMs": round(r.start*1000), "endMs": round(r.end*1000), "speakerId": r.speaker}
                                                 for r in diarization.itertuples()]}}
