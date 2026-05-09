# `GeminiService` script validator

After Gemini returns a script, [`writeScript`](../../backend/shorts-core/src/main/kotlin/com/sciencepixel/service/GeminiService.kt)
loops up to `maxAttempts` times, validating each candidate against two
hard rules. If both pass, the script is accepted. Otherwise the function
re-prompts.

## Rule 1 — exactly 14 scenes

```kotlin
if (scriptResponse.scenes.size != 14) {
    logger.warn("Scene count validation failed: {} scenes (expected 14)")
    if (attempt < maxAttempts) continue else break
}
```

All four channel prompts (science v7, horror v6.9, stocks v7, history v7)
specify exactly 14 scenes. The structure each prompt enforces is documented
on the channel page — e.g., horror is **2-7-3-2** (Hook-Build-Reveal-Linger).

## Rule 2 — channel-aware duration window (30-90s)

Switched 2026-05-09 from a global `chars / 10` estimate to channel-aware
`ChannelBehavior.ttsCharsPerSecondFor(channelId)`:

```kotlin
val totalChars      = scriptResponse.scenes.sumOf { it.sentence.length }
val charsPerSecond  = ChannelBehavior.ttsCharsPerSecondFor(channelId)
val rawDuration     = totalChars / charsPerSecond
val adjustedDuration = rawDuration / 1.10  // atempo=1.10 applied later
```

The `chars/sec` rates are calibrated from observed render durations:

| Channel | TTS settings | chars/sec |
|---|---|---|
| science / stocks / history | SunHi `+30%` rate | **8.0** |
| **horror** | SunHi `-10%` rate | **5.5** |

The legacy `chars/10` assumption over-counted slow horror narration by
~1.8x and let 80-second scripts pass as if they were 45s. After the fix,
horror v6.9 generates ~58-64s videos (within sweet spot).

### Window thresholds

```kotlin
when {
    adjustedDuration < 30 || adjustedDuration > 90 -> HARD_FAIL  // retry
    adjustedDuration < 45 || adjustedDuration > 60 -> SOFT_WARN  // accept
    else                                            -> PERFECT
}
```

Why this window: YouTube raised the Shorts max from 60s to **3 minutes**
in October 2024. The algorithm ranks Shorts by retention (target ≥70%),
not length. Inflow Network's 5,400-shorts study found the **50-60s band
gets ~22× the views** of sub-10s clips, so we aim for the 45-60s sweet
spot but accept 30-90s before failing.

Sources:
- [piktochart 2026 length guide](https://piktochart.com/blog/how-long-youtube-shorts/)
- [opus.pro Shorts retention](https://www.opus.pro/blog/ideal-youtube-shorts-length-format-retention)
- [shortimize algorithm 2025](https://www.shortimize.com/blog/how-does-youtube-shorts-algorithm-work)

## What happens when validation fails

Each `continue` re-runs the Gemini call with the same prompt. After
`maxAttempts` (default 7), the function throws
`Script validation failed: Scene count/duration out of acceptable range
after $maxAttempts retries`. The job ends up `FAILED` with that error
in `video_history.errorMessage`; `BatchScheduler.recoverFailedJobs`
sweeps it to `RETRY_QUEUED` an hour later.

Lesson learned (commit `c898c81`): if a prompt's char-per-scene band
multiplied by 14 falls outside 30-90s at the channel's chars/sec, the
job will hard-fail every retry. Always compute `14 × avg_chars /
chars_per_sec` against the window before changing a prompt's char range.

## Soft check — over-long sentences

```kotlin
if (scene.sentence.length > 50) // logged as soft warning
```

Gemini sometimes returns one or two scenes that blow past the prompt's
char range. We log it but don't fail — the duration check above catches
the systemic case anyway.
