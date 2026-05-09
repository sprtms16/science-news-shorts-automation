# Deployment workflow

Single-rule policy: **never manually restart containers**. Push to
`develop`, the self-hosted GitHub Actions runner deploys.

## Happy path

```
git add ... && git commit && git push origin develop
       │
       ▼
GitHub Actions triggers .github/workflows/deploy.yml on the self-hosted runner
       │
       ▼
   Step 1: Generate .env from GitHub secrets
   Step 2: docker image prune -f
   Step 3: docker compose build --parallel
   Step 4: docker compose up -d --force-recreate \
              zookeeper kafka mongo ai-media-service \
              shorts-{science,horror,stocks,history} \
              shorts-log-service shorts-renderer renderer-autoscaler \
              frontend-server tailscale
   Step 5: rm -f .env (cleanup)
       │
       ▼
Discord notification on completion
```

`shorts-github-runner` is the only container excluded from
`force-recreate` (so a deploy can't kill the runner mid-deploy).

## Service restart policies

- `github-runner`: `restart: always` (resurrects even if you stop it)
- everything else: `restart: unless-stopped` (resurrects unless you stop
  it manually)

Both restart automatically after a host reboot.

## Container topology + ports

| Container | Image | Host port | Notes |
|---|---|---|---|
| `shorts-github-runner` | `myoung34/github-runner:latest` | — | self-hosted runner; **DO NOT** set `DISABLE_AUTO_UPDATE` (see [log.md 2026-05-08](log.md)) |
| `mongo` | mongo official | 27017 | DB name: `sciencepixel` |
| `zookeeper` / `kafka` | confluent | — | Kafka exposed on `kafka:29092` inside the docker network |
| `ai-media-service` | locally built | 8000 | Edge TTS + MusicGen, GPU-accessing |
| `shorts-science-controller` | `shorts-api:latest` | 8080 + 8888 | port 8888 is the YouTube OAuth callback |
| `shorts-horror-controller` | `shorts-api:latest` | 8081 | |
| `shorts-stocks-controller` | `shorts-api:latest` | 8083 | |
| `shorts-history-controller` | `shorts-api:latest` | 8084 | |
| `shorts-log-service` | `shorts-log-service:latest` | 8082 | |
| `shorts-renderer` | `shorts-worker:latest` | — | GPU access (`NVIDIA_VISIBLE_DEVICES=all`) |
| `renderer-autoscaler` | locally built | — | scales renderer count by Kafka lag |
| `frontend-server` | locally built | (via tailscale) | `network_mode: service:tailscale` |
| `tailscale` | `tailscale/tailscale:latest` | 3000 (→ 80) | Tailscale serve makes the frontend reachable on `shorts-admin.taile6413.ts.net` |

## Known incidents (recovery patterns)

### Tailscale auth key expired → frontend down

Symptom: `localhost:3000` returns `ERR_EMPTY_RESPONSE`, frontend MP4
downloads return `ERR_FAILED`. Tailscale logs show
`tailscaled got signal terminated; ... tailscale up failed: exit status 1`
loop.

Recovery (one-time manual exception to "never restart"):

1. Generate a new reusable authkey at
   https://login.tailscale.com/admin/settings/keys (Reusable: ON,
   Ephemeral: OFF).
2. Update GitHub secret `TAILSCALE_AUTHKEY` at
   https://github.com/sprtms16/science-news-shorts-automation/settings/secrets/actions.
3. Trigger a re-deploy:
   `git commit --allow-empty -m "Re-deploy with refreshed Tailscale authkey" && git push`.
4. The next deploy regenerates `.env` with the fresh key, force-recreates
   tailscale, and frontend recovers.

### GitHub runner deprecated

Symptom: deploys queue but never start. Runner logs show
`Runner version v2.X.Y is deprecated and cannot receive messages`.

Cause: a `DISABLE_AUTO_UPDATE=true` env on the runner blocked GitHub's
in-place upgrade until the version drifted out of acceptance.

Recovery:

```bash
docker compose pull github-runner
docker compose up -d --force-recreate --no-deps github-runner
docker logs -f shorts-github-runner   # confirm new version + "Listening for Jobs"
```

The `myoung34/github-runner` image treats the env var as
**set = disabled regardless of value** — the only safe state is to NOT
set it. We removed it entirely from
[`docker-compose.yml`](../../docker-compose.yml) on 2026-05-08.

## Manual scripts (not for production)

[`deploy-services.sh`](../../deploy-services.sh) — same as the GitHub
Actions deploy step (excludes runner). Use only if Actions itself is
broken.

[`deploy-all.sh`](../../deploy-all.sh) — also force-recreates the
runner. Last-resort.

[`deploy-runner.sh`](../../deploy-runner.sh) — restart only the runner
container. Useful only if you've changed runner-specific env (TZ etc.)
without changing image.
