# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Automated YouTube Shorts pipeline that ingests RSS science/horror/stocks/history news, generates scripts via Google Gemini, sources video clips from Pexels, renders with FFmpeg, and uploads to YouTube. A single codebase serves multiple YouTube channels via per-container `SHORTS_CHANNEL_ID` env var.

### Channels

| ID | Name | Port | Schedule |
|----|------|------|----------|
| `science` | 사이언스 픽셀 | 8080 | Every hour at :10, :40 |
| `horror` | 미스터리 픽셀 | 8081 | Every hour at :20, :50 |
| `stocks` | 밸류 픽셀 | 8083 | Daily 17:30 → Upload 18-19:00 |
| `history` | 메모리 픽셀 | 8084 | Daily 06:30 → Upload 07-08:00 |

### Content Guidelines

**CRITICAL**: All video narration scripts MUST use formal/polite Korean (존댓말):
- Use formal endings: -습니다, -입니다, -됩니다, -있습니다
- NEVER use informal speech (반말): -해, -야, -다, -네, -지
- This applies to all prompts in the `system_prompt` collection

## Build & Run Commands

### Backend (Kotlin/Spring Boot 3.2, Gradle, JDK 17)

```bash
cd backend
./gradlew build                        # Build all modules
./gradlew :shorts-api:bootRun          # Run API service locally
./gradlew :shorts-worker:bootRun       # Run worker service locally
./gradlew test                         # Run all tests
./gradlew :shorts-core:test            # Run tests for a single module
./gradlew :shorts-api:test --tests "com.sciencepixel.SomeTest" # Single test class
```

### Frontend (React 19, Vite, TypeScript)

```bash
cd frontend
npm install
npm run dev          # Dev server at http://localhost:5173
npm run build        # Production build (tsc -b && vite build)
npm run lint         # ESLint
```

### Docker (Full Stack)

```bash
docker-compose build --parallel        # Build all images
docker-compose up -d                   # Start all services
docker-compose logs -f shorts-science  # Tail a specific channel's logs
```

## Architecture

### Backend Modules (`backend/`)

Three Gradle modules under `com.sciencepixel` (group), configured in `settings.gradle.kts`:

- **shorts-core** — Shared library (not bootable). Domain models (`VideoHistory`, `VideoStatus`, `SystemPrompt`, `BgmEntity`, `NewsItem`, `QuotaUsage`), MongoDB repositories, services (`GeminiService`, `YoutubeService`, `ProductionService`, `PexelsService`, `AudioService`, `QuotaTracker`, `JobClaimService`), Kafka config/event DTOs, and channel behavior config.
- **shorts-api** — Spring Boot app (port 8080-8084 per channel). REST controllers (`AdminController`, `ManualGenerationController`, `OAuthController`), Spring Batch RSS ingestion pipeline (`batch/`), Kafka producers, scheduled jobs (`BatchScheduler`, `CleanupScheduler`, `YoutubeUploadScheduler`), and a `ScriptConsumer` that calls Gemini for script generation.
- **shorts-worker** — Spring Boot app for rendering. Kafka consumers: `SceneConsumer` (downloads Pexels clips) and `RenderConsumer` (FFmpeg video assembly). Runs with GPU access in Docker.

### Python Services (`backend/`)

- **ai-media-service** — FastAPI (port 8000). Edge TTS for narration, HuggingFace MusicGen for background music. Requires NVIDIA GPU.
- **autoscaler** — Monitors Kafka lag and scales `shorts-renderer` containers via Docker API.

### Frontend (`frontend/`)

React + TypeScript admin dashboard. Tailwind CSS styling, Axios HTTP client, i18n (Korean/English), PWA-capable. Communicates with the API services.

### Event Flow (Kafka Topics)

```
RSS Feed → [shorts-api: Spring Batch]
  → rss-new-item → [ScriptConsumer: Gemini script generation]
  → video-script-created → [SceneConsumer: Pexels asset download]
  → video-assets-ready → [RenderConsumer: FFmpeg rendering]
  → Upload scheduled by YoutubeUploadScheduler
```

Additional topic: `system-logs` consumed by `shorts-log-service` (port 8082).

### Multi-Channel Design

Same Docker image, different container per channel. Channel is selected by `SHORTS_CHANNEL_ID` env var (science, horror, stocks, history). Each container gets its own port, YouTube OAuth credentials, and channel-specific prompts stored in MongoDB.

### Key External APIs

- **Google Gemini** — Script/metadata generation (quota-tracked via `QuotaTracker`)
- **Pexels** — Context-aware stock video search
- **YouTube Data API v3** — OAuth 2.0 upload, status sync
- **Microsoft Edge TTS** — Neural text-to-speech narration
- **Discord Webhooks** — Operational notifications

## Deployment

**CRITICAL - Deployment Workflow**: NEVER manually restart containers or services. Always use GitHub deployment workflow:

1. Commit and push changes to `develop` branch
2. GitHub Actions self-hosted runner automatically deploys
3. Runner container (`shorts-github-runner`) is excluded from force-recreate

```bash
git add .
git commit -m "your changes"
git push origin develop  # This triggers auto-deployment
```

CI/CD pipeline (`.github/workflows/deploy.yml`):
- Watches `develop` branch for changes
- Builds Docker images in parallel
- Recreates all services except `shorts-github-runner`
- Sends Discord notification on completion

Production is accessed through Tailscale VPN (frontend shares Tailscale's network namespace).

### Service Restart Policies

All services are configured to auto-restart after system reboot:
- `github-runner`: `restart: always` (starts even if manually stopped)
- All other services: `restart: unless-stopped` (starts unless manually stopped)

## Database

**Database name**: `sciencepixel` (NOT shorts_db)
- MongoDB running on port 27017
- Channel-isolated collections with compound indexes on `channelId`
- System prompts stored in `system_prompt` collection
- YouTube OAuth tokens stored in `backend/tokens/` volume mount

Important collections:
- `video_history` — Video metadata and status tracking
- `system_prompt` — Channel-specific prompts (must use 존댓말)
- `bgm_entity` — Background music assets
- `quota_usage` — API quota tracking (Gemini, YouTube)
