# External integrations

| API | Purpose | Auth | Quota tracker |
|---|---|---|---|
| Google Gemini | Script + metadata generation | API key | `QuotaTracker` |
| Microsoft Edge TTS | Korean narration | none (reverse-engineered) | — |
| Pexels | Stock video + thumbnail photo | API key | — |
| YouTube Data API v3 | OAuth upload + metadata | OAuth 2.0 per channel | `QuotaTracker` |
| Discord Webhooks | Operational alerts | webhook URL | — |
| Tailscale | Frontend + admin VPN | reusable authkey | — |
| HuggingFace MusicGen | AI BGM fallback | local model | GPU memory |

## Google Gemini

Spring service: [`GeminiService`](../../backend/shorts-core/src/main/kotlin/com/sciencepixel/service/GeminiService.kt).

- Multiple API keys (10 keys in production) for round-robin load
  spreading; `QuotaTracker` enforces daily request budget across them.
- Script generation: `writeScript(title, summary)` — channel-aware
  prompt loaded from MongoDB `system_prompt` collection (`promptKey =
  script_prompt_v6`). Validation in [prompt-validator.md](prompt-validator.md).
- Specialty calls: `generateScienceNews`, `extractTrendingTickers`
  (stocks), `writeMorningBriefingScript` (stocks), `checkSensitivity`
  (safety filter for stock + history items).
- Quota error handling distinguishes `GeminiRetryableException` (429,
  transient) from `GeminiFatalException` (permanent, key-wide).

## Microsoft Edge TTS

Reached via FastAPI proxy in
[`backend/ai-media-service/app.py`](../../backend/ai-media-service/app.py).
Behavior + voice catalog + 3-tier fallback documented in
[edge-tts-korean-voices.md](edge-tts-korean-voices.md).

## Pexels

Spring service: [`PexelsService`](../../backend/shorts-worker/src/main/kotlin/com/sciencepixel/service/PexelsService.kt).

- `downloadVerifiedVideo(keyword, contextString, outputFile)` —
  search + safety check + download. Falls back to a per-channel
  generic keyword (`"science technology"`, `"dark mystery"`,
  `"business finance"`, `"ancient civilization"`) if the scene-specific
  keyword returns nothing.
- `searchPhoto(keyword)` — single-shot thumbnail search using
  `orientation=portrait`. Returns the first `large2x` URL.

## YouTube Data API v3

Spring service: [`YoutubeService`](../../backend/shorts-core/src/main/kotlin/com/sciencepixel/service/YoutubeService.kt)
+ controllers [`OAuthController`](../../backend/shorts-api/src/main/kotlin/com/sciencepixel/controller/OAuthController.kt),
[`AdminController`](../../backend/shorts-api/src/main/kotlin/com/sciencepixel/controller/AdminController.kt).

- Per-channel OAuth: each `shorts-{channel}-controller` has its own
  `client_secret_*.json` mounted from the host, and stores refresh
  tokens in the `shorts-tokens` Docker volume.
- OAuth callback port `8888` is bound on the science controller only
  (used during initial token issue).
- `YoutubeUploadScheduler` runs on the cron defined per channel
  (`APP_SCHEDULING_UPLOAD_CRON`); uses `requiresStrictDateCheck` from
  [`ChannelBehavior`](channel-behavior.md) to decide whether to upload
  older `COMPLETED` videos.
- `YoutubeSyncService` periodically pulls public stats (views, likes)
  for already-uploaded videos and stores them in `youtube_video`
  collection for the frontend dashboard.

## Discord webhooks

Service: [`NotificationService`](../../backend/shorts-core/src/main/kotlin/com/sciencepixel/service/NotificationService.kt).
Used for failure alerts (Gemini quota exhaustion, render failure,
buffer-full warning) + per-deploy success notification from the GitHub
Actions workflow.

## Tailscale + frontend

`frontend-server` runs as a sidecar that **shares the network namespace**
of the `tailscale` container (`network_mode: service:tailscale`). All
external access goes through the tailscale container's `0.0.0.0:3000`
mapping. If tailscale auth fails the frontend silently disappears — see
the recovery procedure in [deployment-workflow.md](deployment-workflow.md).

## MusicGen-Small (HuggingFace)

Loaded by `ai-media-service` at startup; produces fallback BGM when no
local file matches a horror mood. In practice rarely triggered for
horror because we have curated local files in
`shared-data/bgm/suspense/`. See [horror-channel.md](horror-channel.md)
BGM section.
