# NSCSCC 2026 Team Development Contract

## Mission

This workspace targets the 2026 NSCSCC LoongArch team competition on the
official Artix-7 platform. The functional target is the final-round maximum:
boot a soft-float LA32R Linux system and complete the required operations.
After correctness, full-SoC implementation, and timing closure are preserved,
optimize both benchmark cycle count and achievable CPU frequency.

Local development runs in WSL2 Ubuntu. Use Verilator for architectural and SoC
simulation, Vivado 2023.2 for synthesis/implementation, and the Windows Surfer
binary at `D:/Surfer/surfer.exe` (WSL path `/mnt/d/Surfer/surfer.exe`) for FST or
VCD inspection. This WSL host is not directly connected to the competition
board, but the board is reachable through the team's remote Windows server. Do
not describe local simulation, standalone synthesis, or bitstream generation as
board validation; hand candidates and reproducible artifacts to the team board
flow after local gates pass.

## Source Of Truth

When sources disagree, use this precedence:

1. Current official competition notices and the PDFs in `Info/References/`.
2. `docs/linux-system-requirements.md` for this project's Linux completion
   contract. It intentionally exceeds the competition's baseline instruction
   subset.
3. `nscscc-team-ci/` for the official submission layout, clock limits, fixed
   platform snapshot, full-SoC timing gates, and hardware evaluation behavior.
4. Executable tests and contracts in the active `nscscc-cpu` checkout.
5. `Info/CurrentDesign/` and historical material in `nscscc-cpu`; these are
   evidence and design references, not automatically the current truth.

The `chiplab` integration and evaluation baseline is the exact official CI
snapshot `68c20a539e2be8a05300e714296f5fda8373ee80`, as named by
`nscscc-team-ci/child.yml`. Keep the checkout at that commit and treat its SoC,
clock, DDR, board, and Vivado project files as fixed platform inputs. CPU
candidates must be verified against a clean copy of this exact snapshot; another
Chiplab revision or a descendant commit is not equivalent evidence.

## Workspace Ownership

- `nscscc-cpu/`: active CPU development repository. CPU RTL is authored in
  SpinalHDL under `spinal/src/main/scala`; tests belong beside the corresponding
  package under `spinal/src/test/scala` or in `tests/`.
- `chiplab/`: fixed SoC integration, Verilator harness, FPGA platform logic, and
  platform IP from official CI snapshot `68c20a5...`. Keep platform files
  unmodified during CPU development; populate only the student-owned `IP/myCPU`
  input through the reproducible synchronization flow.
- `nscscc-team-ci/`: authoritative submission and evaluation contract. Do not
  copy platform PLL XCI files into a student submission.
- `fpga-lab-agent/`: independent source and release repository for the team's
  serialized board service. Develop and test it locally, then push reviewed
  compatible updates to its `main`; do not copy it into the CPU repository or
  treat it as part of the submitted CPU RTL.
- `Info/References/`: official manuals, competition rules, schematics, and pin
  data. Treat binary references as read-only.
- `Info/CurrentDesign/`: snapshots and audits of earlier/current candidates.
  Verify every claim against the checkout before using it.
- `docs/`: workspace-level requirements and durable decisions.

Nested repositories have independent Git state. Never commit, reset, clean, or
switch one merely because a command is being run from the workspace root.

## Branch And Change Discipline

The active CPU development line is `dev/ECHO`; keep feature development there
unless the team explicitly selects another branch.
Before CPU edits, confirm the checkout and preserve all existing worktree state.
Do not develop new CPU features directly on `main` by assumption.

Before changing files:

1. Read this file and any nearer `AGENTS.md` that exists in the target repo.
2. Inspect `git status`, the current branch, and the relevant design contracts.
3. Preserve all pre-existing user changes. Never restore, discard, or rewrite
   them unless explicitly asked.
4. State the hypothesis and the observable correctness/performance metric.

Keep changes attributable. Do not combine a microarchitecture experiment,
platform rewrite, and build-system refactor in one candidate.

## RTL And IP Rules

