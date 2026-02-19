#!/bin/bash

# 서비스 배포 스크립트 (GitHub Actions와 동일)
# github-runner를 제외한 모든 서비스 재배포

echo "🚀 Deploying services (excluding github-runner)..."
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

# 2. Force recreate only application services (without restarting dependencies)
echo "🚀 Deploying application services..."
docker-compose up -d --force-recreate --no-deps \
  shorts-science shorts-horror shorts-stocks shorts-history \
  shorts-log-service shorts-renderer renderer-autoscaler \
  frontend-server

echo ""
echo "✅ Deployment complete!"
echo ""
echo "📋 Service status:"
docker-compose ps
