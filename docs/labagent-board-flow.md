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
  `nscscc-seeded-reset-v1`;
- current production commit `c398d274812f164d387146fa7d8f612a4a1296d9` uses
  `nscscc-system-reset-v1`.

The historical seeded-reset protocol holds CPU/confreg reset while DDR is
initialized and the test image is loaded, then runs functional tests with
deterministic AXI seeds `F0`, `FF`, and `A5`. The production system-reset
protocol follows the current Chiplab reset topology: it performs a full-system
reset, waits for DDR readiness, and repeats that sequence for each functional
seed. Performance tests run twice and retain the slower valid result. This
compatibility behavior is why a package carries the Chiplab commit; it does not
authorize the server to rebuild the SoC.

## Execution Boundary

The local LabAgent executable may perform client-side work that does not access
hardware: build or pack a `.fpgajob`, verify its inputs and hashes, upload it to
the service, query status, and fetch evidence. The remote installed runtime is
the only instance allowed to run a worker, invoke Vivado Hardware Manager,
program the FPGA, drive VIO/reset protocols, or capture the board camera. Local
Vivado remains responsible for synthesis, implementation, timing/DRC reports,
and bitstream generation; the remote server never rebuilds a candidate.

## Client Authentication And Access

Routine board operations originate in WSL through the restricted SSH account:

```sh
ssh -T fpga-agent@10.20.213.157 boardctl maintenance status
ssh -T fpga-agent@10.20.213.157 boardctl queue
ssh -T fpga-agent@10.20.213.157 boardctl status JOB_ID
ssh -T fpga-agent@10.20.213.157 boardctl result JOB_ID
```

The account is bound by sshd `ForceCommand` to LabAgent's gateway; it is the
normal upload/query/evidence transport, not a general Windows shell. Stream a
validated package and fetch a terminal artifact directly from WSL as follows:

```sh
ssh -T fpga-agent@10.20.213.157 boardctl upload \
  --idempotency KEY --sha256 SHA256 --bytes BYTES < PACKAGE.fpgajob
ssh -T fpga-agent@10.20.213.157 boardctl artifact JOB_ID ARTIFACT > OUTPUT
```

Use `administrator@10.20.213.157` only when maintenance requires an unrestricted
Windows session. Start that SSH connection interactively, enter `powershell` as
the first remote command, and keep subsequent maintenance in PowerShell.

The active authorized-key file for `fpga-agent`, as selected by the server's
`Match User fpga-agent` sshd rule, is
`D:\fpga-lab\keys\fpga-agent_authorized_keys`. To pair a WSL client, inspect its
existing `~/.ssh/id_ed25519.pub`, pass only that public line through the
administrator PowerShell session, and append it with duplicate detection using
no-BOM UTF-8 or ASCII. Do not use `ssh-copy-id` against this Windows server: its
shell/encoding path can corrupt the key file. The default
`C:\Users\fpga-agent\.ssh\authorized_keys` is not the active file for this
account. Never transfer a private key.

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

The LabAgent release installed on 2026-08-04 is source commit
`f8a3e3f9a45ed675a88fcdf460227ce8bd6f6c35` (version 0.3.2), with executable
SHA256 `bde76c72a1c6a68f2ec4f044043c4bf905c67be5c3e726fe90d4ee44efa355a1`.
Its offline Windows tests, package hash verification, installed self-test, and
server doctor all passed. The release ZIP SHA256 is
`b842f035d9e1c8c099fed3b849f9d397f74777477cac9d58896f3ff1ecfba848`;
the deployed ZIP and complete-history Git bundle remain under
`D:\fpga-lab\releases`. The immediate rollback backup is
`D:\fpga-lab\backups\app-20260804-063524`. The earlier 0.3.1 and system-reset
backups remain historical.

Version 0.3.2 accepts the official c398 func metrics form with an empty
`requested_cpu_mhz=` field, which means the platform-default PLL is in use. It
does not relax perf metadata or any other empty metrics field. The first real
server package exercising this rule retained the unmodified metrics and was
accepted as job `20260803-223955-1ceb232c`.

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

A later package audit established that both jobs used package SHA256
`f254f892...` with bitstream SHA256 `5e21422e...`. That bitstream is the
`2301dde` 100 MHz `perf20` implementation recorded in the CPU iteration, while
the package profile and program were `func58`. The observations remain useful
cross-mode diagnostics, but they are not official clean `gen_func` evidence.
Do not infer the synthesized Chiplab macro or clock from the LabAgent profile;
future packaging must validate the bit build kind and actual clock against the
selected board profile.

The first terminal production-system-reset validation is job
`20260803-132008-97f48faa`, using CPU commit
`8594150feb652bd3ef137995858cb9eb5f884580`, Chiplab
`c398d274812f164d387146fa7d8f612a4a1296d9`, and package SHA256
`93378d04f3fa8519ecc03c63de168f556e1366403185a4547f9a8c191bad2b6d`.
It passed `func58` at the package's actual 32.726797 MHz CPU clock. Seeds `F0`,
`FF`, and `A5` each reached `3A00003A` with both pass indicators asserted. This
closes the earlier n49/`0x30000030` functional-board symptom for that candidate;
it is not 100 MHz performance or Linux-boot evidence.

The current Linux-semantics candidate also passed the same production flow in
job `20260803-223955-1ceb232c`. Its package source commit is
`e26ccfa823e81c41f1191365fdcf8f89395b1248`, generated RTL SHA256 is
`137657aa0c594334568cc386571d13aa9cdc828c8fc45c56ed421be15912c209`, and
package SHA256 is
`4d3ebd3754f165d0af4c2e37e33e54bc67f9f5d7619c8a4467025d549cc30687`.
At 32.726797 MHz, seeds `F0`, `FF`, and `A5` all reached `3A00003A`; the four
downloaded evidence files match the hashes in the terminal `passed` result.
