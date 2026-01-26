from fastapi import FastAPI, HTTPException, UploadFile, File
from pydantic import BaseModel
import edge_tts
import torch
from diffusers import StableVideoDiffusionPipeline
from diffusers.utils import load_image, export_to_video
import os
import uuid
import uvicorn
from PIL import Image
import io

app = FastAPI(title="Shorts AI Media Service")

# --Config --
OUTPUT_DIR = "/app/output"
os.makedirs(OUTPUT_DIR, exist_ok=True)

# --Load AI Model (SVD) --
print("Loading SVD Model... This might take a while.")
try:
    pipe = StableVideoDiffusionPipeline.from_pretrained(
        "stabilityai/stable-video-diffusion-img2vid-xt",
        torch_dtype=torch.float16,
        variant="fp16"
    )
    if torch.cuda.is_available():
        pipe.enable_model_cpu_offload() # VRAM optimization
        print("✅ SVD Model loaded on CUDA.")
    else:
        print("⚠ WARNING: CUDA not found. SVD will be extremely slow.")
except Exception as e:
    print(f"❌ Failed to load SVD model: {e}")
    pipe = None

# --Models --
class TTSRequest(BaseModel):
    text: str
    voice: str = "ko-KR-SunHiNeural"

# --Endpoints --
@app.get("/")
def health_check():
    gpu_status = "available" if torch.cuda.is_available() else "unavailable"
    return {"status": "ok", "gpu": gpu_status}

@app.post("/generate-audio")
async def generate_audio(request: TTSRequest):
    try:
        filename = f"audio_{uuid.uuid4()}.mp3"
        output_path = os.path.join(OUTPUT_DIR, filename)
        communicate = edge_tts.Communicate(request.text, request.voice)
        await communicate.save(output_path)
        return {"status": "success", "filename": filename}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/generate-video")
async def generate_video(file: UploadFile = File(...)):
    if pipe is None:
        raise HTTPException(status_code=503, detail="SVD Model not loaded")
    
    try:
        # 1. Read Image
        image_data = await file.read()
        image = Image.open(io.BytesIO(image_data)).convert("RGB")
        image = image.resize((1024, 576)) # Resize for SVD optimal resolution
        
        # 2. Generate Video
        print(f"Generating video for {file.filename}...")
        generator = torch.manual_seed(42)
        # Reduce chunk size to 2 for CPU/Memory constraints
        frames = pipe(image, decode_chunk_size=2, generator=generator, num_inference_steps=10).frames[0]
        print("Video generation completed.")
        
        # 3. Save Video
        filename = f"video_{uuid.uuid4()}.mp4"
        output_path = os.path.join(OUTPUT_DIR, filename)
        export_to_video(frames, output_path, fps=7)
        
        return {"status": "success", "filename": filename}
        
    except Exception as e:
        print(f"Video Generation Error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
