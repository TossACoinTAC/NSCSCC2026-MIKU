# Function And Performance Build Contract

## Why Two Bitstreams Exist

The test binary is loaded at runtime through JTAG, but the Chiplab test mode is
selected at synthesis time in `chip/soc_demo/nscscc-team/soc_config.vh`.
Consequently, changing only `program.bin` does not change the complete SoC into
the other official test configuration.

The production CI template builds two independent projects:

| Property | Function build | Performance build |
| --- | --- | --- |
| Chiplab macro | `RUN_FUNC_TEST` | `RUN_PERF_TEST` |
| CPU clock | platform default, 32.726797 MHz measured | generated from `perf_clock.json`, default 100 MHz |
| AXI behavior | random mask/backpressure | performance response-delay model |
| RAM address behavior | function-test remapping for selected regions | address passed through |
| LabAgent profile | `func58` | `perf20` |
| Program | `nscscc_func/obj/main.bin` | `nscscc_perf/obj/allbench/inst_data.bin` |
| Primary evidence | correctness and robustness | cycles, frequency, timing, and score |

The CPU RTL may be identical, but the platform wrapper, PLL, constraints,
placement, routing, and bitstream are not. Official CI therefore runs clean
`gen_func` and `gen_perf` jobs and publishes separate `.bit/.ltx` artifacts.

## Cross-Mode Runs

A function program can often execute on a performance bitstream, and a
performance program can often execute on a function bitstream. The JTAG loader
can write either image, the CPU is the same, and common program address ranges
may be unaffected by function-mode remapping. Such a run can identify a hang,
exercise an instruction path, or show that a fault survives a different AXI
stress pattern.

It is diagnostic only. The CPU-to-system clock ratio, memory latency and
backpressure, address behavior, timing margin, and scoring frequency differ.
Do not use cross-mode cycle counts, timing, benchmark score, or a profile name as
official evidence for the underlying hardware mode.

The 2026-08-02 jobs `20260802-201006-ac3bf081` and
`20260802-201535-7b3d98a4` illustrate the failure mode. Both packages selected
LabAgent `func58` and loaded the function binary, but their bitstream SHA256
`5e21422e...` is the documented `2301dde` 100 MHz `perf20` implementation. The
observed F0 behavior remains a cross-mode diagnostic, not a clean `gen_func`
result.

## Local Build Policy

Use `make soc-impl` for the default 100 MHz performance implementation. Use
`make soc-func` for an isolated clean function implementation. The function
target extracts exact c398 sources under ignored `build/chiplab-func/`, applies
only the official function macro selection there, performs clean synthesis and
implementation, and leaves the fixed `chiplab/` checkout untouched.

Do not synthesize both modes after every source edit. Use the cheapest decisive
verification first:

1. Run Scala, generated-RTL, Verilator, and focused architectural tests.
2. For timing/performance work, run the 100 MHz performance SoC.
3. At Linux, cache/AXI, interrupt, reset, or release milestones, run clean
   function integration and board validation.
4. Before release or official submission, require both function and performance
   builds for the exact RTL candidate.

Run the two local Vivado builds sequentially. A single implementation consumes
all eight configured Vivado threads and has reached about 9.5 GB aggregate PSS
on this host; concurrent runs risk WSL swapping and less reproducible results.
Official Runner builds may proceed independently while one local build runs.

## DCP And Archive Policy

Do not use a DCP across function/performance modes for comparison or acceptance.
Even when Vivado treats it only as an incremental placement reference, it can
change placement/routing and produce timing evidence that is not directly
comparable with the official clean flow. A same-mode DCP may still be used under
the documented incremental implementation contract and must retain reuse and
timing reports.

Every completed local implementation is archived with an explicit class and
timing status. The default class is `candidate`; only a team-selected result
with nonnegative setup and hold slack may use `SOC_ARCHIVE_CLASS=stable`.
Function and performance archive names and manifests remain distinct.

Before LabAgent accepts a package, its hashed `vivado-metrics.txt` must identify
`build_kind=func` or `build_kind=perf`, report a positive
`actual_cpu_mhz`, and match the configured profile mode. A profile name alone is
not proof of the bitstream configuration.
