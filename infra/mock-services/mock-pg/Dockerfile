# Local demo mock; dependencies are installed from the committed lockfile.
FROM node:24-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f

WORKDIR /app
COPY package.json package-lock.json ./
# Runtime executes node directly; package managers are only needed at build time.
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force \
    && rm -rf /usr/local/lib/node_modules/npm /opt/yarn-* \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/yarn /usr/local/bin/yarnpkg
COPY server.js store.js ./
RUN mkdir -p /app/data && chown node:node /app/data
USER node
ENV PORT=8087
EXPOSE 8087
CMD ["node", "server.js"]
