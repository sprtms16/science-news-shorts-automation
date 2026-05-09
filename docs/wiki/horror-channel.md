# Horror channel — 미스터리 픽셀

Channel ID: `horror`. Container: `shorts-horror-controller`.
Sources: Reddit (NoSleep, TwoSentenceHorror, Glitch_in_the_Matrix,
LetsNotMeet) + JP horror RSS (Kowabana, Japanese Horror) + Western
creepypasta feeds (Creepypasta.com, Scare Street, Nightmare Magazine).

Schedule: hourly batch at `:20, :50` (KST), upload at `:20, :50`.

## TTS — settled at v6.9 (user-approved 2026-05-09)

Three-iteration journey landed here after explicit user A/B testing:

| Version | Voice | Pitch | Rate | Volume | Outcome |
|---|---|---|---|---|---|
| Original | SunHi | +0Hz | +30% | +0% | Same as other channels — no horror feel |
| v6.5 (threat-tone) | InJoonNeural | -50Hz | -5% | -15% | "Voice itself is scary" but rendered as 80s videos and felt synthetic |
| v6.6 | InJoonNeural | -50Hz | -5% | -15% | Same as v6.5 with prompt char limit tightened to 22-28 → 64s |
| v6.7 | HyunsuMultilingualNeural | -10Hz | -10% | -5% | User: "톤이 안 맞는 것 같다" |
| v6.8 (skipped) | HyunsuMultilingual | -5Hz | -12% | -5% | Pulled pitch back; superseded by user picking SunHi instead |
| **v6.9 (current)** | **SunHiNeural (female)** | **-10Hz** | **-10%** | **-5%** | User: "괜찮은 것 같아 적절한 것 같아" |

Why female SunHi won:

- Agent-deep analysis (see [edge-tts-korean-voices.md](edge-tts-korean-voices.md))
  found the four edge-tts-usable male voices all share a similar synthetic
  newscaster timbre. The Korean female-narrator niche (디바제시카 "앵커처럼
  차분하게", 유민지 호신마마, 별 헤는 괴담 ASMR) sounds more "intimate
  ghost-story reader" in TTS form than the male radio voices.
- Microsoft's pitch-shift guidance is ±20%; pushing pitch further
  (v6.5's -50Hz) introduces vocoder artifacts that *cause* the synthetic
  feel users were trying to escape. Pulling pitch back toward natural
  + adding warmth via FFmpeg post-EQ outperforms aggressive de-pitching.

## FFmpeg post-EQ Recipe A (warm close-mic radio narrator)

Applied AFTER `atempo=1.10` in `ProductionService.tryEditSceneWithCodec`
when `ChannelBehavior.ttsAudioPostFilterFor("horror")` is non-empty:

```
highpass=f=80,
equalizer=f=180:t=h:width=120:g=2.5,         # +2.5 dB chest warmth
equalizer=f=2800:t=h:width=1500:g=-1.5,      # tame harsh presence
equalizer=f=6500:t=h:width=2000:g=-2,        # tame sibilance
acompressor=threshold=-18dB:ratio=3:attack=15:release=120:makeup=3,
alimiter=limit=0.95
```

Sources: [Music Guy Mixing — Voice EQ](https://www.musicguymixing.com/voice-eq/),
[FFmpeg filter docs](https://ffmpeg.org/ffmpeg-filters.html). The recipe
fits female SunHi well too — the 180Hz boost lands in her lower chest
register, the 2.8/6.5kHz cuts tame the same TTS over-presence/sibilance.

## BGM mix — horror-only override (0.45)

Other channels use `volume=0.20` (~-14 dB attenuation, ambient bed under a
voice-led explainer). Horror **needs the BGM clearly audible because the
BGM is what carries the dread** — the calm storyteller voice is not
supposed to do that work. We measured the v6.9 first-cut and got
narration -8.5 dB vs BGM -29.5 dB → 21 dB diff, well below the
12-15 dB voice-over standard. Result: `ChannelBehavior.bgmMixVolumeFor("horror") = 0.45`
(~-7 dB → diff ~14 dB), commit `8874e75`. See
[log.md 2026-05-09 BGM tune](log.md).

BGM source priority in `AudioService.generateBgm`:

1. `shared-data/bgm/suspense/*.mp3` — local pre-curated horror BGM tracks
   (currently: Apprehensive at Best, Body of Water, Don't Look Inside,
   Spooked, Underworld). Random pick per scene.
2. AI generation via MusicGen-Small (`shorts-ai-service`) using the
   prompt `"$mood style cinematic background music, high quality"`.

In practice almost every horror video uses path (1) — the local files
are mood-appropriate and royalty-cleared.

## Prompt v6.9 — 4-phase Hook→Build→Reveal→Linger

`docs/wiki/prompt-validator.md` has the validator side; the prompt itself
is seeded by `DataInitializer.seedSystemPrompts()` for `channelId=horror,
promptKey=script_prompt_v6`. Architecture is a **2-7-3-2 mapping** of
the 14-scene window:

- **Hook (scenes 1-2)** — familiar everyday setting + ONE wrong detail.
  No greetings, no "오늘은 무서운 이야기를".
- **Build (scenes 3-9, seven scenes)** — escalating signals. King's
  rule of three: introduce, return twice with new context, peak in Reveal.
- **Reveal (scenes 10-12, three scenes)** — cinematic payoff. Show what
  happens; **withhold WHY**. Two vivid sensory details per scene + the
  character's physical reaction (`손끝이 차가워졌습니다`, NOT
  `무서웠습니다`).
- **Linger (scenes 13-14, two scenes)** — no neat resolution. Final line
  is a single image or a question. NEVER end with meta commentary
  (`구독 부탁드립니다`).

Sentence length: **20-26 chars/scene, target ~22**. At 5.5 chars/sec
this lands at 14×22/5.5 ≈ 56s rendered audio.

Banned phrases: 무서웠습니다 / 소름이 돋았습니다 / 충격 / 헉 / 대박
(weak telling + YouTube egregious-clickbait policy).

## Files

- [`backend/shorts-core/.../config/ChannelBehavior.kt`](../../backend/shorts-core/src/main/kotlin/com/sciencepixel/config/ChannelBehavior.kt) — `HorrorChannelBehavior` + companion lookups
- [`backend/shorts-api/.../config/DataInitializer.kt`](../../backend/shorts-api/src/main/kotlin/com/sciencepixel/config/DataInitializer.kt) — `script_prompt_v6` for horror (v6.9 description)
- [`backend/shorts-core/.../service/ProductionService.kt`](../../backend/shorts-core/src/main/kotlin/com/sciencepixel/service/ProductionService.kt) — `editSceneWithoutSubtitle` post-EQ wiring + `buildBurnSubtitlesCommand` BGM volume
- [`backend/ai-media-service/app.py`](../../backend/ai-media-service/app.py) — Edge TTS endpoint with 3-tier prosody fallback

## Memory snapshot (cross-session)

The user's auto-memory has the same baseline at
[`memory/horror_voice_v6_9.md`](../../memory/horror_voice_v6_9.md) so
new sessions inherit this without rerunning the whole tuning loop.
