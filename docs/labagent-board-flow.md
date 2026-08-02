# LabAgent Board Flow

## Scope

LabAgent serializes access to the team's shared NSCSCC board. It is deliberately
outside the CPU submission and does not move synthesis, implementation, timing,
DRC, or bitstream generation to the server. Those stages remain local Vivado
2023.2 work. The server validates an already compiled `.fpgajob`, programs the
board, loads the test image, runs the selected hardware profile, and returns
structured evidence.

The local source of truth is the independent `fpga-lab-agent/` repository with
remote `git@github.com:negativegluon/fpga-lab-agent.git`. Compatible changes may
be pushed directly to `main` after its Go, Tcl, self-test, package hash, and
release checks pass. Keep commits focused and use conventional messages.

## Platform Selection

The board server does not require a Chiplab checkout. Every package records the
locked platform commit; LabAgent validates that metadata and maps it to a board
protocol:

- legacy Chiplab packages use `legacy-vio-v1`;
- official commit `68c20a539e2be8a05300e714296f5fda8373ee80` uses
  `nscscc-seeded-reset-v1`.

The official protocol holds CPU/confreg reset while DDR is initialized and the
test image is loaded, then runs functional tests with deterministic AXI seeds
`F0`, `FF`, and `A5`. This compatibility behavior is why a package carries the
Chiplab commit; it does not authorize the server to rebuild the SoC.

## Release And Deployment

Before deploying a LabAgent update:

1. Confirm the local repository is clean, based on current `main`, and all
   release checks pass. Verify `package-manifest.json`, `SHA256SUMS.txt`, the
   executable version, and the executable SHA256.
2. Push the reviewed commits to GitHub `main`.
3. Create a Git bundle from the pushed commit and transfer the bundle plus the
   release runtime with `scp`. A bundle keeps server source reproducible without
   requiring GitHub credentials.
4. Confirm no hardware job or teammate test is active. Connect as
   `administrator@10.20.213.157`, start `powershell` immediately, and keep
   interactive maintenance in PowerShell.
5. Back up `D:\fpga-lab\app`, `D:\fpga-lab\config`, and the current source
   revision before replacement. Synchronize `D:\fpga-lab\source\fpga-lab-agent`
   from the bundle and deploy only the release runtime needed by the service.
6. Run the offline self-test/doctor before touching the board. Run hardware
   calibration or a known package only after the board is confirmed available.

Rollback means stopping new work, waiting for the current worker command to
reach a boundary, restoring the timestamped application/config backup, running
the offline doctor, and only then resuming the worker. Do not delete prior
candidate packages or evidence as part of an application rollback.

## Shared-Board Safety

The queue mutex serializes LabAgent workers, but it cannot serialize a teammate
using Vivado or the board outside LabAgent. Coordinate board ownership before
programming. In particular:

- `No devices detected`, a missing target, or a debug core disappearing during
  a run can be caused by another user reprogramming or resetting the board;
- classify that observation as `infra_error` or external contention unless
  independent evidence establishes a physical fault;
- do not infer a DUT failure from partial VIO progress before the disconnect;
- do not retry while a teammate may be running Linux or another hardware test.

Use Moonlight and the server camera only to inspect visible power, cable, and
indicator state. Camera evidence never substitutes for a terminal LabAgent
result and verified artifacts.

## Acceptance Evidence

A board claim requires a terminal `passed` result plus matching package and
artifact hashes. Preserve at least the job ID, source commit, package SHA256,
board protocol, exact seed/profile, timestamps, `programming-summary.txt`,
`board-summary.txt`, VIO CSV, worker log, and Vivado metrics. An `infra_error`,
missing summary, partial CSV, or a log line containing `PASS` is not a hardware
pass.

The LabAgent release installed on 2026-08-03 was source commit
`243ef82e0f706f925983a324f2d9ad2fbc241984` (version 0.2.4), with executable
SHA256 `5eb2ecae4aa7c4bc24b34af7b95a6ce6f9da6bca41067c122f2a1c6657a35a5c`.
The timestamped pre-update backup is
`D:\fpga-lab\backup\before-seeded-reset-20260803-040401`.

The first official-protocol validation job,
`20260802-201006-ac3bf081`, programmed successfully, observed DDR ready, and
started seed `F0`, then lost the Vivado debug core after partial progress. The
single controlled retry, `20260802-201535-7b3d98a4`, found no JTAG device while
another team test may have owned the shared board. Both are `infra_error`; they
prove the new protocol was selected and exercised, but do not establish a
func58 pass or failure. A contemporaneous Moonlight camera observation showed
the board indicators and attached display powered, which supports possible
shared use but does not identify the running workload or JTAG owner. Revalidate
after explicit board coordination.
