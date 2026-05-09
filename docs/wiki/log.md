# Project Log

Append-only chronological record of meaningful changes.
Format: `## [YYYY-MM-DD] <kind> | <title>` followed by a 1-3 line summary
and links to related entity pages and the commit SHA(s).

> Kinds: `ingest` (new external knowledge), `decision` (chosen approach),
> `incident` (production issue + fix), `tune` (parameter change with rationale),
> `refactor` (code reorganization).

---

## [2026-05-09] tune | Horror BGM mix volume 0.20 → 0.45

User reported the v6.9 sample sounded like it had no BGM. ffprobe showed
narration −8.5 dB vs BGM −29.5 dB (21 dB diff, well below the 12-15 dB
voice-over standard). Added per-channel `ChannelBehavior.bgmMixVolumeFor`;
horror gets 0.45 (~14 dB diff, BGM clearly present), other channels keep
0.20. See [horror-channel.md](horror-channel.md). Commit `8874e75`.

## [2026-05-09] decision | Horror voice → SunHi (female)

User A/B-tested 9 generated samples (`scripts/generate_voice_samples.py`)
and chose SunHi at -10Hz/-10%/-5% as the only voice that read as a calm
괴담 storyteller rather than a synthetic newscaster. Replaced
HyunsuMultilingual male v6.7. See [horror-channel.md](horror-channel.md),
[edge-tts-korean-voices.md](edge-tts-korean-voices.md). Commit `559e7f4`.

## [2026-05-09] ingest | Edge TTS exposes only 4 of 9 ko-KR voices

Sample tests confirmed BongJin / GookMin / JiMin / SeoHyeon / YuJin return
a stuck 4.25s response from the free `rany2/edge-tts` endpoint regardless
of prosody — they're listed in Azure docs but not in the Edge browser
TTS gateway. Usable set = SunHi, InJoon, Hyunsu, HyunsuMultilingual.
Documented in [edge-tts-korean-voices.md](edge-tts-korean-voices.md).

## [2026-05-09] tune | Validator now channel-aware (chars/sec, 30-90s)

`GeminiService` script validation switched from a global `chars/10`
estimate (which over-counted slow horror narration ~1.8x and let 80s
scripts pass) to channel-specific `ttsCharsPerSecondFor`. Window widened
from 35-65s (60s ceiling) to 30-90s after confirming Shorts now allows
3 minutes (Oct 2024) and the algorithm ranks on retention, not length.
See [prompt-validator.md](prompt-validator.md). Commit `0d8cddb`.

## [2026-05-09] tune | Prompts v7 (science / stocks / history) + horror v6.6

Per-channel research-grounded rewrites. Each prompt bakes in a measured
story architecture (Kurzgesagt+Vsauce / NCI / 5-beat causal / 4-phase),
banned-words list aligned with YouTube's egregious-clickbait policy,
title-formula menu calibrated to Korean Shorts CTR data, and Pexels
keyword convention for thumbnail-grade images. See per-channel pages.
Commit `168cf1c`.

## [2026-05-09] tune | Stocks 24h freshness window

`fetchStockNews` was filtering only the base business feed against
`startOfDay` (1-hour window if batch ran near midnight) and bypassing
the filter on Google News ticker results entirely — 8 stale COMPLETED
jobs (145h-1401h old) had accumulated. Replaced with explicit
`cutoff = now - 24h` applied uniformly. See
[stocks-channel.md](stocks-channel.md). Commit `7a28bef`.

## [2026-05-08] incident | Tailscale auth key expired → frontend down

`tailscaled got signal terminated; ... tailscale up failed: exit status 1`
loop crashed the tailscale container and took the frontend with it
(network_mode: service:tailscale). User regenerated reusable authkey on
Tailscale admin and updated the GitHub `TAILSCALE_AUTHKEY` secret;
empty-commit push triggered re-deploy with the new key. See
[deployment-workflow.md](deployment-workflow.md).

## [2026-05-08] incident | GitHub Actions runner v2.331.0 deprecated

`Runner version v2.331.0 is deprecated and cannot receive messages` —
`DISABLE_AUTO_UPDATE=true` had blocked GitHub's in-place runner upgrade
until the version drifted into the rejection window. Pulled the latest
`myoung34/github-runner` image, force-recreated the container (one-time
manual exception to "never restart manually"), runner came up at v2.334.0
and picked up the queued deploy. Removed the `DISABLE_AUTO_UPDATE` env
entirely. See [deployment-workflow.md](deployment-workflow.md).

## [2026-05-08] incident | Horror SAGA used SunHi defaults despite Hyunsu config

ProductionService runs inside `shorts-renderer` (SHORTS_CHANNEL_ID=renderer
→ RendererChannelBehavior), so `channelBehavior.ttsVoice` always resolved
to the renderer's defaults. Manual-create flow inside the horror controller
worked, but the SAGA flow that actually produces uploaded videos ran with
SunHi for every channel. Fix: `ChannelBehavior.ttsVoiceFor(channelId)`
companion lookup, called from ProductionService with the *effective*
channelId. Commit `0fd30a8`.

## [2026-05-08] decision | Horror TTS replaced Edge-only single voice with per-channel TTS axis

Initial migration: `ChannelBehavior` gains `ttsVoice/ttsRate/ttsPitch`
properties + companion lookups; `ai-media-service` gets a 3-tier fallback
that never returns empty audio; `AudioService` (core + worker) plumbs
voice/rate/pitch end-to-end. Commit `d941e45`.

## [2026-03-15] refactor | Backend SRP analysis

Multi-channel pipeline cleanup analysis covering `shorts-core` /
`shorts-api` / `shorts-worker`. See
[refactoring-srp-analysis.md](refactoring-srp-analysis.md).
