# Branch Trace Observer

`SIM_BRANCH_TRACE=1` enables an instrumented-only, simulation-side observer for
retired branches. It records the predictor metadata carried with each ROB entry,
so trace/replay can measure the distribution of weak PHT states without adding a
counter to the public CPU interface.

## Run

Build the instrumented model and run a small workload:

```sh
make sim-prepare SIM_PROFILE=instrumented SIM_BRANCH_TRACE=1 \
  SIM_SUITE=perf20 SIM_WORKLOADS=perf20/coremark RUN_SOFTWARE=coremark
make sim-matrix SIM_PROFILE=instrumented SIM_SUITE=perf20 \
  SIM_WORKLOADS=perf20/coremark SIM_MEMORY_MODE=ideal SIM_SEEDS=0 \
  SIM_LANES=1 TIME_LIMIT=600000000
```

The matrix run writes `branch-trace-v1.jsonl` beside the workload logs. Summarize
it with:

```sh
make branch-trace-summary BRANCH_TRACE=/absolute/path/to/branch-trace-v1.jsonl
```

The run manifest stores the trace hash. The model cache key also includes the
observer source/header hashes and the branch-trace setting, preventing an
instrumented model from being confused with a clean model.

## Record semantics

Each branch record is emitted at the in-order commit boundary and contains the
cycle, commit lane, ROB pointer, PC/instruction, actual direction and target,
predictor type, and the 16-bit predictor metadata captured at fetch. The summary
decodes the configured PHT index, two-bit state, and valid bit. A branch is
`low_confidence_pht` when the PHT entry is valid and its state is weakly taken or
weakly not-taken (`01` or `10`). Direct, indirect, return, and call records are
retained for category comparison; their PHT field is diagnostic metadata rather
than a claim that those classes use the conditional PHT for prediction.

This first observer does not record predicted direction, BTB hit/miss, target
prediction, or RAS hit/miss. Those require a separate fetch-side observation and
should only be added if the retirement distribution identifies a useful question.

## Production boundary

The default `make cpu-generate` path sets `CPU_BRANCH_TRACE=0`, so the observer
is absent from the published clean RTL. A branch-trace model is an observation
artifact only; its timing, area, and performance must not be used as formal
implementation evidence.
