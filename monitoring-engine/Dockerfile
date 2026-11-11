# NetPulse C SQS worker — multi-stage Linux image (libcurl, libpq, OpenSSL, pthreads).
# Build context: repository root
#
#   docker build -f monitoring-engine/Dockerfile -t netpulse-worker .
#
# Long-running SQS consumer only (no HTTP scheduler). Stops on SIGTERM/SIGINT.

FROM debian:bookworm-slim AS build
WORKDIR /src

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        libcurl4-openssl-dev \
        libpq-dev \
        libssl-dev \
        make \
        pkg-config \
    && rm -rf /var/lib/apt/lists/* \
    && pkg-config --exists libpq \
    && test -f /usr/include/postgresql/libpq-fe.h

COPY monitoring-engine/Makefile ./
COPY monitoring-engine/include ./include
COPY monitoring-engine/src ./src

# Makefile uses pkg-config --cflags libpq (-I/usr/include/postgresql on Debian).
RUN make clean && make worker \
    && test -x build/netpulse-worker \
    && ldd build/netpulse-worker | grep -E 'libpq|libcurl|libssl|libcrypto'

FROM debian:bookworm-slim AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        libcurl4 \
        libpq5 \
        libssl3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system netpulse \
    && useradd --system --gid netpulse --home-dir /app --shell /usr/sbin/nologin netpulse

COPY --from=build /src/build/netpulse-worker /app/netpulse-worker
RUN chown netpulse:netpulse /app/netpulse-worker

USER netpulse

# No HTTP port — not a web service. Scheduler lives only in the Java web container.
ENTRYPOINT ["/app/netpulse-worker"]
