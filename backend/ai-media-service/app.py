from fastapi import FastAPI, HTTPException, UploadFile, File
from pydantic import BaseModel
import edge_tts
import os
import uuid
import uvicorn
from mutagen.mp3 import MP3
from transformers import pipeline
import scipy.io.wavfile
import torch
import numpy as np
import json
from kafka import KafkaProducer
import datetime

app = FastAPI(title="Shorts AI Media Service (TTS + MusicGen)")

# --Config --
OUTPUT_DIR = "/app/output"
os.makedirs(OUTPUT_DIR, exist_ok=True)

# --Global Models--
synthesiser = None

class KafkaLogPublisher:
    def __init__(self):
        self.producer = None
        self.bootstrap_servers = os.getenv("KAFKA_SERVERS", "kafka:29092")
        self.topic = "system-logs"
        try:
            self.producer = KafkaProducer(
                bootstrap_servers=self.bootstrap_servers,
                value_serializer=lambda v: json.dumps(v).encode('utf-8')
            )
            print(f"📡 Kafka Logger connected to {self.bootstrap_servers}")
        except Exception as e:
            print(f"⚠️ Kafka Logger failed to connect: {e}")

    def log(self, level, message, details=None, trace_id=None):
        if not self.producer:
            return
        log_entry = {
            "serviceName": "ai-media-service",
            "level": level,
            "message": message,
            "details": details,
            "traceId": trace_id,
            "timestamp": datetime.datetime.utcnow().isoformat() + "Z"
        }
        try:
            self.producer.send(self.topic, log_entry)
        except Exception as e:
            print(f"❌ Failed to send log to Kafka: {e}")

logger = KafkaLogPublisher()

@app.on_event("startup")
async def startup_event():
    global synthesiser
    try:
        device = "cuda" if torch.cuda.is_available() else "cpu"
        print(f"🎵 Loading MusicGen-Small on {device}...")
        # Using pipeline for simplicity
        synthesiser = pipeline("text-to-audio", "facebook/musicgen-small", device=device)
        print("✅ MusicGen loaded successfully.")
        logger.log("INFO", "AI Media Service Started", f"MusicGen loaded on {device}")
    except Exception as e:
        print(f"❌ Failed to load MusicGen: {e}")
        logger.log("ERROR", "AI Media Service Start Failed", f"Error: {str(e)}")

# --Models --
class TTSRequest(BaseModel):
    text: str
    voice: str = "ko-KR-SunHiNeural"
    rate: str = "+30%"  # 1.3x speed by default
    pitch: str = "+0Hz"  # neutral pitch by default; negative values lower the tone
    volume: str = "+0%"  # neutral volume by default; negative values quieter

class BGMRequest(BaseModel):
    prompt: str
    duration: int = 15 # seconds to generate (will be looped if needed)

# Default fallback voice — used when the requested combo produces empty audio.
DEFAULT_TTS_VOICE = "ko-KR-SunHiNeural"
DEFAULT_TTS_RATE = "+30%"
DEFAULT_TTS_PITCH = "+0Hz"
DEFAULT_TTS_VOLUME = "+0%"


async def _synthesize(text: str, voice: str, rate: str, pitch: str, volume: str, output_path: str) -> float:
    """Run edge-tts and return measured duration. Raises if the produced file is empty."""
    communicate = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch, volume=volume)
    await communicate.save(output_path)

    if not os.path.exists(output_path) or os.path.getsize(output_path) == 0:
        raise RuntimeError(f"edge-tts produced empty file (voice={voice}, pitch={pitch}, volume={volume})")

    try:
        duration = MP3(output_path).info.length
    except Exception as e:
        raise RuntimeError(f"edge-tts produced unreadable MP3: {e}")

    if duration <= 0:
        raise RuntimeError(f"edge-tts produced 0-duration audio (voice={voice}, pitch={pitch}, volume={volume})")

    return duration

# --Endpoints --
@app.get("/")
def health_check():
    return {"status": "ok", "service": "TTS + MusicGen", "gpu": torch.cuda.is_available()}