- SpinalHDL is the source for CPU behavior. Generate Verilog through the checked
  build flow; never hand-edit generated `rtl/mycpu_top.v` or packaged RTL.
- The published `core_top` interface is a platform contract. Port widths, reset
  behavior, AXI semantics, debug/commit signals, and `TLBNUM` must remain
  compatible unless the platform contract is intentionally updated end to end.
- Xilinx or third-party IP is conditional, not a blanket allowance for the
  Vivado catalog. Use it only inside the student CPU when it supports
  `xc7a200tfbg676-2`, is available under the official Runner's licenses, does not
  replace the core CPU design, and can be rebuilt from submitted sources with
  Vivado 2023.2.
- An IP present in Chiplab is a compatibility candidate, not automatic approval
  for reuse. Do not submit or replace platform-owned clock, DDR, AXI, JTAG, or
  board IP; in particular, never submit or replace platform `clk_pll.xci`.
- Preserve the XCI/XCIX, Tcl, configuration, wrappers, and other inputs needed to
  regenerate CPU-local IP. Provide a Verilator-compatible behavioral path where
  vendor simulation products cannot be used, and verify the synthesized IP in
  the complete Vivado SoC.
- Record every third-party IP or borrowed source, its purpose, origin, and
  license in the design report.
- Treat uncached MMIO, AXI backpressure, reset/CDC, precise exceptions, dirty
  cache data, and committed-store visibility as architectural behavior, not
  integration details.

## Functional Priority

Linux completion has priority over speculative performance work. The current
design audit identifies at least these items as requiring proof or repair:

- `DBAR`/`IBAR` completion must include older stores and pending cache/AXI work.
- `CACOP` must not lose dirty data and must implement valid Index/Hit semantics.
- `CPUCFG` must report the implemented cache, TLB, timer, and ISA capabilities.
- LL/SC reservation lifetime and granularity must match the supported platform.
- MMU/TLB exceptions, privilege checks, interrupts, uncached ordering, and
  recovery under cache misses/AXI backpressure need directed and randomized
  evidence.

Do not infer Linux readiness from static decode coverage. The acceptance path is
bootloader, kernel entry, MMU/user-mode operation, interactive shell, and the
required file/process/memory/atomic/timer/I/O operations.

## Performance Strategy

The final performance score rewards CPU frequency and benchmark cycle count
equally. Optimize their product, not IPC in isolation. A cycle reduction that
breaks full-SoC timing is not an improvement; a higher requested PLL frequency
without nonnegative setup and hold slack is not a valid result.

Prefer measured, independently testable improvements such as predictor accuracy,
front-end bubbles, multiply/divide latency or throughput, queue scheduling,
cache hit/miss service, critical-word return, and AXI utilization. For every
experiment record:

- baseline and candidate commit/configuration;
- functional results and random seeds;
- per-benchmark cycle counts, not only a geometric mean;
- requested and actual clocks;
- full-SoC setup/hold WNS/TNS and failing endpoints;
- RTL/package/bitstream hashes when artifacts are retained.

Never carry timing or performance evidence across an RTL change.

## Verification Ladder

Use the cheapest decisive checks first, then broaden with risk:

1. Spinal elaboration/compile and focused unit/contract tests.
2. Generated-RTL port, publication, lint, and structural checks.
3. Verilator directed tests and multi-seed architectural DiffTest, including
   random AXI delay/backpressure when relevant.
4. Chiplab functional and performance software runs. Check the architectural
   result, DiffTest status, terminal PC/syscall, timeout, and UART output.
5. Standalone CPU synthesis for early timing/resource feedback.
6. Complete `nscscc-team` SoC synthesis, implementation, DRC, setup/hold timing,
   and bitstream generation with Vivado 2023.2.
7. Validated post-implementation simulation when it can exercise the changed
   behavior. Document the timing model, test image, and observed endpoint.
8. Team-board/official CI validation after local gates pass. It may run on a
   teammate's host, the remote team Windows server, or a hardware Runner; local
   WSL-only results cannot substitute for it.

