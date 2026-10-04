# =============================================================================
# Mock PG — Node 20 alpine + express
# =============================================================================
FROM node:20-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

COPY server.js ./

ENV PORT=8087
EXPOSE 8087

CMD ["node", "server.js"]
