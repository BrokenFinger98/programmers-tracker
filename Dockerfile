# programmers-tracker — a resident local process, packaged.
#
# Two stages so the runtime image carries neither the JDK nor the Gradle cache. Nothing
# user-specific is baked in: no cookie, no /watch token, no host path, no port choice.
# The only paths this file decides are the container's own mount points, which are the
# image's contract with compose rather than anybody's configuration.
#
# See docs/bootstrap.md to run it, and the ADR 2026-08-06-container-network-posture for
# why the bind address inside this container is not the control it looks like.

# --- build -------------------------------------------------------------------
# JVM 25 per the ADR 2026-08-05-backend-stack; the toolchain in build.gradle.kts
# pins the same number, so a drift here fails the build rather than downgrading it.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /src

# The wrapper and the dependency declarations first, on their own layer. Editing a `.kt`
# then reuses the resolved dependencies instead of downloading Spring Boot again.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null

COPY src ./src

# `bootJar`, not `build`: the gates run in CI on three operating systems and repeating
# them here would only prove the same thing more slowly. The image build's job is to
# produce the artifact.
# The jar stamps itself with its build time, and with the commit when whoever builds
# supplies one. `.dockerignore` excludes `.git/` on purpose — it carries every credential
# ever committed and then removed — so this build cannot read the commit for itself.
ARG SOURCE_COMMIT=unknown
ENV SOURCE_COMMIT=$SOURCE_COMMIT
RUN ./gradlew --no-daemon bootJar && mv build/libs/*.jar /tmp/tracker.jar

# --- runtime -----------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

# `git` is not optional: the record repository's history is written by shelling out to the
# git CLI (CommandLineGitSync), so an image without it records to disk and silently never
# commits. `curl` backs the HEALTHCHECK below.
#
# `openssh-client` shipped from #56 to #258, while SSH was the push credential. Pushes now
# authenticate over HTTPS through the GitHub token's credential store, so it retired with
# the deploy-key path — an image with no use for a tool should not carry it.
RUN apt-get update \
    && apt-get install --no-install-recommends -y git curl \
    && rm -rf /var/lib/apt/lists/*

# The record repository arrives as a bind mount, so its owner is the host user and almost
# never this container's uid — git then refuses every command with "detected dubious
# ownership". Declared in the SYSTEM config on purpose: a user who mounts their own
# ~/.gitconfig would shadow a --global entry and get that refusal back.
RUN git config --system --add safe.directory /records

# Non-root, and the uid is overridable from compose because a bind mount on Linux keeps
# the host's ownership — see `user:` in compose.yaml.
#
# The Ubuntu base ships a stock `ubuntu` account that already holds uid 1000, so it has to
# go first. Claiming 1000 is the point rather than an accident: it is the first uid a Linux
# desktop hands out, which makes the common bind-mount case work with no override at all.
# Chained without `|| true` on purpose — if a future base image stops shipping that
# account, this should fail visibly instead of quietly moving the app to another uid.
RUN userdel --remove ubuntu && useradd --create-home --uid 1000 tracker

# Stated explicitly because compose may run this as a numeric uid to match a bind mount's
# owner, and Docker does not consult /etc/passwd for a numeric user — HOME would be `/`,
# and the git credential store the server writes under the records' .ps/ would go unread.
ENV HOME=/home/tracker

WORKDIR /app
COPY --from=build --chown=tracker:tracker /tmp/tracker.jar ./tracker.jar

# `/app/.ps` is process state (raw frames, timers, the generated /watch token, the backup
# marker) and `/records` is the user's data. Both are mount points, and both must be
# mounted — this line only guarantees they exist and are writable when they are not.
RUN mkdir -p /app/.ps /records && chown tracker:tracker /app/.ps /records
USER tracker

# The mount point above, named so a bare `docker run` behaves like compose does. This is
# the container's own filesystem layout, not a default for where a user keeps records —
# the application default (`~/ps-records`) is deliberately left alone for native runs.
ENV TRACKER_RECORD_REPO=/records

# Shell form so TRACKER_PORT is read at start rather than frozen at build time. A 404 from
# `/` is a pass: it proves the server is listening and speaking HTTP. Deliberately not
# POST /watch, which would log a refused-unauthorized warning every thirty seconds forever.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -s -o /dev/null "http://127.0.0.1:${TRACKER_PORT:-8080}/" || exit 1

ENTRYPOINT ["java", "-jar", "/app/tracker.jar"]