For Linux-impacting changes, add directed privileged/MMU/cache/atomic tests and
retain early-boot UART logs. A passing performance suite alone is insufficient.

## Locked RTL Gate Container

The reusable local image `nscscc-local-gates:ubuntu24.04-v1` provides Ubuntu
24.04, Git, Python 3, Verilator 5.020, and Yosys 0.33 for the generated-RTL gates.
Build it with `make gate-image` from
`docker/nscscc-local-gates.Dockerfile`, then run `make cpu-locked-gates`.
The gate scripts still verify the exact tool binary hashes against
`nscscc-cpu/reference/manifest.lock`; a matching image tag alone is not
evidence that the contents are correct.

Keep a successfully validated image for reuse across candidates. Rebuild and
revalidate it when the Dockerfile, Ubuntu base, or locked tool versions/hashes
change. The workspace is bind-mounted at `/work`, and disposable reports belong
under `nscscc-cpu/build/core_top/locked-gates/`; do not place source changes or
submission artifacts inside the image. This image covers port, lint, Yosys, and
publication checks only. It does not contain Vivado, simulate the complete SoC,
or substitute for implementation, post-implementation simulation, or board
validation.

## Local Scala Toolchain

Use `tools/sbt-local` through the root `make cpu-check` and
`make cpu-generate` targets. The wrapper keeps the SBT launcher and dependency
cache under ignored `build/tool-cache/sbt/`, while using
`/tmp/nscscc-sbt-runtime` for the short-lived runtime socket. This avoids WSL
sandbox failures caused by writes to the user's home directory and avoids Unix
socket path-length failures from deeply nested workspace paths.

Keep a validated workspace cache for reuse across candidates. It is a local
build acceleration resource, not a submitted source or verification result.
Refresh it when the SBT launcher or declared Scala dependencies change, and
still report the actual SBT/Java versions used for each verification run.

## Incremental Vivado Flow

`make soc-impl-incremental` preserves the latest routed SoC checkpoint outside
the disposable Vivado project, regenerates and synchronizes RTL from SpinalHDL,
performs normal synthesis, and uses the checkpoint only as an incremental
implementation reference. It does not reuse an old synthesized CPU as the new
candidate and does not weaken the complete-SoC timing contract.

The default `SOC_TIMING_POLICY=strict` rejects negative setup or hold slack.
`SOC_TIMING_POLICY=report` may retain a routed comparison bitstream only when
the requested/actual clocks are verified and the Vivado failure is specifically
negative slack. Such a bitstream is diagnostic evidence, not an acceptance or
board candidate. Review both incremental-reuse reports because high cell match
does not imply equivalent placement or routing reuse, and incremental results
may be worse than a clean implementation.

Successful incremental runs archive RTL, bitstream, routed DCP, timing, DRC,
reuse reports, reference metadata, and SHA-256 hashes under ignored
`Stable_Backup/cpu_<commit>_chiplab_<commit>_incremental_<clock>mhz/`. The DCP
does not embed the CPU source commit or RTL hash, so the reference manifest's
workspace values describe staging context only; never claim stronger provenance
without an independently recorded build manifest.

## Team Board Flow

The team board is attached to the Windows server at
`administrator@10.20.213.157`. The server is a shared board endpoint, not a
remote build machine. LabAgent source and release development belongs in the
root `fpga-lab-agent/` clone; the installed runtime belongs under
`D:\fpga-lab\app`. See `docs/labagent-board-flow.md` for the deployment and
evidence contract.

After local gates pass:

1. Record the candidate CPU commit/configuration and hashes of generated RTL,
   submitted package, software image, and bitstream inputs.
2. Complete synthesis, implementation, DRC/timing checks, and bitstream
   generation with the local Vivado 2023.2 flow. Do not move these build stages
   to the remote server.
