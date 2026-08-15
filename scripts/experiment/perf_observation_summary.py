#!/usr/bin/env python3
"""Aggregate a complete instrumented perf20 matrix without losing run identity."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import (  # noqa: E402
    ExperimentError,
    load_json,
    load_perf_matrix,
    parse_key_values,
    sha256_file,
)


LSQ_EVENT_NAMES_V4 = (
    "scheduled_load_valid",
    "translation_active",
    "translation_cancel_pending",
    "translation_request_valid",
    "translation_request_ready",
    "translation_request_fire",
    "translation_response_valid",
    "translation_response_ready",
    "translation_response_fire",
    "data_request_valid",
    "data_request_ready",
    "data_request_fire",
    "data_request_is_write",
    "data_request_uncached",
    "data_response_valid",
    "load_request_fire",
    "store_request_fire",
    "forward_fire",
    "store_completion_fire",
    "translation_completion_fire",
    "completion_valid",
    "load_wakeup_valid",
    "store_data_fire",
    "agu_fire",
    "older_store_pending",
    "store_drain_busy",
    "request_buffer_valid",
    "accepted_store_valid",
)

LSQ_EVENT_NAMES_V5 = LSQ_EVENT_NAMES_V4 + (
    "load_head_ready",
    "load_needs_translation",
    "load_block_unknown_store",
    "load_block_older_uncached_store",
    "load_block_older_load",
    "load_block_partial_overlap",
    "load_block_pending_store_data",
    "load_forward_candidate",
    "load_cache_candidate",
    "load_request_capture",
    "load_candidate_buffer_busy",
    "load_candidate_store_priority",
    "raw_load_completion",
    "raw_ordinary_load_completion",
    "raw_ordinary_load_completion_at_rob_head",
    "oldest_load_address_not_ready",
    "alternate_pending_load_address_ready",
    "oldest_blocked_with_alternate_address_ready",
)

LSQ_EVENT_NAMES_V6 = (
    LSQ_EVENT_NAMES_V4[:-1]
    + ("cached_store_request_fire",)
    + LSQ_EVENT_NAMES_V5[len(LSQ_EVENT_NAMES_V4) :]
)

LSQ_EVENT_NAMES_V7 = LSQ_EVENT_NAMES_V6 + (
    "load_queue_full",
    "store_queue_full",
    "oldest_load_address_not_ready_with_alternate",
    "oldest_load_order_blocked_with_alternate",
    "oldest_load_local_alias_blocked_with_alternate",
    "load_block_multiple_forwarding_stores",
    "load_block_multiple_forwarding_stores_with_alternate",
)

CACHE_EVENT_NAMES = (
    "instruction_request_valid",
    "instruction_request_ready",
    "instruction_response_valid",
    "l1i_request_fire",
    "l1i_line_read_valid",
    "l1i_line_read_fire",
    "l1d_request_fire",
    "l1d_line_read_valid",
    "l1d_line_read_fire",
    "l1d_line_write_valid",
    "l1d_line_write_fire",
    "l2_read_valid",
    "l2_read_fire",
    "memory_read_valid",
    "memory_read_fire",
    "memory_read_beat_valid",
    "memory_read_beat_fire",
    "memory_write_valid",
    "memory_write_fire",
    "memory_write_response_valid",
)

ROB_HEAD_REASON_NAMES = (
    "invalid",
    "payload_not_ready",
    "incomplete",
    "predictor_backpressure",
    "ready_without_retire",
)

ROB_INCOMPLETE_CLASS_NAMES = ("load", "store", "branch", "system", "other")

PERF20_BENCHMARKS = (
    "bitcount",
    "bubble_sort",
    "coremark",
    "crc32",
    "dhrystone",
    "quick_sort",
    "select_sort",
    "sha",
    "stream_copy",
    "stringsearch",
    "fireye_A0",
    "fireye_B2",
    "fireye_C0",
    "fireye_D1",
    "fireye_I2",
    "inner_product",
    "lookup_table",
    "loop_induction",
    "my_memcmp",
    "minmax_sequence",
)


def _vector(document: dict[str, Any], path: str, length: int) -> list[int]:
    value: Any = document
    for key in path.split("."):
        value = value.get(key) if isinstance(value, dict) else None
    if not isinstance(value, list) or len(value) != length or not all(
        isinstance(item, int) and item >= 0 for item in value
    ):
        raise ExperimentError(f"观测字段 {path} 必须包含 {length} 个非负整数")
    return value


def _integer(document: dict[str, Any], path: str) -> int:
    value: Any = document
    for key in path.split("."):
        value = value.get(key) if isinstance(value, dict) else None
    if not isinstance(value, int) or value < 0:
        raise ExperimentError(f"观测字段 {path} 必须是非负整数")
    return value


def _named_counts(
    document: dict[str, Any], path: str, names: tuple[str, ...]
) -> dict[str, int]:
    value: Any = document
    for key in path.split("."):
        value = value.get(key) if isinstance(value, dict) else None
    if not isinstance(value, dict) or set(value) != set(names) or not all(
        isinstance(item, int) and item >= 0 for item in value.values()
    ):
        raise ExperimentError(f"观测字段 {path} 必须包含指定的非负计数器")
    return {name: value[name] for name in names}


def _add_vector(total: list[int], value: list[int]) -> None:
    for index, item in enumerate(value):
        total[index] += item


def _ratio(value: int, cycles: int) -> float:
    return value / cycles if cycles else 0.0


def summarize_matrix(matrix_path: Path) -> dict[str, Any]:
    matrix = load_perf_matrix(matrix_path)
    if matrix["identity"]["profile"] != "instrumented":
        raise ExperimentError("性能观测汇总只接受 instrumented matrix")
    expected_rows = {
        (benchmark, "ideal", 0) for benchmark in PERF20_BENCHMARKS
    }
    if set(matrix["rows"]) != expected_rows:
        raise ExperimentError("性能观测汇总要求完整的 ideal-memory perf20 20/20 矩阵")

    identity_keys = (
        "cpu_commit",
        "chiplab_commit",
        "profile",
        "suite",
        "memory_mode",
        "model_sha256",
        "model_key",
        "software_key",
    )
    reference_identity: dict[str, str] | None = None
    source_schema: str | None = None
    load_queue_capacity: int | None = None
    workloads: list[dict[str, Any]] = []

    totals = {
        "score_cycles": 0,
        "roi_cycles": 0,
        "retired_instructions": 0,
        "retire_width_histogram": [0] * 4,
        "zero_retire_loss": {"recovery": 0, "rob_empty": 0, "rob_nonempty": 0},
        "recovery_cycles": 0,
        "rob_occupancy_sum": 0,
        "rob_full_cycles": 0,
        "rob_empty_cycles": 0,
        "rob_zero_retire_head_reason": {
            name: 0 for name in ROB_HEAD_REASON_NAMES
        },
        "rob_incomplete_head_class": {
            name: 0 for name in ROB_INCOMPLETE_CLASS_NAMES
        },
        "frontend_empty_cycles": 0,
        "frontend_decode_valid_sum": 0,
        "frontend_translation_outstanding_cycles": 0,
        "frontend_cache_outstanding_cycles": 0,
        "issue_occupancy_sum": [0] * 4,
        "issue_full_cycles": [0] * 4,
        "issue_fire_by_port": [0] * 4,
        "issue_fire_sum": 0,
        "dispatch_fire_sum": 0,
        "branch_resolved": 0,
        "branch_mispredicted": 0,
        "branch_recovery_matches": 0,
        "branch_resolve_to_recovery_cycles": 0,
        "branch_head_completion_opportunity": 0,
        "branch_head_mispredict_opportunity": 0,
        "load_queue_occupancy_sum": 0,
        "store_queue_occupancy_sum": 0,
        "load_queue_full_cycles": 0,
        "store_queue_full_cycles": 0,
        "store_data_multiple_ready_cycles": 0,
        "store_data_out_of_age_order_cycles": 0,
        "lsq_events": [0] * len(LSQ_EVENT_NAMES_V7),
        "cache_events": [0] * len(CACHE_EVENT_NAMES),
        "axi_valid": [0] * 5,
        "axi_fire": [0] * 5,
        "axi_backpressure": [0] * 5,
        "axi_errors": [0] * 2,
    }

    for key in sorted(matrix["rows"]):
        benchmark, memory_mode, seed = key
        lane = matrix["runs"][key]
        manifest_path = lane / "run-manifest.txt"
        counters_path = lane / "m01-counters.json"
        manifest = parse_key_values(manifest_path)
        identity = {name: manifest.get(name, "") for name in identity_keys}
        if any(not value for value in identity.values()):
            raise ExperimentError(f"run manifest 身份不完整: {manifest_path}")
        if reference_identity is None:
            reference_identity = identity
        elif identity != reference_identity:
            raise ExperimentError(f"观测矩阵身份不一致: {manifest_path}")
        expected_hash = manifest.get("m01_counters_sha256", "")
        if not counters_path.is_file() or sha256_file(counters_path) != expected_hash:
            raise ExperimentError(f"M01 计数器缺失或 hash 不匹配: {counters_path}")

        counters = load_json(counters_path)
        row_schema = counters.get("schema_version")
        if row_schema not in {
            "miku-perf-observation-v3",
            "miku-perf-observation-v4",
            "miku-perf-observation-v5",
            "miku-perf-observation-v6",
            "miku-perf-observation-v7",
            "miku-perf-observation-v8",
            "miku-perf-observation-v9",
        }:
            raise ExperimentError(f"观测汇总要求 v3-v9 ROI 结构: {counters_path}")
        if source_schema is None:
            source_schema = row_schema
        elif row_schema != source_schema:
            raise ExperimentError(f"观测矩阵 schema 不一致: {counters_path}")
        roi = counters.get("roi")
        if not isinstance(roi, dict) or roi.get("mode") != "outermost-counter-read-pair":
            raise ExperimentError(f"perf20 必须使用最外层 counter-read ROI: {counters_path}")
        markers = roi.get("counter_read_markers")
        nested_pairs = roi.get("nested_counter_read_pairs")
        if (
            roi.get("complete") is not True
            or roi.get("boundary_cycles_included") is not False
            or not isinstance(markers, int)
            or markers < 2
            or markers % 2 != 0
            or nested_pairs != markers // 2 - 1
        ):
            raise ExperimentError(f"perf20 ROI marker 未闭合: {counters_path}")
        invariants = counters.get("invariants")
        if not isinstance(invariants, dict) or any(
            value is not True for name, value in invariants.items()
            if name in {
                "abi_valid",
                "retire_hist_cycles",
                "retire_hist_instructions",
                "commit_observation",
                "source_retire_alignment",
                "roi_complete",
            }
        ):
            raise ExperimentError(f"perf20 ROI invariant 未通过: {counters_path}")

        score_cycles = matrix["rows"][key]
        roi_cycles = _integer(counters, "cycles")
        retired = _integer(counters, "retired_instructions")
        retire_hist = _vector(counters, "retire_width_histogram", 4)
        rob_hist = _vector(counters, "rob.occupancy_histogram", 33)
        head_reasons = (
            _named_counts(
                counters, "rob.zero_retire_head_reason", ROB_HEAD_REASON_NAMES
            )
            if row_schema in {
                "miku-perf-observation-v4",
                "miku-perf-observation-v5",
                "miku-perf-observation-v6",
                "miku-perf-observation-v7",
                "miku-perf-observation-v8",
                "miku-perf-observation-v9",
            }
            else None
        )
        incomplete_classes = (
            _named_counts(
                counters, "rob.incomplete_head_class", ROB_INCOMPLETE_CLASS_NAMES
            )
            if row_schema in {
                "miku-perf-observation-v4",
                "miku-perf-observation-v5",
                "miku-perf-observation-v6",
                "miku-perf-observation-v7",
                "miku-perf-observation-v8",
                "miku-perf-observation-v9",
            }
            else None
        )
        frontend_hist = _vector(counters, "frontend.occupancy_histogram", 17)
        issue_occupancy = _vector(counters, "issue.occupancy_sum", 4)
        issue_full = _vector(counters, "issue.full_cycles", 4)
        issue_fire = _vector(counters, "issue.fire_by_port", 4)
        lsq_event_names = {
            "miku-perf-observation-v5": LSQ_EVENT_NAMES_V5,
            "miku-perf-observation-v6": LSQ_EVENT_NAMES_V6,
            "miku-perf-observation-v7": LSQ_EVENT_NAMES_V7,
            "miku-perf-observation-v8": LSQ_EVENT_NAMES_V7,
            "miku-perf-observation-v9": LSQ_EVENT_NAMES_V7,
        }.get(row_schema, LSQ_EVENT_NAMES_V4)
        lsq_events = _vector(counters, "lsq.events", len(lsq_event_names))
        row_load_capacity = (
            _integer(counters, "lsq.load_capacity")
            if row_schema in {
                "miku-perf-observation-v8",
                "miku-perf-observation-v9",
            }
            else (8 if row_schema == "miku-perf-observation-v7" else 16)
        )
        if load_queue_capacity is None:
            load_queue_capacity = row_load_capacity
        elif load_queue_capacity != row_load_capacity:
            raise ExperimentError(f"观测矩阵 load queue 容量不一致: {counters_path}")
        cache_events = _vector(counters, "cache.events", len(CACHE_EVENT_NAMES))
        axi_valid = _vector(counters, "axi.valid", 5)
        axi_fire = _vector(counters, "axi.fire", 5)
        axi_backpressure = _vector(counters, "axi.backpressure", 5)
        axi_errors = _vector(counters, "axi.errors", 2)
        store_data_multiple_ready = (
            _integer(counters, "store_data.multiple_ready_cycles")
            if row_schema == "miku-perf-observation-v9"
            else 0
        )
        store_data_out_of_age = (
            _integer(counters, "store_data.out_of_age_order_cycles")
            if row_schema == "miku-perf-observation-v9"
            else 0
        )

        row = {
            "benchmark": benchmark,
            "memory_mode": memory_mode,
            "seed": seed,
            "score_cycles": score_cycles,
            "roi_cycles": roi_cycles,
            "score_minus_roi_cycles": score_cycles - roi_cycles,
            "nested_counter_read_pairs": nested_pairs,
            "retired_instructions": retired,
            "ipc": _ratio(retired, roi_cycles),
            "zero_retire_loss": counters["zero_retire_loss"],
            "rob": {
                "average_occupancy": _ratio(
                    _integer(counters, "rob.occupancy_sum"), roi_cycles
                ),
                "full_cycle_ratio": _ratio(
                    _integer(counters, "rob.full_cycles"), roi_cycles
                ),
                "empty_cycle_ratio": _ratio(rob_hist[0], roi_cycles),
                **(
                    {
                        "zero_retire_head_reason": head_reasons,
                        "incomplete_head_class": incomplete_classes,
                    }
                    if head_reasons is not None and incomplete_classes is not None
                    else {}
                ),
            },
            "frontend": {
                "empty_cycle_ratio": _ratio(frontend_hist[0], roi_cycles),
                "translation_outstanding_ratio": _ratio(
                    _integer(counters, "frontend.translation_outstanding_cycles"),
                    roi_cycles,
                ),
                "cache_outstanding_ratio": _ratio(
                    _integer(counters, "frontend.cache_outstanding_cycles"),
                    roi_cycles,
                ),
            },
            "issue": {
                "average_occupancy": [
                    _ratio(value, roi_cycles) for value in issue_occupancy
                ],
                "full_cycle_ratio": [
                    _ratio(value, roi_cycles) for value in issue_full
                ],
                "fire_per_cycle": _ratio(
                    _integer(counters, "issue.fire_sum"), roi_cycles
                ),
            },
            "branch": {
                "resolved": _integer(counters, "branch.resolved"),
                "mispredicted": _integer(counters, "branch.mispredicted"),
                "recovery_matches": _integer(counters, "branch.recovery_matches"),
                "resolve_to_recovery_cycles": _integer(
                    counters, "branch.resolve_to_recovery_cycles"
                ),
                "head_completion_opportunity": (
                    _integer(counters, "branch.head_completion_opportunity")
                    if row_schema in {
                        "miku-perf-observation-v5",
                        "miku-perf-observation-v6",
                        "miku-perf-observation-v7",
                        "miku-perf-observation-v8",
                        "miku-perf-observation-v9",
                    }
                    else 0
                ),
                "head_mispredict_opportunity": (
                    _integer(counters, "branch.head_mispredict_opportunity")
                    if row_schema in {
                        "miku-perf-observation-v5",
                        "miku-perf-observation-v6",
                        "miku-perf-observation-v7",
                        "miku-perf-observation-v8",
                        "miku-perf-observation-v9",
                    }
                    else 0
                ),
            },
            "lsq": {
                "load_capacity": row_load_capacity,
                "average_load_occupancy": _ratio(
                    _integer(counters, "lsq.load_occupancy_sum"), roi_cycles
                ),
                "average_store_occupancy": _ratio(
                    _integer(counters, "lsq.store_occupancy_sum"), roi_cycles
                ),
                "events": dict(zip(lsq_event_names, lsq_events)),
            },
            "store_data": {
                "multiple_ready_cycles": store_data_multiple_ready,
                "out_of_age_order_cycles": store_data_out_of_age,
                "multiple_ready_ratio": _ratio(store_data_multiple_ready, roi_cycles),
                "out_of_age_order_ratio": _ratio(store_data_out_of_age, roi_cycles),
            },
            "cache_events": dict(zip(CACHE_EVENT_NAMES, cache_events)),
            "axi": counters["axi"],
            "evidence": {
                "run_manifest": manifest_path.as_posix(),
                "counters": counters_path.as_posix(),
                "counters_sha256": expected_hash,
            },
        }
        workloads.append(row)

        totals["score_cycles"] += score_cycles
        totals["roi_cycles"] += roi_cycles
        totals["retired_instructions"] += retired
        _add_vector(totals["retire_width_histogram"], retire_hist)
        for name in totals["zero_retire_loss"]:
            totals["zero_retire_loss"][name] += _integer(
                counters, f"zero_retire_loss.{name}"
            )
        totals["recovery_cycles"] += _integer(counters, "recovery_cycles")
        totals["rob_occupancy_sum"] += _integer(counters, "rob.occupancy_sum")
        totals["rob_full_cycles"] += _integer(counters, "rob.full_cycles")
        totals["rob_empty_cycles"] += rob_hist[0]
        if head_reasons is not None and incomplete_classes is not None:
            for name in ROB_HEAD_REASON_NAMES:
                totals["rob_zero_retire_head_reason"][name] += head_reasons[name]
            for name in ROB_INCOMPLETE_CLASS_NAMES:
                totals["rob_incomplete_head_class"][name] += incomplete_classes[name]
        totals["frontend_empty_cycles"] += frontend_hist[0]
        totals["frontend_decode_valid_sum"] += _integer(
            counters, "frontend.decode_valid_sum"
        )
        totals["frontend_translation_outstanding_cycles"] += _integer(
            counters, "frontend.translation_outstanding_cycles"
        )
        totals["frontend_cache_outstanding_cycles"] += _integer(
            counters, "frontend.cache_outstanding_cycles"
        )
        _add_vector(totals["issue_occupancy_sum"], issue_occupancy)
        _add_vector(totals["issue_full_cycles"], issue_full)
        _add_vector(totals["issue_fire_by_port"], issue_fire)
        totals["issue_fire_sum"] += _integer(counters, "issue.fire_sum")
        totals["dispatch_fire_sum"] += _integer(counters, "dispatch.fire_sum")
        totals["branch_resolved"] += _integer(counters, "branch.resolved")
        totals["branch_mispredicted"] += _integer(counters, "branch.mispredicted")
        totals["branch_recovery_matches"] += _integer(
            counters, "branch.recovery_matches"
        )
        totals["branch_resolve_to_recovery_cycles"] += _integer(
            counters, "branch.resolve_to_recovery_cycles"
        )
        if row_schema in {
            "miku-perf-observation-v5",
            "miku-perf-observation-v6",
            "miku-perf-observation-v7",
            "miku-perf-observation-v8",
            "miku-perf-observation-v9",
        }:
            totals["branch_head_completion_opportunity"] += _integer(
                counters, "branch.head_completion_opportunity"
            )
            totals["branch_head_mispredict_opportunity"] += _integer(
                counters, "branch.head_mispredict_opportunity"
            )
        totals["load_queue_occupancy_sum"] += _integer(
            counters, "lsq.load_occupancy_sum"
        )
        totals["store_queue_occupancy_sum"] += _integer(
            counters, "lsq.store_occupancy_sum"
        )
        totals["load_queue_full_cycles"] += _integer(
            counters, "lsq.load_full_cycles"
        )
        totals["store_queue_full_cycles"] += _integer(
            counters, "lsq.store_full_cycles"
        )
        totals["store_data_multiple_ready_cycles"] += store_data_multiple_ready
        totals["store_data_out_of_age_order_cycles"] += store_data_out_of_age
        for index, value in enumerate(lsq_events):
            totals["lsq_events"][index] += value
        _add_vector(totals["cache_events"], cache_events)
        _add_vector(totals["axi_valid"], axi_valid)
        _add_vector(totals["axi_fire"], axi_fire)
        _add_vector(totals["axi_backpressure"], axi_backpressure)
        _add_vector(totals["axi_errors"], axi_errors)

    cycles = totals["roi_cycles"]
    derived = {
        "ipc": _ratio(totals["retired_instructions"], cycles),
        "zero_retire_cycle_ratio": _ratio(
            totals["retire_width_histogram"][0], cycles
        ),
        "recovery_cycle_ratio": _ratio(totals["recovery_cycles"], cycles),
        "rob_average_occupancy": _ratio(totals["rob_occupancy_sum"], cycles),
        "rob_full_cycle_ratio": _ratio(totals["rob_full_cycles"], cycles),
        "rob_empty_cycle_ratio": _ratio(totals["rob_empty_cycles"], cycles),
        "frontend_empty_cycle_ratio": _ratio(
            totals["frontend_empty_cycles"], cycles
        ),
        "frontend_translation_outstanding_ratio": _ratio(
            totals["frontend_translation_outstanding_cycles"], cycles
        ),
        "frontend_cache_outstanding_ratio": _ratio(
            totals["frontend_cache_outstanding_cycles"], cycles
        ),
        "issue_average_occupancy": [
            _ratio(value, cycles) for value in totals["issue_occupancy_sum"]
        ],
        "issue_full_cycle_ratio": [
            _ratio(value, cycles) for value in totals["issue_full_cycles"]
        ],
        "issue_fire_per_cycle": _ratio(totals["issue_fire_sum"], cycles),
        "dispatch_fire_per_cycle": _ratio(totals["dispatch_fire_sum"], cycles),
        "branch_mispredict_ratio": _ratio(
            totals["branch_mispredicted"], totals["branch_resolved"]
        ),
        "branch_average_resolve_to_recovery": _ratio(
            totals["branch_resolve_to_recovery_cycles"],
            totals["branch_recovery_matches"],
        ),
        "branch_head_completion_opportunity_ratio": _ratio(
            totals["branch_head_completion_opportunity"], cycles
        ),
        "branch_head_mispredict_opportunity_ratio": _ratio(
            totals["branch_head_mispredict_opportunity"], cycles
        ),
        "load_queue_average_occupancy": _ratio(
            totals["load_queue_occupancy_sum"], cycles
        ),
        "store_queue_average_occupancy": _ratio(
            totals["store_queue_occupancy_sum"], cycles
        ),
        "load_queue_full_cycle_ratio": _ratio(
            totals["load_queue_full_cycles"], cycles
        ),
        "store_queue_full_cycle_ratio": _ratio(
            totals["store_queue_full_cycles"], cycles
        ),
        "store_data_multiple_ready_cycle_ratio": _ratio(
            totals["store_data_multiple_ready_cycles"], cycles
        ),
        "store_data_out_of_age_order_cycle_ratio": _ratio(
            totals["store_data_out_of_age_order_cycles"], cycles
        ),
        "store_data_out_of_age_per_multiple_ready": _ratio(
            totals["store_data_out_of_age_order_cycles"],
            totals["store_data_multiple_ready_cycles"],
        ),
    }
    if source_schema in {
        "miku-perf-observation-v4",
        "miku-perf-observation-v5",
        "miku-perf-observation-v6",
        "miku-perf-observation-v7",
        "miku-perf-observation-v8",
        "miku-perf-observation-v9",
    }:
        derived["rob_zero_retire_head_reason_ratio"] = {
            name: _ratio(value, cycles)
            for name, value in totals["rob_zero_retire_head_reason"].items()
        }
        derived["rob_incomplete_head_class_ratio"] = {
            name: _ratio(value, cycles)
            for name, value in totals["rob_incomplete_head_class"].items()
        }
    summary_lsq_event_names = {
        "miku-perf-observation-v3": LSQ_EVENT_NAMES_V4,
        "miku-perf-observation-v4": LSQ_EVENT_NAMES_V4,
        "miku-perf-observation-v5": LSQ_EVENT_NAMES_V5,
        "miku-perf-observation-v6": LSQ_EVENT_NAMES_V6,
        "miku-perf-observation-v7": LSQ_EVENT_NAMES_V7,
        "miku-perf-observation-v8": LSQ_EVENT_NAMES_V7,
        "miku-perf-observation-v9": LSQ_EVENT_NAMES_V7,
    }[source_schema]
    raw_totals = {
        **totals,
        "load_queue_capacity": load_queue_capacity,
        "lsq_events": dict(zip(summary_lsq_event_names, totals["lsq_events"])),
        "cache_events": dict(zip(CACHE_EVENT_NAMES, totals["cache_events"])),
    }
    return {
        "schema_version": 1,
        "source_schema": source_schema,
        "matrix": {
            "path": matrix["path"].as_posix(),
            "sha256": sha256_file(matrix["path"]),
        },
        "identity": reference_identity,
        "summary": {"raw": raw_totals, "derived": derived},
        "workloads": workloads,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = summarize_matrix(args.matrix)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(result, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    raw = result["summary"]["raw"]
    derived = result["summary"]["derived"]
    print(
        f"perf20 ROI: score={raw['score_cycles']} roi={raw['roi_cycles']} "
        f"instructions={raw['retired_instructions']} ipc={derived['ipc']:.6f}"
    )
    print(args.out)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ExperimentError as error:
        print(f"performance observation summary failed: {error}", file=sys.stderr)
        raise SystemExit(1)
