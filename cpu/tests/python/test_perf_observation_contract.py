from __future__ import annotations

import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
CHECKER = ROOT / "scripts/sim/check-m01-counters"
MONITOR = ROOT / "scripts/sim/perf_monitor.cpp"
OBSERVATION_SOURCES = (
    ROOT / "cpu/src/main/scala/miku/core/OooCoreSystem.scala",
    ROOT / "cpu/src/main/scala/miku/core/OooCore.scala",
    ROOT / "cpu/src/main/scala/miku/frontend/OooFrontend.scala",
    ROOT / "cpu/src/main/scala/miku/backend/OooBackend.scala",
    ROOT / "cpu/src/main/scala/miku/backend/ReorderBuffer.scala",
    ROOT / "cpu/src/main/scala/miku/backend/LoadStoreQueue.scala",
    ROOT / "cpu/src/main/scala/miku/memory/SharedCacheHierarchy.scala",
)


def valid_document() -> dict:
    return {
        "schema_version": "miku-perf-observation-v2",
        "observation_abi": {"magic": "MIKU", "version": 1, "word_count": 8},
        "roi": {
            "mode": "counter-read-pairs",
            "counter_read_markers": 2,
            "closed_pairs": 1,
            "complete": True,
            "boundary_cycles_included": False,
        },
        "commit_observation_lag_cycles": 4,
        "cycles": 1,
        "retired_instructions": 0,
        "retire_width_histogram": [1, 0, 0, 0],
        "unused_commit_slots": 3,
        "observed_commit_instructions": 0,
        "commit_trace_signature_fnv1a64": "14650fb0739d0383",
        "recovery_cycles": 0,
        "recovery_cause": {
            "none": 0,
            "branch_mispredict": 0,
            "exception": 0,
            "ertn": 0,
            "refetch": 0,
            "reserved_5": 0,
            "reserved_6": 0,
            "reserved_7": 0,
        },
        "zero_retire_loss": {"recovery": 0, "rob_empty": 1, "rob_nonempty": 0},
        "rob": {
            "occupancy_sum": 0,
            "occupancy_max": 0,
            "full_cycles": 0,
            "occupancy_histogram": [1] + [0] * 32,
        },
        "frontend": {
            "occupancy_histogram": [1] + [0] * 16,
            "request_interval_histogram": [0] * 8,
            "request_interval_sequences": 0,
            "cache_request_fire": 0,
        },
        "issue": {
            "ready_cycles": [0] * 4,
            "ready_entries": [0] * 4,
            "occupancy_sum": [0] * 4,
            "full_cycles": [0] * 4,
            "operand_valid_sum": 0,
            "fire_sum": 0,
            "fire_by_port": [0] * 4,
        },
        "dispatch": {"valid_sum": 0, "fire_sum": 0, "fire_histogram": [1, 0, 0, 0, 0]},
        "branch": {
            "commit_histogram": [1, 0, 0, 0],
            "retired": 0,
            "resolved": 0,
            "mispredicted": 0,
            "recovery_matches": 0,
            "recovery_without_resolution": 0,
            "resolve_to_recovery_histogram": [0] * 8,
        },
        "lsq": {
            "load_occupancy_sum": 0,
            "store_occupancy_sum": 0,
            "events": [0] * 28,
        },
        "cache": {"events": [0] * 20, "occupancy_sum": [0] * 3},
        "axi": {
            "valid": [0] * 5,
            "fire": [0] * 5,
            "backpressure": [0] * 5,
            "errors": [0] * 2,
        },
        "invariants": {
            "abi_valid": True,
            "retire_hist_cycles": True,
            "retire_hist_instructions": True,
            "commit_observation": True,
            "source_retire_alignment": True,
            "roi_complete": True,
            "abi_errors": 0,
            "source_retire_alignment_errors": 0,
            "sampling_protocol_errors": 0,
            "non_prefix_retire_cycles": 0,
        },
    }


