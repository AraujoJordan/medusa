# A CI-like Linux box for benchmark/concurrency.py: amd64, because the Android CLI publishes no Linux arm64 build.
# (On Apple Silicon this runs under emulation, so absolute times are slower than a real CI machine; the shape is what matters.)
FROM --platform=linux/amd64 eclipse-temurin:21-jdk
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates unzip procps python3 \
 && rm -rf /var/lib/apt/lists/*
ENV GRADLE_USER_HOME=/gradle-home ANDROID_HOME=/sdk ANDROID_SDK_ROOT=/sdk
WORKDIR /ws
