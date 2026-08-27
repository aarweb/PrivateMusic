FROM eclipse-temurin:21-jdk-jammy AS java

FROM node:20-bookworm

ENV JAVA_HOME=/opt/java/openjdk \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    PATH=/opt/java/openjdk/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH

COPY --from=java /opt/java/openjdk /opt/java/openjdk

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       bash binutils ca-certificates curl git jq unzip zip \
    && rm -rf /var/lib/apt/lists/*

ARG CMDLINE_TOOLS_VERSION=15859902
ARG CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
RUN set -eux; \
    archive="commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"; \
    curl --fail --location --retry 3 \
      "https://dl.google.com/android/repository/${archive}" -o "/tmp/${archive}"; \
    echo "${CMDLINE_TOOLS_SHA256}  /tmp/${archive}" | sha256sum --check; \
    mkdir -p "$ANDROID_HOME/cmdline-tools/latest" /tmp/android-tools; \
    unzip -q "/tmp/${archive}" -d /tmp/android-tools; \
    mv /tmp/android-tools/cmdline-tools/* "$ANDROID_HOME/cmdline-tools/latest/"; \
    yes | sdkmanager --licenses >/dev/null || true; \
    sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"; \
    rm -rf "/tmp/${archive}" /tmp/android-tools

