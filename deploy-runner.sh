#!/bin/bash

# GitHub Actions Runner 재시작 스크립트
# 타임존 변경 등 환경변수 업데이트 시 사용

echo "🔄 Restarting GitHub Actions Runner..."

docker-compose restart github-runner

echo "✅ GitHub Runner restarted successfully!"
echo ""
echo "📋 Checking runner logs..."
docker-compose logs --tail 20 github-runner