3. Package the resulting checked bitstream, probes, test image, and Vivado
   metrics as a LabAgent `.fpgajob`. Local LabAgent execution is limited to
   client-side pre-board work such as `pack`/`build-package`, package integrity
   checks, upload, status, and artifact fetch commands. Run `worker`, board
   programming, VIO tests, camera capture, and other hardware interaction only
   on the remote server. Transfer compiled artifacts only; the server must not
   clone Chiplab or synthesize the design. The package's locked Chiplab commit
   is metadata used to validate the platform and select the compatible board
   reset/VIO protocol.
4. Before starting a hardware job, coordinate with the team and inspect the
   LabAgent queue. A missing JTAG device or disappearing debug core may mean a
   teammate has reprogrammed the shared board, including for Linux testing. It
   is an infrastructure/conflict result, not evidence that the DUT passed or
   failed. Do not probe, reprogram, or retry until ownership is confirmed.
5. For administrator maintenance, connect with
   `ssh administrator@10.20.213.157`, start `powershell` immediately, and
   perform subsequent interactive work in PowerShell rather than `cmd.exe`.
   LabAgent's packaged internal wrappers may invoke `cmd.exe`; do not rewrite
   them merely to satisfy the interactive-shell rule.
6. Use the remote Vivado 2023.2 executable at
   `E:\XilinX\Vivado\2023.2\bin\vivado.bat` only when board programming or a
   hardware-manager interaction requires it. Remote work is limited to steps
   that require the attached development board; preserve Tcl commands and logs.
7. The paired host Moonlight session may be used to view the server's camera and
   confirm the board's power, cable, indicator, and other visible physical
   state. Camera observations supplement but do not replace UART output,
   hardware test logs, timing/DRC reports, and artifact hashes.
8. Copy board logs and reports back with `scp`. Record the exact observable
   milestone, UART/terminal output, test result, remote job/run identifier, and
   hashes before claiming board validation.

Do not overwrite a known-good remote candidate or reprogram the board when its
target, power, cable state, or currently running team job is uncertain. Inspect
the remote state first and coordinate with the team when a run could interfere
with another user. Restore test-only server changes after the test. A validated
LabAgent release, reproducible Git bundle, compiled candidate package, and
stable backup may remain for reuse when their version and hashes are recorded.

## Completion And Claims

A change is complete only when source, generated RTL, tests, integration, and
evidence agree. Use precise claims:

- `elaborates`, `unit tests pass`, `Verilator DiffTest passes`;
- `standalone timing closes` or `complete-SoC timing closes`;
- `post-implementation simulation passes`;
- `Linux boots to <observable milestone>`;
- `official board/CI passes` only with actual official evidence.

At minimum, complete-SoC acceptance requires Vivado 2023.2 implementation and
bitstream success, zero DRC errors, nonnegative setup and hold slack, and clocks
matching the platform/CI contract. Linux acceptance is defined in
`docs/linux-system-requirements.md`.

## Root Commands

Run `make help` at the workspace root. The root Makefile is a thin orchestrator;
the nested repositories remain authoritative for implementation details.

- `make doctor`: check WSL/tool paths/repository revisions.
- `make status`: show root and nested Git state without changing it.
- `make cpu-check`: run the existing CPU local gates.
- `make gate-image`: build the reusable Ubuntu 24.04 locked RTL gate image.
- `make cpu-locked-gates`: regenerate RTL and run port/lint/Yosys/publication
  gates with exact tool hashes inside that image.
- `make cpu-generate`: generate and publish `mycpu_top.v` through SpinalHDL.
- `make sim RUN_SOFTWARE=func/func_lab19`: synchronize generated RTL and run
  Chiplab Verilator simulation.
- `make wave`: open the default FST in host Surfer; override `WAVE=...` as needed.
- `make soc-impl`: create and implement the local complete SoC in Vivado 2023.2.
- `make soc-impl-incremental`: run fresh RTL generation/synthesis followed by
  incremental implementation from the latest preserved routed checkpoint.
- `make soc-incremental-archive`: archive the current incremental comparison
  artifacts and their hashes without rerunning Vivado.

Long-running simulation or Vivado commands must not be presented as lightweight
checks. Preserve their logs and report whether they completed, failed, or were
not run.
