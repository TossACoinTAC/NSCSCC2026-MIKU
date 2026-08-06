FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive
ARG SBT_VERSION=1.10.11
ARG SBT_SHA256=5034a64841b8a9cfb52a341e45b01df2b8c2ffaa87d8d2b0fe33c4cdcabd8f0c

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl git make g++ patch perl time zlib1g-dev liblz4-dev \
      openjdk-17-jdk-headless python3 python3-pip \
      verilator=5.020-1 yosys=0.33-5build2 \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fL -o /tmp/sbt.tgz \
      "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
    && echo "${SBT_SHA256}  /tmp/sbt.tgz" | sha256sum -c - \
    && tar -xzf /tmp/sbt.tgz -C /opt \
    && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt \
    && rm /tmp/sbt.tgz

COPY chiplab/toolchains/ /opt/nscscc/toolchains/

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH=/opt/nscscc/toolchains/loongson-gnu-toolchain-8.3-x86_64-loongarch32r-linux-gnusf-v2.0/bin:/opt/nscscc/toolchains/la32r-QEMU-x86_64-ubuntu-22.04:${PATH}
ENV COURSIER_CACHE=/cache/coursier
ENV SBT_OPTS="-Dsbt.supershell=false -Dsbt.global.base=/cache/sbt/global -Dsbt.boot.directory=/cache/sbt/boot -Dsbt.ivy.home=/cache/ivy"

WORKDIR /work
