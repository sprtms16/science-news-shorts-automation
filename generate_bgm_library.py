import requests
import re
import os
import subprocess
import time

# Configuration
API_URL = "http://localhost:8000/generate-bgm"
PROMPT_FILE = "shared-data/prompt.md"
BASE_DIR = "shared-data/bgm"
CONTAINER_NAME = "shorts-ai-service"
CONTAINER_OUTPUT_DIR = "/app/output"

CATEGORIES = {
    "Science Pixel": "science",
    "Mystery Pixel": "horror",
    "Value Pixel": "stocks",
    "Memory Pixel": "history",
    "Default": "general"
}

def parse_keywords():
    with open(PROMPT_FILE, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    moods = {}
    current_category = None
    
    for line in lines:
        line = line.strip()
        # Detect Category
        match = re.search(r'Category:\s*(.+?)\s*\(', line)
        if match:
            cat_name = match.group(1).strip()
            if cat_name in CATEGORIES:
                current_category = CATEGORIES[cat_name]
                moods[current_category] = ""
            elif "Default" in line:
                current_category = "general"
                moods[current_category] = ""
            continue
            
        # Detect Keywords (and combine with Mood/Genre for a rich prompt)
        if current_category:
            if line.startswith("- **Keywords:**"):
                kw = line.split(":", 1)[1].strip()
                moods[current_category] += kw + ", "
            elif line.startswith("- **Mood Filter:**"):
                mf = line.split(":", 1)[1].strip()
                moods[current_category] += mf + ", "
            elif line.startswith("- **Genre Filter:**"):
                gf = line.split(":", 1)[1].strip()
                moods[current_category] += gf + ", "

    return moods

def generate_and_save():
    moods = parse_keywords()
    
    for category, prompt_text in moods.items():
        # Clean prompt
        final_prompt = prompt_text.strip(", ")
        print(f"\n🎹 Category: {category}")
        print(f"   Prompt: {final_prompt}")
        
        target_dir = os.path.join(BASE_DIR, category)
        os.makedirs(target_dir, exist_ok=True)
        
        # Generate 3 tracks per category
        for i in range(1, 4):
            print(f"   Shape {i}/3: Requesting AI Generation...", end="", flush=True)
            try:
                # 1. Call API
                resp = requests.post(API_URL, json={"prompt": final_prompt, "duration": 15})
                if resp.status_code == 200:
                    data = resp.json()
                    filename = data["filename"] # e.g. bgm_uuid.wav
                    print(f" Done. (File: {filename})")
                    
                    # 2. Copy from Container to Host
                    local_filename = f"{category}_ai_track_{i}.wav"
                    local_path = os.path.join(target_dir, local_filename)
                    
                    # 'docker cp container:path local_path'
                    cp_cmd = [
                        "docker", "cp",
                        f"{CONTAINER_NAME}:{CONTAINER_OUTPUT_DIR}/{filename}",
                        local_path
                    ]
                    subprocess.run(cp_cmd, check=True, stdout=subprocess.DEVNULL)
                    print(f"   ⬇️ Saved to: {local_path}")
                    
                else:
                    print(f" ❌ API Error: {resp.text}")
            except Exception as e:
                print(f" ❌ Failed: {e}")
            
            time.sleep(1)

if __name__ == "__main__":
    print("🚀 Starting AI BGM Generation & Extraction...")
    generate_and_save()
    print("✨ Complete!")
