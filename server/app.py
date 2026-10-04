"""Servidor TTS WebSocket RAM-only para NovelReader."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Any

import numpy as np
from fastapi import FastAPI, WebSocket, WebSocketDisconnect

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger("novelreader-tts")


@dataclass(frozen=True)
class TtsRequest:
    session_id: str
    segment_id: int
    text: str
    voice: str
    speed: float
    generation: int


class SupertonicEngine:
    """Carga Supertonic una sola vez y devuelve PCM16 en memoria."""

    def __init__(self) -> None:
        from supertonic import TTS

        model = os.getenv("SUPERTONIC_MODEL", "supertonic-3")
        self.tts = TTS(model=model, auto_download=True)
        self.sample_rate = int(self.tts.sample_rate)
        self.default_voice = os.getenv("SUPERTONIC_VOICE", "M5")
        self.steps = int(os.getenv("SUPERTONIC_STEPS", "8"))
        self._styles: dict[str, Any] = {}
        logger.info("Supertonic cargado: model=%s sample_rate=%s steps=%s", model, self.sample_rate, self.steps)

    def synthesize(self, text: str, voice: str, speed: float) -> tuple[bytes, int, float]:
        voice_name = voice or self.default_voice
        style = self._styles.get(voice_name)
        if style is None:
            style = self.tts.get_voice_style(voice_name=voice_name)
            self._styles[voice_name] = style
        started = time.perf_counter()
        waveform, duration = self.tts.synthesize(
            text,
            voice_style=style,
            lang="es",
            speed=speed,
            total_steps=self.steps,
        )
        array = np.asarray(waveform).squeeze()
        pcm = np.clip(array, -1.0, 1.0)
        pcm = (pcm * 32767.0).astype("<i2", copy=False).tobytes()
        audio_duration = len(pcm) / (self.sample_rate * 2)
        generation_ms = (time.perf_counter() - started) * 1000.0
        return pcm, self.sample_rate, audio_duration or len(pcm) / (self.sample_rate * 2)


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.engine = await asyncio.to_thread(SupertonicEngine)
    app.state.inference_limit = asyncio.Semaphore(int(os.getenv("MAX_TTS_CONCURRENCY", "1")))
    yield


app = FastAPI(title="NovelReader TTS", version="0.1.0", lifespan=lifespan)


def _authorized(token: str | None) -> bool:
    expected = os.getenv("TTS_AUTH_TOKEN", "")
    return not expected or token == expected


async def _send_json(websocket: WebSocket, lock: asyncio.Lock, payload: dict[str, Any]) -> None:
    async with lock:
        await websocket.send_json(payload)


async def _worker(
    websocket: WebSocket,
    queue: asyncio.Queue[TtsRequest],
    state: dict[str, Any],
    send_lock: asyncio.Lock,
) -> None:
    while True:
        request = await queue.get()
        try:
            if request.generation != state["generation"]:
                continue
            async with state["inference_limit"]:
                started = time.perf_counter()
                pcm, sample_rate, audio_duration = await asyncio.to_thread(
                    state["engine"].synthesize,
                    request.text,
                    request.voice,
                    request.speed,
                )
            if request.generation != state["generation"] or request.segment_id in state["cancelled_segments"]:
                continue
            generation_ms = (time.perf_counter() - started) * 1000.0
            rtf = generation_ms / max(audio_duration * 1000.0, 1.0)
            await _send_json(
                websocket,
                send_lock,
                {
                    "type": "audio_start",
                    "sessionId": request.session_id,
                    "segmentId": request.segment_id,
                    "format": "pcm_s16le",
                    "sampleRate": sample_rate,
                    "channels": 1,
                    "generationMs": round(generation_ms, 1),
                },
            )
            for offset in range(0, len(pcm), 64 * 1024):
                async with send_lock:
                    await websocket.send_bytes(pcm[offset : offset + 64 * 1024])
            await _send_json(
                websocket,
                send_lock,
                {
                    "type": "audio_end",
                    "sessionId": request.session_id,
                    "segmentId": request.segment_id,
                    "audioDurationMs": round(audio_duration * 1000.0, 1),
                    "rtf": round(rtf, 4),
                },
            )
            logger.info(
                "[TTS] session=%s segment=%s chars=%s generation_ms=%.1f audio_ms=%.1f RTF=%.4f",
                request.session_id,
                request.segment_id,
                len(request.text),
                generation_ms,
                audio_duration * 1000.0,
                rtf,
            )
        except Exception as exc:  # keep the WebSocket alive for later segments
            logger.exception("Error generando segmento %s", request.segment_id)
            await _send_json(
                websocket,
                send_lock,
                {"type": "error", "segmentId": request.segment_id, "message": str(exc)},
            )
        finally:
            queue.task_done()


@app.get("/health")
async def health() -> dict[str, Any]:
    return {"status": "ok", "sampleRate": app.state.engine.sample_rate}


@app.websocket("/ws/tts")
async def tts_socket(websocket: WebSocket) -> None:
    await websocket.accept()
    queue: asyncio.Queue[TtsRequest] = asyncio.Queue(maxsize=int(os.getenv("MAX_PENDING_SEGMENTS", "3")))
    send_lock = asyncio.Lock()
    state = {
        "generation": 0,
        "cancelled_segments": set(),
        "engine": app.state.engine,
        "inference_limit": app.state.inference_limit,
    }
    worker = asyncio.create_task(_worker(websocket, queue, state, send_lock))
    authenticated = not bool(os.getenv("TTS_AUTH_TOKEN", ""))
    try:
        while True:
            message = await websocket.receive()
            if message.get("type") == "websocket.disconnect":
                break
            raw_text = message.get("text")
            if raw_text is None:
                continue
            payload = json.loads(raw_text)
            message_type = payload.get("type")
            if message_type == "auth":
                authenticated = _authorized(payload.get("token"))
                await _send_json(websocket, send_lock, {"type": "auth_result", "ok": authenticated})
                if not authenticated:
                    await websocket.close(code=1008, reason="invalid token")
                    return
                continue
            if not authenticated:
                await websocket.close(code=1008, reason="authentication required")
                return
            if message_type == "cancel":
                state["generation"] += 1
                state["cancelled_segments"].clear()
                while not queue.empty():
                    queue.get_nowait()
                    queue.task_done()
                await _send_json(websocket, send_lock, {"type": "cancelled", "sessionId": payload.get("sessionId")})
                continue
            if message_type == "cancel_segment":
                state["cancelled_segments"].add(int(payload["segmentId"]))
                continue
            if message_type != "tts_request":
                await _send_json(websocket, send_lock, {"type": "error", "message": f"unknown message type: {message_type}"})
                continue
            text = str(payload.get("text", "")).strip()
            if not text:
                await _send_json(websocket, send_lock, {"type": "error", "message": "text is required"})
                continue
            request = TtsRequest(
                session_id=str(payload.get("sessionId", "")),
                segment_id=int(payload["segmentId"]),
                text=text,
                voice=str(payload.get("voice", "M5")),
                speed=float(payload.get("speed", 1.0)),
                generation=state["generation"],
            )
            try:
                queue.put_nowait(request)
            except asyncio.QueueFull:
                await _send_json(websocket, send_lock, {"type": "backpressure", "segmentId": request.segment_id})
    except (WebSocketDisconnect, json.JSONDecodeError, ValueError):
        logger.info("Sesión WebSocket cerrada")
    finally:
        worker.cancel()
        await asyncio.gather(worker, return_exceptions=True)

