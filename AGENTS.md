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
3. `T2026144230012607/Readme.md` and the production GitLab CI template included
   by its unmodified `.gitlab-ci.yml` for the current submission layout, clock
   limits, fixed platform snapshot, timing policy, and hardware evaluation.
4. `nscscc-team-ci/` as a local mirror/reference; verify its revision against
   the production GitLab template before relying on it.
5. Executable tests and contracts in the active `nscscc-cpu` checkout.
6. `Info/CurrentDesign/` and historical material in `nscscc-cpu`; these are
   evidence and design references, not automatically the current truth.

The current production GitLab CI template commit
`6915882af5c8d3a0c856f570cb914920a3e5ff99` pins Chiplab
`c398d274812f164d387146fa7d8f612a4a1296d9` from the official `nscscc2026`
branch. Use the clean `chiplab/` worktree at that exact commit and
treat its SoC, clock, DDR, board, and Vivado project files as fixed platform
inputs. The older local `68c20a5...` worktree was removed after its reusable
toolchain snapshot was copied into the official `chiplab/toolchains/` layout
and hash-locked; it is not current CI evidence. Re-resolve the production
template before a submission if its protected `master` advances.

## Workspace Ownership

- `nscscc-cpu/`: active CPU development repository. CPU RTL is authored in
  SpinalHDL under `spinal/src/main/scala`; tests belong beside the corresponding
  package under `spinal/src/test/scala` or in `tests/`.
- `chiplab/`: fixed SoC integration, Verilator harness, FPGA
  platform logic, and platform IP from official CI snapshot `c398d27...`. Keep
  platform files unmodified during CPU development; populate only the
  student-owned `IP/myCPU` input through the reproducible synchronization flow.
- `T2026144230012607/`: official GitLab student submission repository. Never
  modify its protected `.gitlab-ci.yml` or competition Tcl files; develop and
  trigger CI from non-`master` submission branches.
- `nscscc-team-ci/`: local Gitee mirror/reference for the submission and
  evaluation contract. It may lag the production GitLab template; do not use it
  to override the template included by the official submission repository. Do
  not copy platform PLL XCI files into a student submission.
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

## Functional And Performance Builds

Chiplab function and performance bitstreams are different synthesized SoC
configurations, not merely the same CPU running different binaries. See
`docs/func-perf-build-contract.md` for the complete contract.

- The official c398 function build defines `RUN_FUNC_TEST`, uses the platform
  default CPU PLL (measured locally as 32.726797 MHz), enables function-test AXI
  random backpressure/address mapping, and pairs with LabAgent `func58` plus
  `nscscc_func/obj/main.bin`.
- The performance build defines `RUN_PERF_TEST`, regenerates the CPU PLL from
  `perf_clock.json` (100 MHz by default), uses the performance memory-delay and
  address behavior, and pairs with `perf20` plus
  `nscscc_perf/obj/allbench/inst_data.bin`.
- A cross-mode program may execute and provide diagnostic pass/hang evidence,
  but its timing, cycles, and score are not valid evidence for the selected
  profile. Never infer bitstream mode from the LabAgent profile alone; package
  metrics must state a matching `build_kind` and `actual_cpu_mhz`.
- Use function results for architectural/platform correctness and performance
  results for cycle, timing, frequency, and optimization decisions. Function
  slack cannot establish 100 MHz closure, and function-mode cycle counts cannot
  rank memory-sensitive performance changes.
- Do not build both locally for every RTL edit. Run the 100 MHz performance SoC
  for timing/performance iterations, add clean function build/board evidence at
  correctness milestones, and require both for a release or official CI.
- Do not run two local Vivado implementations concurrently on the current WSL
  host; one run already uses eight threads and about 9.5 GB peak aggregate PSS.
  Run them sequentially, while independent official Runner work may proceed in
  parallel.
- Do not use a performance DCP to implement function mode, or a function DCP to
  implement performance mode, for comparison or acceptance evidence. The macro,
  PLL, memory wrapper, placement, and routing differ; run clean flows across the
  boundary. Same-mode incremental implementation remains diagnostic until its
  reuse and timing reports satisfy the existing incremental-flow contract.

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