@app.post("/generate-audio")
async def generate_audio(request: TTSRequest):
    filename = f"audio_{uuid.uuid4()}.mp3"
    output_path = os.path.join(OUTPUT_DIR, filename)

    # Multi-tier fallback so we never return a missing/empty audio file.
    # Tier 1: requested voice + rate + pitch + volume
    # Tier 2: requested voice + rate, neutral pitch + neutral volume
    #         (some voices reject extreme prosody combos)
    # Tier 3: default voice + default rate + neutral pitch/volume (known-good last resort)
    attempts = [
        (request.voice, request.rate, request.pitch, request.volume),
        (request.voice, request.rate, DEFAULT_TTS_PITCH, DEFAULT_TTS_VOLUME),
        (DEFAULT_TTS_VOICE, DEFAULT_TTS_RATE, DEFAULT_TTS_PITCH, DEFAULT_TTS_VOLUME),
    ]
    # Deduplicate while keeping order so we don't retry an identical configuration.
    seen = set()
    unique_attempts = []
    for attempt in attempts:
        if attempt not in seen:
            seen.add(attempt)
            unique_attempts.append(attempt)

    last_error = None
    for idx, (voice, rate, pitch, volume) in enumerate(unique_attempts):
        try:
            duration = await _synthesize(request.text, voice, rate, pitch, volume, output_path)
            if idx > 0:
                logger.log(
                    "WARN",
                    f"TTS Fallback used (tier {idx + 1})",
                    f"voice={voice}, rate={rate}, pitch={pitch}, volume={volume}, text={request.text[:30]}",
                )
            logger.log("INFO", f"TTS Generated: {request.text[:30]}...", f"File: {filename}, Dur: {duration:.2f}s, voice={voice}, pitch={pitch}, volume={volume}")
            return {"status": "success", "filename": filename, "duration": duration}
        except Exception as e:
            last_error = e
            logger.log("WARN", f"TTS attempt {idx + 1} failed", f"voice={voice}, pitch={pitch}, volume={volume}, error={e}")
            # Remove partial/empty file before next attempt so the caller never sees stale bytes.
            if os.path.exists(output_path):
                try:
                    os.remove(output_path)
                except OSError:
                    pass

    logger.log("ERROR", "TTS Generation Failed (all fallbacks exhausted)", f"Error: {last_error}")
    raise HTTPException(status_code=500, detail=f"TTS failed after {len(unique_attempts)} attempts: {last_error}")

@app.post("/generate-bgm")
async def generate_bgm(request: BGMRequest):
    global synthesiser
    if synthesiser is None:
        raise HTTPException(status_code=503, detail="MusicGen model not loaded")

    try:
        filename = f"bgm_{uuid.uuid4()}.wav"
        output_path = os.path.join(OUTPUT_DIR, filename)
        
        print(f"🎵 Generating BGM: '{request.prompt}' (max {request.duration}s)...")
        
        # Determine max_new_tokens based on roughly 50 tokens per second for MusicGen (approx)
        # musicgen-small: 32khz, 
        # The pipeline handles 'max_length' or 'max_new_tokens'. 
        # Let's rely on the model default or a fixed reasonable length.
        # Generating too long takes time. Let's aim for ~10-15s and loop it. 
        # MusicGen defaults to generating ~5-10s if not specified.
        # We can pass forward_params={"max_new_tokens": 512} or similar.
        # 256 tokens ~= 5 seconds. So 768 ~= 15 seconds.
        
        music = synthesiser(request.prompt, forward_params={"max_new_tokens": 768})
        
        # Debugging Output Structure
        print(f"DEBUG: MusicGen Output Type: {type(music)}")
        if isinstance(music, list):
            print(f"DEBUG: MusicGen Output[0] Keys: {music[0].keys()}")
            result = music[0]
        else:
            print(f"DEBUG: MusicGen Output Keys: {music.keys()}")
            result = music

        # Extract Audio
        # Pipeline usually returns {'audio': arrayLike, 'sampling_rate': 32000}
        # Audio shape seems to be (1, len) or (1, 1, len) depending on version
        audio_data = result["audio"]
        sampling_rate = result["sampling_rate"]
        
        print(f"DEBUG: Audio Data Type: {type(audio_data)}")
        if isinstance(audio_data, torch.Tensor):
            audio_data = audio_data.cpu().numpy()
        
        print(f"DEBUG: Audio Shape: {audio_data.shape}")
        
        # Squeeze batch dimension if present (1, C, N) or (1, N)
        if len(audio_data.shape) == 3:
            audio_data = audio_data[0] # (C, N)
        
        # Scipy expects (N, C) or (N,)
        # Currently likely (C, N) e.g. (1, 32000)
        if len(audio_data.shape) == 2 and audio_data.shape[0] < audio_data.shape[1]:
             audio_data = audio_data.T # Transpose to (N, C)
             
        scipy.io.wavfile.write(output_path, rate=sampling_rate, data=audio_data)
        
        logger.log("INFO", f"BGM Generated: {request.prompt}", f"File: {filename}")
        return {"status": "success", "filename": filename}
    except Exception as e:
        print(f"Error generating BGM: {e}")
        logger.log("ERROR", "BGM Generation Failed", f"Error: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
