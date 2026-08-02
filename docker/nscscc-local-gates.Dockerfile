FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        git \
        python3 \
        verilator=5.020-1 \
        yosys=0.33-5build2 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work