The only supported local Scala/simulation entry points are the root
`make cpu-test`, `make cpu-check`, `make cpu-generate`, `make sim`,
`make sim-prepare`, and `make sim-matrix` targets. Use
`make cpu-test CPU_TEST=<fully-qualified-suite>` for a focused Scala contract. Do not call a
system `sbt` or `verilator`, prepend an ad hoc `/tmp` directory, or switch tool
versions to work around a failure. Run `make toolchain-check` when diagnosing
the toolchain.

`tools/sbt-local` keeps the pinned SBT 1.10.11 launcher and dependency cache
under ignored `tools/.local/sbt/`, while using
`/tmp/nscscc-sbt-runtime` for the short-lived runtime socket. This avoids WSL
sandbox failures caused by writes to the user's home directory and avoids Unix
socket path-length failures from deeply nested workspace paths.

Host-side Spinal simulations use the validated Verilator 5.020 installation at
`tools/.local/verilator/5.020/` through `tools/verilator-local` and
`tools/bin/verilator`. The wrapper rejects version/hash drift and overrides an
external `VERILATOR_ROOT` with the matching workspace resource root;
`tools/sbt-local` and the root `sim-build` target
prepend this wrapper only for their child processes, and Chiplab receives the
matching include directory. Direct focused tests through
`tools/sbt-local` cannot silently select `/usr/local/bin/verilator`.

The host Verilator is a hash-locked 5.020 source build compatible with the WSL
Ubuntu 22.04 host. It is not the Ubuntu 24.04 package whose binary hashes are in
the RTL publication manifest. `make cpu-check` therefore runs Scala/Python on
the host and automatically runs metadata, port, lint, Yosys, and publication
checks inside `nscscc-local-gates:ubuntu24.04-v1`; never use a host executable
as a substitute for those exact locked gates. The root `cpu-check` target also
forces temporary files to `/tmp`, because Python tests requiring POSIX
directory-descriptor and atomic-rename behavior cannot run correctly through a
Windows-mounted `TEMP` inherited by WSL.

`tools/toolchain.lock` records the SBT launcher, Verilator executable, and
official-CI `glab` hashes. Use `tools/glab-local` (or the `tools/bin/glab` shim)
for GitLab CLI access; its ignored configuration remains alongside the pinned
binary under `tools/.local/glab/`.

Chiplab's LA32 GCC 8.3, NEMU DiffTest library, QEMU, and picolibc snapshot live
in the official-manual layout under ignored `chiplab/toolchains/`. Run
`make chiplab-toolchains` to validate their locked hashes. `make sim` depends
on that check and prepends the compiler/QEMU directories only for its child
processes. Do not depend on the removed 68c worktree or a user-global
cross-compiler `PATH`.
Keep the validated ignored `tools/.local/` cache across candidates. Restore it
from the known workspace cache if missing; do not rebuild or replace it during
an RTL debugging task. Refresh the lock only as a separate reviewed toolchain
change. The reusable locked-gate Docker image remains the only Yosys/lint gate
environment and is checked separately. None of these caches are submitted RTL
or verification results; still report SBT, Java, and Verilator versions for a
run.

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

The team board is attached to the Windows server at `10.20.213.157`. Use the
forced-command account `fpga-agent@10.20.213.157` from WSL for routine LabAgent
`queue`, `upload`, `status`, `logs`, `result`, and `artifact` operations. Use
`administrator@10.20.213.157` only for interactive server maintenance. The
server is a shared board endpoint, not a remote build machine. LabAgent source
and release development belongs in the root `fpga-lab-agent/` clone; the
installed runtime belongs under `D:\fpga-lab\app`. See
`docs/labagent-board-flow.md` for the deployment, authentication, and evidence
contract.

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
5. For every administrator session, start an interactive
   `ssh administrator@10.20.213.157` connection. Never try a non-interactive
   `ssh host command` invocation first. After the Windows prompt appears, enter
   `powershell` as the first command and perform all subsequent interactive work
   in PowerShell rather than `cmd.exe`.
   LabAgent's packaged internal wrappers may invoke `cmd.exe`; do not rewrite
   them merely to satisfy the interactive-shell rule.
6. Keep normal board API traffic in WSL with `ssh -T
   fpga-agent@10.20.213.157 boardctl ...`; this account is restricted by sshd to
   LabAgent's gateway and is not a general remote shell. If a WSL public key
   needs pairing, transfer only the existing `id_ed25519.pub` text through the
   administrator PowerShell session. Add it with duplicate detection and
   no-BOM UTF-8/ASCII encoding to the sshd-configured file
   `D:\fpga-lab\keys\fpga-agent_authorized_keys`. Do not use `ssh-copy-id`
   against the Windows server, and do not assume
   `C:\Users\fpga-agent\.ssh\authorized_keys` is active.
