# Project Wiki — Index

Catalog of every wiki page with a one-line summary, organized by category.
Following [Andrej Karpathy's LLM Wiki pattern](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f):
the human curates raw sources (`docs/`, code, MongoDB, RSS, the user's own
listening), the LLM owns this directory.

> **Schema** lives in [`/CLAUDE.md`](../../CLAUDE.md) — repo-wide conventions
> any LLM agent should read before editing. **Operational changelog** lives
> in [`log.md`](log.md). All entity pages cross-link with relative paths.

---

## Architecture
- [architecture.md](architecture.md) — three Gradle modules + Python media + Kafka SAGA flow + GPU renderer + multi-channel-via-env
- [saga-flow.md](saga-flow.md) — Kafka topic chain (`rss-new-item` → `video-script-created` → `video-assets-ready` → upload), which container processes which topic
- [deployment-workflow.md](deployment-workflow.md) — push to `develop` → self-hosted GitHub Actions runner → `docker compose build --parallel` → `up -d --force-recreate` (excludes runner)

## Channels
- [channel-behavior.md](channel-behavior.md) — `ChannelBehavior` interface + per-channel implementations + the `RendererChannelBehavior` multi-tenant gotcha + companion-object lookups
- [horror-channel.md](horror-channel.md) — 미스터리 픽셀: TTS v6.9 (SunHi female + warm post-EQ + BGM 0.45), prompt v6.9 4-phase Hook→Build→Reveal→Linger
- [science-channel.md](science-channel.md) — 사이언스 픽셀: prompt v7 (Kurzgesagt 4-part / Vsauce 3-act), SunHi default voice
- [stocks-channel.md](stocks-channel.md) — 밸류 픽셀: prompt v7 NCI structure + 24h freshness window + Korean retail-investor compliance
- [history-channel.md](history-channel.md) — 메모리 픽셀: prompt v7 5-beat causal chain + Wineburg 3-principles + sensitive-topic discipline

## TTS / Voice
- [edge-tts-korean-voices.md](edge-tts-korean-voices.md) — 9 Azure ko-KR voices, only 4 actually exposed by free `rany2/edge-tts`; per-channel voice/rate/pitch/volume; FFmpeg post-EQ recipes
- [prompt-validator.md](prompt-validator.md) — `GeminiService` 14-scene + 30-90s window, channel-aware `chars/sec` calibration

## External integrations
- [external-apis.md](external-apis.md) — Gemini (quota, prompts), Pexels (video + thumbnail), Edge TTS, YouTube Data v3, Discord webhooks, Tailscale frontend
- [mongo-collections.md](mongo-collections.md) — `video_history`, `system_prompt`, `bgm_entity`, `quota_usage`, `system_setting`, `youtube_video`

## Operations history (deep-dives)
- [refactoring-srp-analysis.md](refactoring-srp-analysis.md) — 2026-03-15 SRP refactoring analysis for the backend modules
