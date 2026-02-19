import os
import subprocess
import re
import sys

# Mapping categories from prompt.md to folder names
CATEGORIES = {
    "Science Pixel": "science",
    "Mystery Pixel": "horror",
    "Value Pixel": "stocks",
    "Memory Pixel": "history",
    "Default": "general"
}

PROMPT_FILE = "prompt.md"
BASE_DIR = "output/bgm"

def parse_prompt():
    with open(PROMPT_FILE, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    current_category = None
    tracks = {}

    for line in lines:
        line = line.strip()
        
        # Detect Category
        match = re.search(r'Category:\s*(.+?)\s*\(', line)
        if match:
            cat_name = match.group(1).strip()
            if cat_name in CATEGORIES:
                current_category = CATEGORIES[cat_name]
                tracks[current_category] = []
                continue
            elif "Default" in line:
                 current_category = "general"
                 tracks[current_category] = []
                 continue

        # Detect Track
        if current_category and line.startswith('- "'):
            # Extract track and artist: - "Title" - Artist
            # or - "Title"
            parts = line.split('"')
            if len(parts) >= 3:
                title = parts[1]
                artist = parts[2].replace('-', '', 1).strip()
                tracks[current_category].append(f"{title} {artist}")

    return tracks

def download_tracks(tracks):
    for category, track_list in tracks.items():
        folder = os.path.join(BASE_DIR, category)
        os.makedirs(folder, exist_ok=True)
        print(f"📂 Processing Category: {category} -> {folder}")
        
        for query in track_list:
            print(f"  ⬇️ Downloading: {query}")
            # sanitize filename
            safe_name = "".join([c for c in query if c.isalnum() or c in (' ', '-', '_')]).strip()
            output_template = os.path.join(folder, f"{safe_name}.%(ext)s")
            
            cmd = [
                sys.executable, "-m", "yt_dlp",
                f"ytsearch1:{query} audio library", # Add 'audio library' to search to favor official source
                "-x", "--audio-format", "mp3",
                "-o", output_template,
                "--no-playlist"
            ]
            
            try:
                subprocess.run(cmd, check=True)
                print(f"  ✅ Downloaded: {query}")
            except subprocess.CalledProcessError as e:
                print(f"  ❌ Failed: {query} ({e})")

if __name__ == "__main__":
    print("🚀 Starting BGM Download based on prompt.md...")
    parsed_tracks = parse_prompt()
    download_tracks(parsed_tracks)
    print("✨ All downloads processing complete.")