7. Use the remote Vivado 2023.2 executable at
   `E:\XilinX\Vivado\2023.2\bin\vivado.bat` only when board programming or a
   hardware-manager interaction requires it. Remote work is limited to steps
   that require the attached development board; preserve Tcl commands and logs.
8. The paired host Moonlight session may be used to view the server's camera and
   confirm the board's power, cable, indicator, and other visible physical
   state. Camera observations supplement but do not replace UART output,
   hardware test logs, timing/DRC reports, and artifact hashes.
9. Copy board logs and reports back through the `fpga-agent` artifact API;
   reserve administrator `scp` for release deployment, recovery, or another
   explicitly justified maintenance transfer. Record the exact observable
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
- `make cpu-test CPU_TEST=openla500.backend.OooRobSpec`: run one focused
  Scala suite through the locked workspace wrappers.
- `make cpu-check`: run the existing CPU local gates.
- `make gate-image`: build the reusable Ubuntu 24.04 locked RTL gate image.
- `make cpu-locked-gates`: regenerate RTL and run port/lint/Yosys/publication
  gates with exact tool hashes inside that image.
- `make cpu-generate`: generate and publish `mycpu_top.v` through SpinalHDL.
- `make sim RUN_SOFTWARE=func/func_lab19`: synchronize generated RTL and run
  Chiplab Verilator simulation.
- `make sim-prepare SIM_PROFILE=clean SIM_WORKLOADS=func/func_lab19`: create a
  clean c398 snapshot, generate RTL once, and compile a hash-locked model plus
  software inputs without modifying the active Chiplab worktree.
- `make sim-matrix SIM_PROFILE=clean SIM_WORKLOADS=func/func_lab19
  SIM_SEEDS=1,19557 SIM_LANES=2`: reuse the prepared read-only model while each
  workload/seed/time-limit runs with independent RAM, temporary files, logs,
  verdict, and resource measurements. Three lanes require the documented
  measured-memory opt-in; a process exit code of zero is not by itself a pass.
- `make wave`: open the default FST in host Surfer; override `WAVE=...` as needed.
- `make soc-impl`: create an isolated clean c398 performance snapshot under
  `build/chiplab-perf/` and implement the complete SoC at the default 100 MHz
  CPU target in Vivado 2023.2. Override `PERF_CPU_MHZ` only for an
  explicitly recorded frequency experiment; the Chiplab functional-clock
  default is not valid performance, scoring, or 100 MHz timing evidence.
- `make soc-func`: build the function-test SoC in an ignored Chiplab archive
  under `build/`. It performs clean synthesis and implementation for the func
  macro and platform-default PLL so its result remains directly comparable with
  the official clean CI flow.
- `make soc-archive`: archive the latest normal implementation with RTL,
  bitstream, probes, routed DCP, timing/DRC evidence, hashes, class, and timing
  status. Normal runs archive as `candidate`; only use
  `SOC_ARCHIVE_CLASS=stable` for a team-selected version with clean setup/hold
  timing. Stable and non-stable artifacts must remain visibly distinct.
- `make soc-impl-incremental`: run fresh RTL generation/synthesis followed by
  incremental implementation from the latest preserved routed checkpoint.
- `make soc-incremental-archive`: archive the current incremental comparison
  artifacts and their hashes without rerunning Vivado.

Long-running simulation or Vivado commands must not be presented as lightweight
checks. Preserve their logs and report whether they completed, failed, or were
not run.

Variable test-environment configuration and diagnostically useful generated logs
may remain between runs when they are clearly identified and do not change the
fixed platform semantics. Restore only temporary changes that would contaminate
a submission, alter the next result unintentionally, or modify shared server or
board state; do not perform cleanup solely to make a test workspace appear clean.

Test-only Chiplab harness fixes may also remain while they are in active use so
the retained source matches the compiled simulator. Record their diff and hash,
keep them out of synthesized CPU/SoC inputs, and label results as patched-harness
evidence. Final CI and submission claims must still be reproduced from a clean
checkout of the locked Chiplab commit.
