# Rebuild bundled Go tools; retain the upstream PostgreSQL/TimescaleDB engine.
FROM golang:1.27.1-alpine3.23@sha256:0908ac9b9319e09d7c238aabe914e0395c51d63c4e3d0ae8c554fda9158a5769 AS tools
ENV CGO_ENABLED=0 GOTOOLCHAIN=local
WORKDIR /src
ADD --checksum=sha256:cd9719b775dbfedae53923c9b0dc792b66d42c51e0b36652ed6f747fbadc0164 https://codeload.github.com/tianon/gosu/tar.gz/refs/tags/1.19 /src/gosu.tar.gz
ADD --checksum=sha256:ce3cb74504305b8f4c30dc12b06edc454b1d537a07654770f60e21d53f12d27f https://codeload.github.com/timescale/timescaledb-parallel-copy/tar.gz/refs/tags/v0.13.0 /src/parallel-copy.tar.gz
ADD --checksum=sha256:de2a89af74127df64dbb21db1aaea764eabfa054239a16b6d341188164de325e https://codeload.github.com/timescale/timescaledb-tune/tar.gz/refs/tags/v0.19.0 /src/tune.tar.gz
RUN tar -xzf gosu.tar.gz && tar -xzf parallel-copy.tar.gz && tar -xzf tune.tar.gz
WORKDIR /src/gosu-1.19
RUN go get golang.org/x/sys@v0.44.0 && go mod verify && go test ./... && go build -trimpath -o /out/gosu .
WORKDIR /src/timescaledb-parallel-copy-0.13.0
RUN go get github.com/jackc/pgx/v5@v5.11.0 golang.org/x/crypto@v0.57.0 golang.org/x/text@v0.42.0 \
    && go mod verify \
    && go build -trimpath -o /out/timescaledb-parallel-copy ./cmd/timescaledb-parallel-copy
WORKDIR /src/timescaledb-tune-0.19.0
RUN go test ./... && go build -trimpath -o /out/timescaledb-tune ./cmd/timescaledb-tune
RUN go version -m /out/* > /out/build-info.txt

FROM timescale/timescaledb:2.30.2-pg16@sha256:6f139d56042989bd35f50ba5986e492cd32e35cc6778c50be2659add298dc09f
# Signed packages remain on the upstream Alpine release. Re-scan each new build.
RUN apk upgrade --no-cache
COPY --from=tools /out/gosu /out/timescaledb-parallel-copy /out/timescaledb-tune /usr/local/bin/
COPY --from=tools /out/build-info.txt /usr/local/share/ticketing-tools-build-info.txt
RUN gosu postgres id && timescaledb-tune --version
