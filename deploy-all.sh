#!/bin/bash

# 전체 시스템 재배포 스크립트 (Runner 포함)
# 로컬 개발 또는 전체 시스템 업데이트 시 사용

echo "🚀 Deploying ALL services (including github-runner)..."
echo ""

# Check if .env file exists
if [ ! -f .env ]; then
    echo "⚠️  WARNING: .env file not found!"
    echo "   Please create .env file from .env.example:"
    echo "   cp .env.example .env"
    echo "   Then edit .env with your actual credentials."
    echo ""
    read -p "Continue without .env? (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "❌ Deployment cancelled."
        exit 1
    fi
fi

# Clean up old images
echo "🧹 Cleaning up old images..."
docker image prune -f

# Build images in parallel
echo "🔨 Building images..."
docker-compose build --parallel

# 1. Ensure infrastructure services are running (won't restart if already up and unchanged)
echo "📦 Starting infrastructure services..."
docker-compose up -d zookeeper kafka mongo ai-media-service tailscale

# 2. Force recreate application services including runner (without restarting dependencies)
echo "🚀 Deploying application services (including runner)..."
docker-compose up -d --force-recreate --no-deps \
  github-runner \
  shorts-science shorts-horror shorts-stocks shorts-history \
  shorts-log-service shorts-renderer renderer-autoscaler \
  frontend-server

echo ""
echo "✅ Full deployment complete!"
echo ""
echo "📋 Service status:"
docker-compose ps
