# NetPulse Spring Boot API + optional embedded React SPA (same-origin production).
# Build context: repository root (needed to compile frontend into the jar).
#
#   docker build -f backend/Dockerfile .

FROM node:22-bookworm-slim AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# Production UI talks to same-origin /api (do not enable demo mode here).
ENV VITE_DEMO_MODE=false
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /frontend/dist/ ./src/main/resources/static/
RUN mvn -B -q -DskipTests package \
    && mv target/netpulse-backend-*.jar /build/app.jar

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system netpulse \
    && useradd --system --gid netpulse --home-dir /app --shell /usr/sbin/nologin netpulse

COPY --from=build /build/app.jar /app/app.jar
RUN chown netpulse:netpulse /app/app.jar

USER netpulse
EXPOSE 8080

# Production defaults: authenticated same-origin SPA + API.
# Scheduler OFF by default (free tier uses GitHub Actions one-shot enqueue + bounded C worker).
ENV SERVER_ADDRESS=0.0.0.0 \
    NETPULSE_SECURITY_MODE=AUTHENTICATED \
    NETPULSE_SCHEDULER_ENABLED=false \
    NETPULSE_SESSION_COOKIE_SECURE=true \
    NETPULSE_SESSION_COOKIE_SAME_SITE=Lax \
    MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=never

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PORT:-8080}/health" || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
