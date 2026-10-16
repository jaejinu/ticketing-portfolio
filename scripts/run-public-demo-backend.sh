#!/bin/bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
set -a
source infra/.env.public-demo
set +a
exec "$JAVA_HOME/bin/java" -Xms256m -Xmx1536m -jar backend/app-gateway/build/libs/app-gateway.jar
