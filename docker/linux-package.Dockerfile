FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ARG GRADLE_VERSION=9.8.0
ENV PATH="$JAVA_HOME/bin:/opt/gradle/bin:$PATH"

RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk openjdk-21-jdk build-essential cmake ninja-build git curl ca-certificates \
    unzip zip tar xz-utils libx11-dev libxext-dev libxi-dev libxrender-dev \
    libxtst-dev libxrandr-dev libfontconfig1 libfreetype6 libasound2t64 \
    libgl1-mesa-dev libvulkan-dev vulkan-tools \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && mv "/opt/gradle-${GRADLE_VERSION}" /opt/gradle \
    && rm /tmp/gradle.zip \
    && gradle --version

WORKDIR /work
