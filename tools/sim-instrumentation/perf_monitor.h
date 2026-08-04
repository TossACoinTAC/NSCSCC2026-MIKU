#ifndef NSCSCC_M01_PERF_MONITOR_H
#define NSCSCC_M01_PERF_MONITOR_H

#include <cstdint>

class Vsimu_top;

// Simulation-only observer. It never drives a DUT signal and is compiled only
// into the instrumented Chiplab snapshot.
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
    static constexpr std::uint8_t kNoReason = 0xff;

    struct CycleSnapshot {
        std::uint8_t retired_count = 0;
        std::uint8_t retired_mask = 0;
        std::uint8_t commit_offer = 0;
        bool recovery = false;
        std::uint8_t recovery_cause = 0;
        bool exception = false;
        bool ertn = false;
        bool idle = false;
        bool refetch = false;
        std::uint8_t zero_reason = 1;
        std::uint8_t head_incomplete_reason = kNoReason;
        std::uint8_t head_complete_reason = kNoReason;
        std::uint8_t rob_occupancy = 0;
        std::uint8_t decode_valid = 0;
        bool frontend_cache_request = false;
        bool frontend_uncached_request = false;
        std::uint8_t issue_ready[4] = {};
        bool divider_busy = false;
        bool p1_ready_while_divider_busy = false;
        std::uint8_t divide_class = kNoReason;
        std::uint8_t lsq_events = 0;
        std::uint8_t axi_valid = 0;
        std::uint8_t axi_fire = 0;
        std::uint8_t axi_error = 0;
    };

    CycleSnapshot capture_snapshot();
    void accumulate_snapshot(const CycleSnapshot &snapshot, std::uint8_t retired_count);

    Vsimu_top *top_;
    std::uint64_t cycles_ = 0;
    std::uint64_t retire_hist_[4] = {0, 0, 0, 0};
    std::uint64_t sampled_instructions_ = 0;
    std::uint64_t observed_instructions_ = 0;
    std::uint64_t trace_signature_ = 1469598103934665603ULL;
    bool commit_cycle_pending_ = false;
    std::uint64_t sampling_protocol_errors_ = 0;
    std::uint64_t source_retire_alignment_errors_ = 0;
    std::uint64_t non_prefix_retire_ = 0;
    std::uint64_t commit_offer_vs_retired_cycles_ = 0;
    CycleSnapshot snapshot_history_[kCommitObservationLag] = {};
    std::uint64_t recovery_cycles_ = 0;
    std::uint64_t recovery_cause_[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    std::uint64_t zero_retire_[5] = {0, 0, 0, 0, 0};
    std::uint64_t head_incomplete_[19] = {};
    std::uint64_t head_complete_blocked_[6] = {};
    std::uint64_t exception_cycles_ = 0;
    std::uint64_t ertn_cycles_ = 0;
    std::uint64_t idle_cycles_ = 0;
    std::uint64_t refetch_cycles_ = 0;
    std::uint64_t rob_occupancy_sum_ = 0;
    std::uint64_t rob_occupancy_max_ = 0;
    std::uint64_t rob_full_cycles_ = 0;
    std::uint64_t rob_occupancy_hist_[33] = {};
    std::uint64_t frontend_decode_valid_sum_ = 0;
    std::uint64_t frontend_cache_request_ = 0;
    std::uint64_t frontend_uncached_request_ = 0;
    std::uint64_t issue_ready_cycles_[4] = {0, 0, 0, 0};
    std::uint64_t issue_ready_entries_[4] = {0, 0, 0, 0};
    std::uint64_t divider_busy_cycles_ = 0;
    std::uint64_t p1_ready_while_divider_busy_ = 0;
    std::uint64_t divide_classes_[5] = {0, 0, 0, 0, 0};
    bool previous_divider_busy_ = false;
    std::uint64_t lsq_events_[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    std::uint64_t axi_valid_[5] = {0, 0, 0, 0, 0};
    std::uint64_t axi_fire_[5] = {0, 0, 0, 0, 0};
    std::uint64_t axi_backpressure_[5] = {0, 0, 0, 0, 0};
    std::uint64_t axi_error_[2] = {0, 0};
};

// Called by the instrumented difftest adapter after it consumes a commit
// packet. The clean model has no reference to these functions.
void perf_monitor_begin_cycle();
void perf_monitor_cancel_cycle();
void perf_monitor_record_commit(std::uint64_t pc, std::uint32_t instruction,
                                std::uint8_t index);
void perf_monitor_record_commit_cycle(std::uint8_t count);

#endif
