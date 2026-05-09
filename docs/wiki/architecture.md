# Architecture

System turns RSS science / horror / stocks / history news into 50-60s
Korean YouTube Shorts. Single Spring Boot codebase serves four channels
via per-container `SHORTS_CHANNEL_ID`. GPU-bound rendering runs in a
separate worker container.

## Component map

```
RSS / Reddit / Wikipedia / Google News
          │
          ▼ (Spring Batch in shorts-api per channel)
   shorts-{science,horror,stocks,history}-controller   ← Gemini prompt → ScriptCreated
          │ (Kafka)
          ▼
   shorts-renderer (worker, GPU)                       ← Pexels + Edge TTS + FFmpeg
          │ (Kafka)
          ▼
   YoutubeUploadScheduler (back in the controllers)    ← OAuth upload
          │
          ▼
                   YouTube Data API v3
```

Supporting services: `ai-media-service` (FastAPI on port 8000, Edge TTS +
MusicGen, GPU-accessing), `shorts-log-service` (port 8082, consumes
`system-logs` Kafka topic), `mongo` (`sciencepixel` DB), `kafka` +
`zookeeper`, `frontend-server` (React, Tailscale-namespaced),
`renderer-autoscaler` (monitors Kafka lag, scales renderer count).

## Three Gradle modules

- **shorts-core** (library) — domain models, MongoDB repositories,
  shared services (`GeminiService`, `YoutubeService`, `ProductionService`,
  `PexelsService`, `AudioService`, `QuotaTracker`, `JobClaimService`),
  Kafka config + event DTOs, [`ChannelBehavior`](channel-behavior.md)
  abstraction.
- **shorts-api** (Spring Boot, port 8080-8084 per channel) — REST
  controllers, Spring Batch RSS ingestion, Kafka producers, scheduled
  jobs (`BatchScheduler`, `CleanupScheduler`, `YoutubeUploadScheduler`),
  `ScriptConsumer` that calls Gemini.
- **shorts-worker** (Spring Boot) — Kafka consumers `SceneConsumer`
  (Pexels download + audio + scene clip render) and `RenderConsumer`
  (final FFmpeg merge + subtitle burn + BGM mix). GPU access in Docker.

The **renderer container** is the source of one major footgun: it boots
with `SHORTS_CHANNEL_ID=renderer` so its `RendererChannelBehavior` is
what Spring DI injects, but it processes jobs for *every* channel. Any
per-channel customization (voice, BGM mix volume, post-EQ) must use the
**static `ChannelBehavior.companion` lookups keyed on the job's
`effectiveChannelId`**, not the injected per-instance properties. See
[channel-behavior.md](channel-behavior.md).

## Multi-channel design

Same Docker image, different container per channel. Container env wires
the channel:

```yaml
shorts-horror:
  image: shorts-api:latest
  environment:
    - SHORTS_CHANNEL_ID=horror
    - YOUTUBE_CLIENT_SECRET_JSON_B64=${SECRET_HORROR_B64}
    - APP_FEATURE_REMOTE_RENDERING=true     # uses SAGA flow
    - APP_FEATURE_CONSUMER_SCRIPT=true      # this container runs Gemini
    - APP_FEATURE_CONSUMER_SCENE=false      # renderer does the scenes
    - APP_FEATURE_CONSUMER_RENDER=false
    - APP_SCHEDULING_BATCH_CRON=0 0 1,5,9,13,17,21 * * *
    - APP_SCHEDULING_UPLOAD_CRON=0 0 1,4,7,10,13,16,19,22 * * *
```

Channel cron + per-channel YouTube OAuth credentials are the only thing
that distinguishes the four controllers. All scheduling and cleanup
behavior comes from `ChannelBehavior`.

## Event flow

Detailed Kafka topics + which container consumes what is in
[saga-flow.md](saga-flow.md).
