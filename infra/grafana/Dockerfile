FROM grafana/grafana:13.2.3@sha256:b28bae15e219c998fb0e0424ed724930cc61b1f61fb404d47c862f9a23f9e572 AS plugins
USER root
ARG TARGETARCH
ENV TARGETARCH=${TARGETARCH}
RUN apk add --no-cache python3
COPY plugins.lock.json install-plugins.py /build/
RUN python3 /build/install-plugins.py

FROM grafana/grafana:13.2.3@sha256:b28bae15e219c998fb0e0424ed724930cc61b1f61fb404d47c862f9a23f9e572
USER root
RUN apk upgrade --no-cache \
    && rm -rf /usr/share/grafana/data/plugins-bundled/grafana-postgresql-datasource \
      /usr/share/grafana/data/plugins-bundled/influxdb /usr/share/grafana/data/plugins-bundled/jaeger \
      /usr/share/grafana/data/plugins-bundled/prometheus /usr/share/grafana/data/plugins-bundled/stackdriver \
      /usr/share/grafana/data/plugins-bundled/tempo
COPY --from=plugins --chown=472:0 /out/ /usr/share/grafana/data/plugins-bundled/
USER 472
