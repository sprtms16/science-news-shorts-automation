# 🧪 Science News Shorts Automation
> **AI 기반 과학 뉴스 쇼츠 자동 생성 및 업로드 파이프라인 구축 프로젝트**

[![Stack](https://img.shields.io/badge/Kotlin-Spring%20Boot%203.2-purple)]() [![Stack](https://img.shields.io/badge/Architecture-Event%20Driven%20(Kafka)-red)]() [![Stack](https://img.shields.io/badge/AI-Gemini%203%20Experimental-blue)]() [![Stack](https://img.shields.io/badge/DevOps-Docker%20Compose-green)]() [![PWA](https://img.shields.io/badge/Mobile-PWA%20Ready-ff69b4)]() [![Version](https://img.shields.io/badge/Version-v2.3.0-orange)]()

## 📖 Project Overview
매일 쏟아지는 최신 과학 뉴스를 1분 내외의 **숏폼(Shorts) 영상**으로 **완전 자동화(Fully Automated)** 하여 제작하고 YouTube에 업로드하는 시스템입니다.
뉴스 수집부터 대본 작성, 리소스(영상/이미지) 확보, 나레이션(TTS), 배경음악(BGM) 생성, 영상 편집(FFmpeg), 그리고 최종 업로드까지 사람의 개입 없이 24시간 운영됩니다.

## 🚀 Key Features
- **Zero-Touch Automation**: RSS 피드 수집부터 유튜브 업로드까지 전 과정 자동화.
- **AI-Powered Content Creation**:
  - **Scripting**: Google Gemini 3 (Experimental Preview) 및 2.5 Flash를 활용하여 뉴스 요약 및 쇼츠 전용 대본/키워드 생성.
  - **Voice**: Microsoft Edge-TTS를 사용한 자연스러운 한국어 나레이션.
  - **Audio**: Text-to-Music (MusicGen) 모델을 활용하여 분위기에 맞는 BGM 생성 및 매칭.
- **Dynamic Video Production**:
  - **FFmpeg Pipeline**: 자막(SRT) 생성, 하드코딩(Burning), 오디오 믹싱, 영상 크롭(9:16) 자동화.
  - **Smart Resource Matching**: 대본의 문맥(Context)을 분석하여 Pexels API에서 최적의 영상 클립 매칭.
- **Advanced Admin Dashboard (React)**:
  - **Monitoring**: 영상 제작 및 업로드 상태 실시간 모니터링 및 필터링/검색 지원.
  - **Maintenance**: 유튜브 링크 동기화, 메타데이터 재생성, 누락 파일 복구 등 매뉴얼 도구 제공.
  - **Settings**: 동적 버퍼 제한(최대 생성 개수) 및 업로드 차단 해제 시간 설정.
  - **Global & Adaptive UI**:
    - **Internationalization (i18n)**: 한글/영어(KO/EN) 실시간 전환 및 자동 감지 지원.
    - **Dynamic Theming**: Light/Dark/System 테마 모드 지원 및 Glassmorphism 디자인 최적화.
    - **Progressive Web App (PWA)**: 모바일 설치 지원 및 Offline-First 아키텍처로 모바일 사용성 극대화.
- **Efficient Delivery & UX**:
  - **RFC-5987 Compliance**: 한글 파일명이 포함된 영상도 모바일/브라우저에서 깨짐 없이 안전하게 다운로드.
  - **Async Orchestration**: Spring `@Async`를 활용한 논블로킹(Non-blocking) 백그라운드 업로드 및 알림 시스템.
- **Robust Architecture**:
  - **Dual-Lock Quota Guard**: Gemini API 및 YouTube API의 쿼터를 실시간으로 추적하며 최적의 생성 속도 유지.
  - **Self-Healing**: 파일 손상이나 누락 시 자동으로 감지하여 재생성(Regeneration).
  - **Cleanup**: 업로드 완료된 리소스 및 1시간 이상 정체된 작업을 주기적으로 정리하여 디스크 효율화.

## 🛠️ System Architecture
시스템은 크게 **Ingestion(수집) - Processing(가공) - Production(제작) - Delivery(배포)** 의 4단계 파이프라인으로 구성되어 있습니다.

```mermaid
graph TD
    subgraph "Ingestion & Event Layer"
        RSS[Google News RSS] -->|Spring Batch| Reader[VideoProcessor]
        Reader -->|Duplicate Check| Mongo[(MongoDB)]
        Reader -->|Event: video.created| Kafka{Apache Kafka}
    end

    subgraph "Core Service (Kotlin + Python)"
        Kafka -->|Consume| AsyncService[Service Layer]
        AsyncService -->|Script Request| Gemini[Google Gemini API]
        AsyncService -->|Video Request| Pexels[Pexels API]
        AsyncService -->|BGM Request| MusicGen[Python AI Service]
    end

    subgraph "Production Layer (FFmpeg)"
        AsyncService -->|Assets Ready| FFmpeg[Video Render Engine]
        FFmpeg -->|Merge/Subtitles| FinalVideo[MP4 File]
    end

    subgraph "Delivery & DevOps"
        FinalVideo -->|Scheduled| Uploader[YouTube Upload Scheduler]
        Uploader -->|Quota Check| YouTube[YouTube Shorts]
        Uploader -->|Error/Retry| Kafka
        FileSystem -->|Cleanup Job| Trash[Resource Cleaner]
    end
```

## 💻 Tech Stack
### Backend & Core
- **Language**: Kotlin (JDK 17)
- **Framework**: Spring Boot 3.2, Spring Batch
- **Database**: MongoDB (Metadata History)
- **Message Broker**: Apache Kafka (Confluent Platform)

### AI & Media Processing
- **LLM**: Google Gemini 3 Experimental & 2.5 Flash (Script & Metadata Generation)
- **Voice**: Microsoft Edge-TTS (Neural Text-to-Speech)
- **Audio AI**: Hugging Face `facebook/musicgen-small` (Python Microservice)
- **Video Engine**: FFmpeg (Clipping, Filtering, Rendering)

### DevOps & Infrastructure
- **Container**: Docker, Docker Compose
- **Scheduling**: Spring Scheduler (Cron)

## 📦 Installation & Setup

### Prerequisites
- Docker & Docker Compose
- Java 17+ (for local development)
- Google Cloud Project (YouTube Data API v3, Gemini API enabled)
- Pexels API Key

### Configuration
1. **Clone Repository**
   ```bash
   git clone https://github.com/sprtms16/science-news-shorts-automation.git
   cd science-news-shorts-automation
   ```

2. **API Keys Setup**
   - `backend/tokens/` 디렉토리에 YouTube OAuth2 자격 증명 파일(`StoredCredential`)이나 `client_secret.json`을 위치시킵니다.
   - Pexels 및 Gemini API Key는 환경 변수 또는 설정 파일에 입력합니다.

3. **Build & Run (Docker)**
   전체 시스템은 Docker Compose로 구성되어 있어 한 번의 명령으로 실행 가능합니다.
   ```bash
   # Build and Start all services
   docker-compose up -d --build
   ```

4. **Verify Services**
   - **Admin Dashboard**: `http://localhost:3000` (Frontend) or via Tailscale IP.
   - **Controller API**: `http://localhost:8080/swagger-ui.html` (if enabled) or check logs.
   - **Kafka UI** (Optional): `http://localhost:9000` (if configured).

## 🚀 Deployment

이 프로젝트는 **GitHub Actions 자동 배포**와 **로컬 수동 배포** 두 가지 방식을 모두 지원합니다.

### 환경 변수 설정

배포 전에 환경 변수를 설정해야 합니다.

1. `.env.example` 파일을 복사하여 `.env` 파일 생성:
   ```bash
   cp .env.example .env
   ```

2. `.env` 파일을 열어 실제 값으로 수정:
   ```bash
   # API Keys
   GEMINI_API_KEY=your_actual_gemini_api_key
   PEXELS_API_KEY=your_actual_pexels_api_key

   # Infrastructure
   TAILSCALE_AUTHKEY=your_actual_tailscale_authkey
   DISCORD_WEBHOOK_URL=your_actual_discord_webhook_url

   # GitHub Personal Access Token (for self-hosted runner)
   GITHUB_PAT=your_actual_github_pat

   # YouTube OAuth Client Secrets (Base64 encoded JSON)
   SECRET_SCIENCE_B64=your_base64_encoded_science_secret
   SECRET_HORROR_B64=your_base64_encoded_horror_secret
   SECRET_STOCKS_B64=your_base64_encoded_stocks_secret
   SECRET_HISTORY_B64=your_base64_encoded_history_secret
   ```

   **YouTube Secret Base64 인코딩 방법:**
   ```bash
   echo -n '{"installed": {...}}' | base64 -w 0
   ```

### GitHub Actions 자동 배포

`develop` 브랜치에 Push하면 자동으로 배포가 실행됩니다.

```bash
git add .
git commit -m "feat: your changes"
git push origin develop
```

**배포 프로세스:**
1. Self-hosted runner가 코드 변경 감지
2. Docker 이미지 빌드 (병렬 처리)
3. 서비스 재시작 (github-runner 제외)
4. 배포 완료 알림

**참고:** GitHub Actions는 `github-runner` 컨테이너를 제외한 모든 서비스를 배포합니다.

### 로컬 배포 스크립트

로컬 환경에서 직접 배포할 수 있는 3가지 스크립트를 제공합니다:

#### 1. 서비스 배포 (GitHub Actions와 동일)

Runner를 제외한 모든 서비스를 재배포합니다.

```bash
chmod +x deploy-services.sh
./deploy-services.sh
```

**용도:**
- GitHub Actions 배포와 동일한 방식으로 로컬에서 서비스 배포
- Runner는 변경하지 않고 다른 서비스만 업데이트할 때

**배포되는 서비스:**
- zookeeper, kafka, mongo
- ai-media-service
- shorts-science, shorts-horror, shorts-stocks, shorts-history
- shorts-log-service, shorts-renderer, renderer-autoscaler
- frontend-server, tailscale

#### 2. Runner 재시작

GitHub Actions Runner만 재시작합니다.

```bash
chmod +x deploy-runner.sh
./deploy-runner.sh
```

**용도:**
- 타임존 변경 등 환경 변수 업데이트 시
- Runner 설정 변경 후 적용
- Runner 로그 확인 (자동으로 최근 20줄 출력)

#### 3. 전체 시스템 배포

Runner를 포함한 모든 서비스를 재배포합니다.

```bash
chmod +x deploy-all.sh
./deploy-all.sh
```

**용도:**
- 로컬 개발 환경 초기 설정
- 전체 시스템 업데이트
- Docker Compose 설정 대규모 변경 시

### 배포 스크립트 특징

모든 배포 스크립트는 다음 기능을 제공합니다:

- ✅ `.env` 파일 존재 여부 자동 확인
- ✅ 누락 시 사용자에게 경고 및 중단 옵션 제공
- ✅ Docker 이미지 병렬 빌드로 배포 속도 최적화
- ✅ 오래된 이미지 자동 정리 (디스크 공간 절약)
- ✅ 배포 완료 후 서비스 상태 자동 출력

### 배포 후 확인

```bash
# 모든 서비스 상태 확인
docker-compose ps

# 특정 서비스 로그 확인
docker-compose logs -f shorts-science

# Runner 로그 확인
docker-compose logs -f github-runner
```

## 💡 Smart Logic Highlights
### 1. Quota-Aware Scheduling & Gemini Guard
- YouTube API의 일일 할당량(Quota) 제한을 고려하여, **'One-by-One'** 방식의 순차 업로드를 구현했습니다.
- **Gemini Guard**: 다중 API 키를 활용하여 RPM/TPM/RPD를 실시간 추적하며, 429 에러 없이 안정적으로 대본을 생성합니다.

### 2. Dual-Lock Buffer Management
- **Strict Limit**: 업로드되지 않은 영상이 10개를 초과하지 않도록 2단계(Scheduler + Processor)에서 정밀하게 체크합니다.
- **Auto-Sync**: 수동으로 유튜브 링크를 입력하더라도 즉시 카운트에서 제외되어 제작 파이프라인이 유동적으로 재개됩니다.

### 3. Self-Healing & Deep Repair
- 네트워크 오류나 FFmpeg 렌더링 실패로 인해 결과물이 누락된 경우 (`FILE_NOT_FOUND`), 시스템이 이를 감지하고 스스로 재생성(`REGENERATING`) 프로세스를 트리거합니다.
- **Deep Repair**: DB와 파일 시스템 간의 불일치를 일괄적으로 해결하고 영어 제목을 한글로 복원하는 강력한 관리 도구를 제공합니다.
- **Quota Recovery**: 할당량 초과(`QUOTA_EXCEEDED`)로 멈춘 영상들을 자동 감지하여 할당량 초기화 시점에 맞춰 스마트하게 재시도합니다.

### 4. Automated Cleanup & Safety
- 서버 디스크 공간 관리를 위해 업로드 완료된 건과 1시간 이상 정체된 실패 작업을 매시 30분마다 자동으로 청소합니다.
- **Safety Filter**: Gemini AI를 통해 정치/종교 등 민감한 주제의 뉴스를 생성 단계에서 1차로, 완료 후 2차로 교차 검열하여 채널 안정성을 확보합니다.

## 📂 Project Structure
```
root/
├── backend/                # Spring Boot App & Python Service
│   ├── src/                # Kotlin Source Code
│   ├── ai-media-service/   # Python AI Service
│   ├── tokens/             # YouTube OAuth Credentials
│   ├── Dockerfile.kotlin
│   └── build.gradle.kts
├── frontend/               # React Web App
├── vpn-state/              # Tailscale State & Logs
├── shared-data/            # Mounted Volume (Videos, Assets)
├── docker-compose.yml
└── README.md
```

## 📝 Usage (Manual Trigger)
자동 스케줄러 외에도 API를 통해 수동으로 제어할 수 있습니다.
```bash
# 수동 업로드 트리거
POST /manual/scheduler/trigger

# 리소스 청소 트리거
POST /manual/cleanup/trigger

# 특정 주제로 영상 생성 요청
POST /manual/batch/topic
{
  "topics": ["Quantum Computing", "Black Hole"],
  "style": "news"
}
```

---
*Created by sprtms16*