class PerfObservationContractTest(unittest.TestCase):
    def run_checker(self, document: dict) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "counters.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            return subprocess.run(
                [str(CHECKER), str(path)],
                cwd=ROOT,
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )

    def test_accepts_versioned_public_contract(self) -> None:
        result = self.run_checker(valid_document())
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("PerfObservation counters pass", result.stdout)

    def test_accepts_existing_v1_evidence(self) -> None:
        document = copy.deepcopy(valid_document())
        document["schema_version"] = "miku-perf-observation-v1"
        document["roi"] = "difftest-observation-window-source-aligned"
        document["invariants"].pop("roi_complete")
        result = self.run_checker(document)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_accepts_full_run_without_counter_markers(self) -> None:
        document = copy.deepcopy(valid_document())
        document["roi"] = {
            "mode": "full-run",
            "counter_read_markers": 0,
            "closed_pairs": 0,
            "complete": True,
            "boundary_cycles_included": False,
        }
        result = self.run_checker(document)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_rejects_incomplete_counter_read_pair(self) -> None:
        document = copy.deepcopy(valid_document())
        document["roi"]["counter_read_markers"] = 3
        document["roi"]["complete"] = False
        document["invariants"]["roi_complete"] = False
        result = self.run_checker(document)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("markers are incomplete", result.stdout)

    def test_rejects_inconsistent_closed_pair_count(self) -> None:
        document = copy.deepcopy(valid_document())
        document["roi"]["closed_pairs"] = 2
        result = self.run_checker(document)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("do not form closed pairs", result.stdout)

    def test_accepts_multiple_roi_request_sequences(self) -> None:
        document = copy.deepcopy(valid_document())
        document["cycles"] = 4
        document["roi"]["counter_read_markers"] = 4
        document["roi"]["closed_pairs"] = 2
        document["retire_width_histogram"] = [4, 0, 0, 0]
        document["unused_commit_slots"] = 12
        document["zero_retire_loss"] = {
            "recovery": 0,
            "rob_empty": 4,
            "rob_nonempty": 0,
        }
        document["rob"]["occupancy_histogram"] = [4] + [0] * 32
        document["frontend"]["occupancy_histogram"] = [4] + [0] * 16
        document["frontend"]["cache_request_fire"] = 4
        document["frontend"]["request_interval_sequences"] = 2
        document["frontend"]["request_interval_histogram"] = [0, 2] + [0] * 6
        document["dispatch"]["fire_histogram"] = [4, 0, 0, 0, 0]
        document["branch"]["commit_histogram"] = [4, 0, 0, 0]
        result = self.run_checker(document)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_rejects_wrong_abi_identity(self) -> None:
        document = copy.deepcopy(valid_document())
        document["observation_abi"]["word_count"] = 7
        result = self.run_checker(document)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unexpected observation ABI", result.stdout)

    def test_rejects_nonconserving_occupancy(self) -> None:
        document = copy.deepcopy(valid_document())
        document["rob"]["occupancy_sum"] = 1
        result = self.run_checker(document)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ROB occupancy sum", result.stdout)

    def test_monitor_reads_only_dedicated_observation_words(self) -> None:
        source = MONITOR.read_text(encoding="utf-8")
        # Word 0 is an immutable ABI header and may be constant-folded by
        # Verilator. The monitor constructs it from locked constants; only
        # the seven dynamic words require generated-model members.
        self.assertEqual(source.count("#define OBSERVATION_WORD_"), 7)
        self.assertNotIn("#define OBSERVATION_WORD_0", source)
        self.assertIn("snapshot.words[0] = observation_header();", source)
        forbidden = (
            "__DOT__queue_",
            "__DOT__slotOccupied",
            "__DOT__readyAge",
            "__DOT__stagedCompletion",
            "__DOT__architectural_",
            "__SYM__switch",
        )
        for name in forbidden:
            self.assertNotIn(name, source)

    def test_monitor_detects_counter_reads_from_public_commit_instruction(self) -> None:
        source = MONITOR.read_text(encoding="utf-8")
        self.assertIn(
            "(instruction & 0xffffffe0U) == 0x00006000U",
            source,
        )
        self.assertIn("pending_commits_", source)

    def test_each_abi_word_has_one_local_owner(self) -> None:
        combined = "\n".join(path.read_text(encoding="utf-8") for path in OBSERVATION_SOURCES)
        for index in range(8):
            declaration = f"val perfObservationV1Word{index} ="
            exposure = f"PerfObservationV1.expose(perfObservationV1Word{index}, {index})"
            self.assertEqual(combined.count(declaration), 1, declaration)
            self.assertEqual(combined.count(exposure), 1, exposure)

    def test_public_perf20_entry_forwards_instrumented_profile(self) -> None:
        result = subprocess.run(
            ["make", "-n", "perf20-sim", "SIM_PROFILE=instrumented"],
            cwd=ROOT,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        profile_arguments = [
            line for line in result.stdout.splitlines() if "--profile" in line
        ]
        self.assertGreaterEqual(len(profile_arguments), 2, result.stdout)
        self.assertTrue(
            all('--profile "instrumented"' in line for line in profile_arguments),
            result.stdout,
        )

if __name__ == "__main__":
    unittest.main()
