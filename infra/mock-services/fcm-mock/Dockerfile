# =============================================================================
# FCM mock — Node 20 alpine + express
# -----------------------------------------------------------------------------
# - alpine = 작은 이미지. wget이 기본 포함돼 healthcheck에 그대로 사용.
# - npm ci 대신 npm install: lockfile 없는 게 작아서 OK.
# =============================================================================
FROM node:20-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

COPY server.js ./

ENV PORT=8086
EXPOSE 8086

CMD ["node", "server.js"]
