# Chiplab Production Platform Migration

## Locked Revisions

The official submission repository includes the protected production GitLab CI
template. At template commit
`6915882af5c8d3a0c856f570cb914920a3e5ff99`, `child.yml` requires Chiplab
`c398d274812f164d387146fa7d8f612a4a1296d9`. This is the current integration
baseline. The local Gitee CI mirror still naming `68c20a5...` is stale and is
not equivalent evidence.

The two Chiplab revisions share ancestor `68edcc4...` and are sibling lines:

- `68c20a5...` extends a local CPU/confreg reset scheme;
- `c398d27...` is the official `nscscc2026` line and uses complete-system reset
  for each functional seed and each performance run.

Therefore this migration is not a fast-forward update from `68c20a5...`.

## Static Platform Differences

The relevant source changes between the two fixed revisions are confined to:

- `soc_top.v`: `c398d27...` removes `btn_step_vio[0]` as a CPU/confreg-only run
  control. CPU and confreg again follow `core_rst_n`.
- `CONFREG/confreg.v`: `c398d27...` seeds its AXI-delay LFSR from the switch on
  system reset. It does not reload the LFSR on a local CPU reset release.
- `vio.tcl`: `c398d27...` selects the seed or benchmark, pulses the complete
  system reset, and waits for PLL, MIG calibration, and system reset readiness
  before every run. Functional tests use `F0`, `FF`, and a CI-selected stress
  seed. Performance executes two independent runs per benchmark and retains the
  conservative valid result.
- `bit.tcl`: negative timing slack is reported in the platform script. The
  production CI template applies the policy: functional hardware collection is
  allowed with a warning, while performance programming requires nonnegative
  setup and hold slack.
- The official line also changes the performance `stringsearch` workload and
  its generated image. Performance numbers from the two platform revisions are
  consequently not directly comparable without reporting that software change.

No file under the student-owned `IP/myCPU` is changed by this platform diff.
The platform update can change reset history and AXI delay sequences seen by a
CPU, but it cannot by itself repair or redefine CPU architectural state.

## `0x30000030` Diagnosis Boundary

The functional program writes one progress value after each numbered test.
`0x30000030` means n48 completed and execution has not completed n49; n49 is the
timer-interrupt exception test in `n49_ti_ex.S`. This is a test milestone, not a
CPU program counter.

The pre-fix CPU at `2301dde...` had a definite reset defect:

- `ESTAT.IS[11]`, the timer interrupt pending bit, was omitted from reset;
- only `TCFG[0]` was reset, leaving the remainder of the timer configuration
  register unspecified;
- `TVAL` had no reset assignment.

CPU commit `1c331323e77bd927c61390f07d629efe098da1dd` resets the complete `ESTAT`,
`TCFG`, and `TVAL` registers and adds CSR-level and ROB/front-end timer
interrupt tests. This is an architectural reset correction, not a benchmark
trade-off, so it remains part of the latest candidate.

The platform reset-policy difference can expose incomplete CPU reset state in a
different way, but static source comparison does not prove that this defect was
the only cause of the observed board stop. Closure requires the same latest RTL
on `c398d27...`, using its complete-system reset protocol, to pass all functional
seeds on the board. Until then, describe the timer reset defect as fixed in RTL
and the board failure as not yet closed.

## Local Integration Contract

Use the clean `chiplab-nscscc2026/` worktree at the exact production commit.
`make chiplab-sync` generates `mycpu_top.v` from SpinalHDL and stages it together
with the two CPU-owned SRAM `.xcix` files. The command must not modify Chiplab
platform sources. `make ci-check` fetches the production template into ignored
`build/official-ci-template/`, verifies its exact revision, parses the template
and submission YAML, and checks the Chiplab pin.

The historical `chiplab/` worktree and its build products may remain for
comparison, but results from it must be labeled with `68c20a5...` and must not
be called current official-CI evidence.
