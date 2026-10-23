# Build environment for Poweramp Navidrome. JDK, Android SDK and Gradle live in
# this image; nothing is installed on Windows. The user agreed to the Android SDK
# license on 2026-10-23.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        unzip wget ca-certificates openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/clt.zip \
    && unzip -q /tmp/clt.zip -d $ANDROID_HOME/cmdline-tools \
    && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
    && rm /tmp/clt.zip
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

RUN yes | sdkmanager --licenses >/dev/null
RUN sdkmanager "platforms;android-36" "platform-tools" >/dev/null
RUN sdkmanager "build-tools;36.0.0" >/dev/null

ENV GRADLE_VERSION=8.14.3
RUN wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -O /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && rm /tmp/gradle.zip
ENV PATH=/opt/gradle-${GRADLE_VERSION}/bin:$PATH

WORKDIR /work
