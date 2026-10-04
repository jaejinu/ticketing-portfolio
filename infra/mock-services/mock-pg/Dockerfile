# Local demo mock; dependencies are installed from the committed lockfile.
FROM node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1

WORKDIR /app
COPY package.json package-lock.json ./
# Runtime executes node directly; package managers are only needed at build time.
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force \
    && rm -rf /usr/local/lib/node_modules/npm /opt/yarn-* \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/yarn /usr/local/bin/yarnpkg
COPY server.js ./
USER node
ENV PORT=8087
EXPOSE 8087
CMD ["node", "server.js"]
