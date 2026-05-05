# Talon notification push relay — multi-stage Docker build.
#
# Build:   docker build -t talon-relay -f relay/Dockerfile .
#          (run from repo root — the Gradle build needs gradlew,
#          settings.gradle.kts, and libs.versions.toml at the top
#          of the build context)
# Run:     docker run -p 8080:8080 \
#            -v $(pwd)/data:/data \
#            -e RELAY_MASTER_SECRET=<32+ random chars> \
#            -e RELAY_DB=/data/relay.db \
#            talon-relay
#
# Push transport is UnifiedPush (no Google / FCM dep). The relay
# POSTs to whatever distributor endpoint each device hands it at
# /register time. No credentials needed on the relay side.
#
# Self-host: `git clone … && docker build relay/` is the whole flow.
# The image expects /data to be a host-mounted volume so the sqlite
# file survives container restarts.

FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
# Copy the gradle wrapper + project files needed to resolve the
# :relay subproject. We pull the whole repo so settings.gradle.kts +
# libs.versions.toml come along; the build only compiles :relay.
COPY . .
RUN ./gradlew --no-daemon :relay:installDist

FROM eclipse-temurin:17-jre-jammy
RUN useradd --create-home --uid 1000 relay
WORKDIR /app
COPY --from=build /src/relay/build/install/relay /app
RUN chown -R relay:relay /app
USER relay
EXPOSE 8080
ENV RELAY_DB=/data/relay.db
ENTRYPOINT ["/app/bin/relay"]
