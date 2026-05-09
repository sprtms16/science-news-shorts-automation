"""Generate one Korean horror sample per Edge TTS ko-KR voice and copy each
into a Downloads folder so the user can A/B them. Same sentence + same
prosody for fair comparison. Also generates a 'natural' prosody pass so
the user can hear each voice without our slowdowns.
"""
import json
import subprocess
import sys
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

VOICES = [
    "ko-KR-SunHiNeural",
    "ko-KR-InJoonNeural",
    "ko-KR-BongJinNeural",
    "ko-KR-GookMinNeural",
    "ko-KR-HyunsuNeural",
    "ko-KR-HyunsuMultilingualNeural",
    "ko-KR-JiMinNeural",
    "ko-KR-SeoHyeonNeural",
    "ko-KR-YuJinNeural",
]
SENTENCE = "교통사고 후 눈을 뜬 병실은 평소와 다름없이 고요했습니다."
DEST = "c:/Users/sprtm/Downloads/horror_voice_samples"

# Two prosody passes: (a) v6.7 storyteller settings, (b) natural baseline.
PROSODIES = {
    "storyteller_-10Hz_-10pct_-5pct": {"rate": "-10%", "pitch": "-10Hz", "volume": "-5%"},
    "natural_default":                {"rate": "+0%",  "pitch": "+0Hz",  "volume": "+0%"},
}

subprocess.run(["mkdir", "-p", DEST], shell=True, check=False)

for prosody_name, p in PROSODIES.items():
    print(f"\n=== prosody: {prosody_name} ===")
    for voice in VOICES:
        payload = {"text": SENTENCE, "voice": voice, "rate": p["rate"], "pitch": p["pitch"], "volume": p["volume"]}
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            "http://localhost:8000/generate-audio",
            data=body,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            fname = data.get("filename")
            if not fname:
                print(f"  {voice:38} FAIL: no filename in {data}")
                continue
            short = voice.replace("ko-KR-", "").replace("Neural", "")
            local = f"{DEST}/{short}__{prosody_name}.mp3"
            cp = subprocess.run(
                ["docker", "cp", f"shorts-ai-service:/app/output/{fname}", local],
                capture_output=True, text=True,
            )
            if cp.returncode == 0:
                print(f"  {voice:38} OK  {local}  (dur={data.get('duration', 0):.2f}s)")
                # Clean up shared-data file inside container after copy
                subprocess.run(
                    ["docker", "exec", "shorts-ai-service", "rm", f"/app/output/{fname}"],
                    capture_output=True, text=True,
                )
            else:
                print(f"  {voice:38} CP FAIL: {cp.stderr.strip()[:100]}")
        except Exception as e:
            print(f"  {voice:38} ERR: {e}")

print("\n=== final list ===")
subprocess.run(["ls", "-lh", DEST], shell=True)
