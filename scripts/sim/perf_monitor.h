#ifndef MIKU_PERF_OBSERVATION_V1_MONITOR_H
#define MIKU_PERF_OBSERVATION_V1_MONITOR_H

#include <cstdint>

class Vsimu_top;

// Instrumented models read only the versioned observation words.  No ordinary
// Verilator implementation hierarchy is part of this C++ contract.
class PerfMonitor {
public:
    explicit PerfMonitor(Vsimu_top *top);

    void begin_cycle();
    void cancel_cycle();
    void record_commit_cycle(std::uint8_t count);
    void record_commit(std::uint64_t pc, std::uint32_t instruction,
                       std::uint8_t index);
    void write_json(const char *path) const;

private:
    static constexpr unsigned kCommitObservationLag = 4;
    static constexpr unsigned kWordCount = 8;
    static constexpr unsigned kFrontendOccupancyBins = 17;

    struct CycleSnapshot {
        std::uint64_t words[kWordCount] = {};
        bool abi_valid = false;
    };

    struct CommitRecord {
        std::uint64_t pc = 0;
        std::uint32_t instruction = 0;
        std::uint8_t index = 0;
    };

    CycleSnapshot capture_snapshot();
    void accumulate_snapshot(const CycleSnapshot &snapshot,
                             std::uint8_t retired_count);
    void accumulate_commit(const CommitRecord &commit);
    void reset_accumulators();
    void reset_interval_state();
    static bool is_counter_read(std::uint32_t instruction);

    Vsimu_top *top_;
    CycleSnapshot snapshot_history_[kCommitObservationLag] = {};
    unsigned snapshot_history_count_ = 0;
    bool commit_cycle_pending_ = false;
    CommitRecord pending_commits_[3] = {};
    std::uint8_t pending_commit_count_ = 0;

    bool roi_marker_seen_ = false;
    bool roi_active_ = false;
    std::uint64_t roi_counter_read_markers_ = 0;
    std::uint64_t roi_closed_pairs_ = 0;

    std::uint64_t cycles_ = 0;
    std::uint64_t retire_hist_[4] = {};
    std::uint64_t sampled_instructions_ = 0;
    std::uint64_t observed_instructions_ = 0;
    std::uint64_t trace_signature_ = 1469598103934665603ULL;
    std::uint64_t abi_errors_ = 0;
    std::uint64_t sampling_protocol_errors_ = 0;
    std::uint64_t source_retire_alignment_errors_ = 0;
    std::uint64_t non_prefix_retire_ = 0;

    std::uint64_t recovery_cycles_ = 0;
    std::uint64_t recovery_cause_[8] = {};
    std::uint64_t zero_retire_[3] = {};

    std::uint64_t rob_occupancy_sum_ = 0;
    std::uint64_t rob_occupancy_max_ = 0;
    std::uint64_t rob_full_cycles_ = 0;
    std::uint64_t rob_occupancy_hist_[33] = {};

    std::uint64_t frontend_decode_valid_sum_ = 0;
    std::uint64_t frontend_occupancy_hist_[kFrontendOccupancyBins] = {};
    std::uint64_t frontend_events_[12] = {};
    std::uint64_t frontend_request_interval_hist_[8] = {};
    std::uint64_t frontend_request_sequences_ = 0;
    std::uint64_t frontend_last_request_cycle_ = 0;
    bool frontend_seen_request_ = false;

    std::uint64_t issue_ready_cycles_[4] = {};
    std::uint64_t issue_ready_entries_[4] = {};
    std::uint64_t issue_occupancy_sum_[4] = {};
    std::uint64_t issue_full_cycles_[4] = {};
    std::uint64_t issue_fire_by_port_[4] = {};
    std::uint64_t issue_valid_sum_ = 0;
    std::uint64_t issue_fire_sum_ = 0;

    std::uint64_t dispatch_valid_sum_ = 0;
    std::uint64_t dispatch_fire_sum_ = 0;
    std::uint64_t dispatch_fire_hist_[5] = {};

    std::uint64_t branch_commit_hist_[4] = {};
    std::uint64_t branch_retired_ = 0;
    std::uint64_t predictor_update_cycles_ = 0;
    std::uint64_t branch_resolved_ = 0;
    std::uint64_t branch_mispredicted_ = 0;
    std::uint64_t branch_recovery_matches_ = 0;
    std::uint64_t branch_recovery_without_resolution_ = 0;
    std::uint64_t branch_resolve_to_recovery_cycles_ = 0;
    std::uint64_t branch_resolve_to_recovery_max_ = 0;
    std::uint64_t branch_resolve_to_recovery_hist_[8] = {};
    std::uint64_t branch_resolve_cycle_[64] = {};
    bool branch_mispredict_pending_[64] = {};

    std::uint64_t load_queue_occupancy_sum_ = 0;
    std::uint64_t store_queue_occupancy_sum_ = 0;
    std::uint64_t load_queue_full_cycles_ = 0;
    std::uint64_t store_queue_full_cycles_ = 0;
    std::uint64_t lsq_events_[28] = {};
    std::uint64_t cache_events_[20] = {};
    std::uint64_t cache_occupancy_sum_[3] = {};
    std::uint64_t axi_valid_[5] = {};
    std::uint64_t axi_fire_[5] = {};
    std::uint64_t axi_backpressure_[5] = {};
    std::uint64_t axi_error_[2] = {};
};

void perf_monitor_begin_cycle();
void perf_monitor_cancel_cycle();
void perf_monitor_record_commit(std::uint64_t pc, std::uint32_t instruction,
                                std::uint8_t index);
void perf_monitor_record_commit_cycle(std::uint8_t count);

#endif
