from fastapi import FastAPI, HTTPException, UploadFile, File
from pydantic import BaseModel
import edge_tts
import os
import uuid
import uvicorn
from mutagen.mp3 import MP3

app = FastAPI(title="Shorts AI Media Service (TTS Only)")

# --Config --
OUTPUT_DIR = "/app/output"
os.makedirs(OUTPUT_DIR, exist_ok=True)

# --Models --
class TTSRequest(BaseModel):
    text: str
    voice: str = "ko-KR-SunHiNeural"
    rate: str = "+30%"  # 1.3x speed by default

# --Endpoints --
@app.get("/")
def health_check():
    return {"status": "ok", "service": "TTS Only"}

@app.post("/generate-audio")
async def generate_audio(request: TTSRequest):
    try:
        filename = f"audio_{uuid.uuid4()}.mp3"
        output_path = os.path.join(OUTPUT_DIR, filename)
        
        # Add rate parameter for speed control
        communicate = edge_tts.Communicate(request.text, request.voice, rate=request.rate)
        await communicate.save(output_path)
        
        # Get actual duration using mutagen
        try:
            audio = MP3(output_path)
            duration = audio.info.length
        except Exception:
            duration = 5.0  # fallback
        
        return {"status": "success", "filename": filename, "duration": duration} 
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
