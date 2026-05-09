# Edge TTS — Korean voices reference

We use [`rany2/edge-tts`](https://github.com/rany2/edge-tts), the
reverse-engineered Edge browser TTS endpoint, via FastAPI in
[`backend/ai-media-service/app.py`](../../backend/ai-media-service/app.py).

## ko-KR voice reality check (2026-05)

[Azure docs](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support)
list 9 standard Korean neural voices + 1 multilingual + 2 Dragon HD. We
sample-tested all of them through the free edge-tts endpoint
([`scripts/generate_voice_samples.py`](../../scripts/generate_voice_samples.py))
and **only 4 actually respond to prosody changes**:

| Voice | Gender | Edge TTS usable? | Notes |
|---|---|---|---|
| `ko-KR-SunHiNeural` | F | ✅ | Default. "Friendly, Positive". Reads as warm-mid storyteller in horror prosody — current horror channel pick. |
| `ko-KR-InJoonNeural` | M | ✅ | Only ko-KR voice with `style="sad"` support. ~253 wpm baseline. |
| `ko-KR-HyunsuNeural` | M | ✅ | Standard male, more youthful/newscaster than warm. |
| `ko-KR-HyunsuMultilingualNeural` | M | ✅ | Most "expressive" male in free tier; cross-lingual. |
| `ko-KR-BongJinNeural` | M | ❌ | Returns stuck 4.25s response regardless of prosody. |
| `ko-KR-GookMinNeural` | M | ❌ | Same as BongJin. |
| `ko-KR-JiMinNeural` | F | ❌ | Same. |
| `ko-KR-SeoHyeonNeural` | F | ❌ | Same. |
| `ko-KR-SoonBokNeural` | F | ❌ | Same. |
| `ko-KR-YuJinNeural` | F | ❌ | Same. |
| `ko-KR-{Hyunsu,SunHi}:DragonHDLatestNeural` | both | ❌ | Paid Azure Speech only. |

So the working set is **{SunHi, InJoon, Hyunsu, HyunsuMultilingual}**.
Don't waste time picking from the others.

## Prosody axes

Edge TTS exposes only `rate`, `pitch`, `volume` (a single `<prosody>` tag
inside a single `<voice>` tag — Microsoft strips anything else
server-side). No mid-sentence style switching, no breathy/whisper
quality, no formant shifting.

- **rate**: `±X%`. Default `+0%`. We use `+30%` for news-pace channels
  and `-10%` for horror.
- **pitch**: `±XHz` or `±Xst` (semitones). Microsoft's own guidance is to
  stay within ±20% of natural baseline; pushing further introduces
  vocoder artifacts that paradoxically *increase* the synthetic feel.
  Korean male F0 baseline ≈ 111-125Hz; female ≈ 200Hz.
- **volume**: `±X%`. Default `+0%`. Use sparingly — perceived intimacy
  comes from FFmpeg post-EQ + mic-distance simulation, not from TTS dB
  attenuation.

## Per-channel current settings

Lookups in [`ChannelBehavior.kt`](../../backend/shorts-core/src/main/kotlin/com/sciencepixel/config/ChannelBehavior.kt)
companion object:

| Channel | voice | rate | pitch | volume | chars/sec |
|---|---|---|---|---|---|
| science / stocks / history | SunHi | +30% | +0Hz | +0% | 8.0 |
| **horror** | **SunHi** | **-10%** | **-10Hz** | **-5%** | **5.5** |

The `chars/sec` hint feeds [`prompt-validator.md`](prompt-validator.md).

## Multi-tier fallback in `app.py`

Each `/generate-audio` POST tries up to 3 prosody bundles before
returning an error:

1. Requested `(voice, rate, pitch, volume)`.
2. Requested voice + rate, but neutral pitch and volume — handles voices
   that reject extreme prosody combos.
3. `SunHi / +30% / +0Hz / +0%` (default voice + neutral) — last-resort
   known-good.

The endpoint also validates the produced MP3 (size > 0, duration > 0)
before returning, so callers never receive empty audio. Logs go to the
`system-logs` Kafka topic.

## Sample-generation script

[`scripts/generate_voice_samples.py`](../../scripts/generate_voice_samples.py)
hits `localhost:8000/generate-audio` with the same Korean horror sentence
across all 9 standard voices × {natural prosody, storyteller prosody}
and `docker cp`s each result to `~/Downloads/horror_voice_samples/`.
Re-run after the user reports a tone problem; let them A/B-test rather
than guessing.
