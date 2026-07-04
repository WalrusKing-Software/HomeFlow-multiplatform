# syntax=docker/dockerfile:1

# ── Stage: build — produce the :server distribution with Gradle ──────────────
# NOTE: :server depends on :core, and :core is a Kotlin Multiplatform module with
# an Android target. Gradle must therefore *configure* the Android plugin even to
# build the JVM server, which needs the Android SDK present. We install the
# command-line SDK here so a plain JDK image can build the server. (Building only
# the server bundle still never compiles Android code — it just needs the SDK to
# resolve the build model.)
FROM eclipse-temurin:21-jdk AS build

ENV ANDROID_HOME=/opt/android-sdk \
    DEBIAN_FRONTEND=noninteractive
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

# Android command-line tools + the platform/build-tools matching libs.versions.toml
# (compileSdk 36). Done before COPY so the layer caches across source changes.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdtools.zip \
    && unzip -q /tmp/cmdtools.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm /tmp/cmdtools.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" > /dev/null

WORKDIR /app
COPY . .
# Config cache gives no benefit for a one-off container build; disable it to avoid
# any plugin incompatibility surprises.
RUN ./gradlew :server:installDist --no-daemon --no-configuration-cache

# ── Stage: runtime — minimal JRE, non-root ───────────────────────────────────
FROM eclipse-temurin:21-jre AS production
WORKDIR /app
COPY --from=build /app/server/build/install/server ./
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser
USER appuser
EXPOSE 8080
# Migrations are NOT run from this image (CLAUDE.md / DEPLOYMENT.md §5).
CMD ["./bin/server"]
