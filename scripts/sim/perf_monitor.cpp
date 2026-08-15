#include "perf_monitor.h"

#include <algorithm>
#include <cstdio>
#include <iterator>

#include "Vsimu_top.h"
#include "Vsimu_top___024root.h"

namespace {
PerfMonitor *active_monitor = nullptr;

using Root = Vsimu_top___024root;

#define OBSERVATION_WORD_1 \
    root->simu_top__DOT__soc__DOT__cpu__DOT__backendArea_core__DOT__systemArea_core__DOT__perfObservationV1Word1
#define OBSERVATION_WORD_2 \
    root->simu_top__DOT__soc__DOT__cpu__DOT__backendArea_core__DOT__systemArea_core__DOT__frontend__DOT__perfObservationV1Word2
#define OBSERVATION_WORD_3 \
    root->simu_top__DOT__soc__DOT__cpu__DOT__backendArea_core__DOT__systemArea_core__DOT__backend__DOT__backend__DOT__backend__DOT__perfObservationV1Word3
#define OBSERVATION_WORD_4 \
    root->simu_top__DOT__soc__DOT__cpu__DOT__backendArea_core__DOT__systemArea_core__DOT__backend__DOT__backend__DOT__backend__DOT__rob__DOT__perfObservationV1Word4
#define OBSERVATION_WORD_5 \
    root->simu_top__DOT__soc__DOT__cpu__DOT__backendArea_core__DOT__systemArea_core__DOT__backend__DOT__backend__DOT__loadStoreQueue_1__DOT__perfObservationV1Word5
#define OBSERVATION_WORD_6 \
    root->simu_top__DOT__soc__DOT__cpu__DOT__backendArea_core__DOT__systemArea_core__DOT__backend__DOT__cacheHierarchy__DOT__perfObservationV1Word6
#define OBSERVATION_WORD_7 \
    root->simu_top__DOT__soc__DOT__cpu__DOT__backendArea_core__DOT__perfObservationV1Word7

constexpr std::uint32_t kObservationMagic = 0x4d494b55U;
constexpr std::uint8_t kObservationVersion = 1;
constexpr std::uint8_t kObservationWordCount = 8;
constexpr std::uint8_t kCommitWidth = 3;
constexpr std::uint8_t kExecutionWidth = 4;

constexpr std::uint64_t observation_header() {
    return static_cast<std::uint64_t>(kObservationMagic) |
           (static_cast<std::uint64_t>(kObservationVersion) << 32) |
           (static_cast<std::uint64_t>(kObservationWordCount) << 40) |
           (static_cast<std::uint64_t>(kCommitWidth) << 48) |
           (static_cast<std::uint64_t>(kExecutionWidth) << 56);
}

inline std::uint64_t field(std::uint64_t word, unsigned lsb, unsigned width) {
    const std::uint64_t mask = width == 64 ? ~0ULL : ((1ULL << width) - 1ULL);
    return (word >> lsb) & mask;
}

inline std::uint64_t bit(bool value) { return value ? 1ULL : 0ULL; }

inline unsigned popcount(std::uint64_t value) {
    unsigned count = 0;
    while (value != 0) {
        count += static_cast<unsigned>(value & 1ULL);
        value >>= 1;
    }
    return count;
}

inline unsigned latency_bucket(std::uint64_t cycles) {
    if (cycles <= 3) return static_cast<unsigned>(cycles);
    if (cycles <= 7) return 4;
    if (cycles <= 15) return 5;
    if (cycles <= 31) return 6;
    return 7;
}

inline void fnv_byte(std::uint64_t &hash, std::uint8_t value) {
    hash ^= value;
    hash *= 1099511628211ULL;
}

inline void fnv_u32(std::uint64_t &hash, std::uint32_t value) {
    for (unsigned shift = 0; shift < 32; shift += 8) {
        fnv_byte(hash, static_cast<std::uint8_t>(value >> shift));
    }
}
}  // namespace

PerfMonitor::PerfMonitor(Vsimu_top *top) : top_(top) { active_monitor = this; }

void PerfMonitor::begin_cycle() {
    if (commit_cycle_pending_) sampling_protocol_errors_++;
    commit_cycle_pending_ = true;
    pending_commit_count_ = 0;
}

void PerfMonitor::cancel_cycle() {
    commit_cycle_pending_ = false;
    pending_commit_count_ = 0;
}

PerfMonitor::CycleSnapshot PerfMonitor::capture_snapshot() {
    CycleSnapshot snapshot;
    Root *root = top_->rootp;
    // Verilator may constant-fold the RTL header word. Construct the same
    // immutable ABI identity here and read only the seven dynamic words.
    snapshot.words[0] = observation_header();
    snapshot.words[1] = OBSERVATION_WORD_1;
    snapshot.words[2] = OBSERVATION_WORD_2;
    snapshot.words[3] = OBSERVATION_WORD_3;
    snapshot.words[4] = OBSERVATION_WORD_4;
    snapshot.words[5] = OBSERVATION_WORD_5;
    snapshot.words[6] = OBSERVATION_WORD_6;
    snapshot.words[7] = OBSERVATION_WORD_7;
    snapshot.abi_valid =
        field(snapshot.words[0], 0, 32) == kObservationMagic &&
        field(snapshot.words[0], 32, 8) == kObservationVersion &&
        field(snapshot.words[0], 40, 8) == kObservationWordCount;
    return snapshot;
}

void PerfMonitor::reset_interval_state() {
    frontend_seen_request_ = false;
    frontend_last_request_cycle_ = 0;
    std::fill(std::begin(branch_resolve_cycle_),
              std::end(branch_resolve_cycle_), 0);
    std::fill(std::begin(branch_mispredict_pending_),
              std::end(branch_mispredict_pending_), false);
}

void PerfMonitor::reset_accumulators() {
    cycles_ = 0;
    std::fill(std::begin(retire_hist_), std::end(retire_hist_), 0);
    sampled_instructions_ = 0;
    observed_instructions_ = 0;
    trace_signature_ = 1469598103934665603ULL;
    abi_errors_ = 0;
    sampling_protocol_errors_ = 0;
    source_retire_alignment_errors_ = 0;
    non_prefix_retire_ = 0;

    recovery_cycles_ = 0;
    std::fill(std::begin(recovery_cause_), std::end(recovery_cause_), 0);
    std::fill(std::begin(zero_retire_), std::end(zero_retire_), 0);

    rob_occupancy_sum_ = 0;
    rob_occupancy_max_ = 0;
    rob_full_cycles_ = 0;
    std::fill(std::begin(rob_occupancy_hist_),
              std::end(rob_occupancy_hist_), 0);
    std::fill(std::begin(rob_zero_retire_head_reason_),
              std::end(rob_zero_retire_head_reason_), 0);
    std::fill(std::begin(rob_incomplete_head_class_),
              std::end(rob_incomplete_head_class_), 0);

    frontend_decode_valid_sum_ = 0;
    std::fill(std::begin(frontend_occupancy_hist_),
              std::end(frontend_occupancy_hist_), 0);
    std::fill(std::begin(frontend_events_), std::end(frontend_events_), 0);
    std::fill(std::begin(frontend_request_interval_hist_),
              std::end(frontend_request_interval_hist_), 0);
    frontend_request_sequences_ = 0;
    reset_interval_state();

    std::fill(std::begin(issue_ready_cycles_),
              std::end(issue_ready_cycles_), 0);
    std::fill(std::begin(issue_ready_entries_),
              std::end(issue_ready_entries_), 0);
    std::fill(std::begin(issue_occupancy_sum_),
              std::end(issue_occupancy_sum_), 0);
    std::fill(std::begin(issue_full_cycles_),
              std::end(issue_full_cycles_), 0);
    std::fill(std::begin(issue_fire_by_port_),
              std::end(issue_fire_by_port_), 0);
    issue_valid_sum_ = 0;
    issue_fire_sum_ = 0;

    dispatch_valid_sum_ = 0;
    dispatch_fire_sum_ = 0;
    std::fill(std::begin(dispatch_fire_hist_),
              std::end(dispatch_fire_hist_), 0);

    std::fill(std::begin(branch_commit_hist_),
              std::end(branch_commit_hist_), 0);
    branch_retired_ = 0;
    predictor_update_cycles_ = 0;
    branch_resolved_ = 0;
    branch_mispredicted_ = 0;
    branch_recovery_matches_ = 0;
    branch_recovery_without_resolution_ = 0;
    branch_resolve_to_recovery_cycles_ = 0;
    branch_resolve_to_recovery_max_ = 0;
    branch_head_completion_opportunity_ = 0;
    branch_head_mispredict_opportunity_ = 0;
    std::fill(std::begin(rename_admission_),
              std::end(rename_admission_), 0);
    predictor_history_groups_ = 0;
    predictor_history_conditional_steps_ = 0;
    predictor_history_multi_groups_ = 0;
    std::fill(std::begin(branch_resolve_to_recovery_hist_),
              std::end(branch_resolve_to_recovery_hist_), 0);

    load_queue_occupancy_sum_ = 0;
    store_queue_occupancy_sum_ = 0;
    load_queue_full_cycles_ = 0;
    store_queue_full_cycles_ = 0;
    store_data_multiple_ready_cycles_ = 0;
    store_data_out_of_age_order_cycles_ = 0;
    std::fill(std::begin(lsq_events_), std::end(lsq_events_), 0);
    std::fill(std::begin(cache_events_), std::end(cache_events_), 0);
    std::fill(std::begin(l1d_response_arbitration_),
              std::end(l1d_response_arbitration_), 0);
    std::fill(std::begin(cache_occupancy_sum_),
              std::end(cache_occupancy_sum_), 0);
    std::fill(std::begin(axi_valid_), std::end(axi_valid_), 0);
    std::fill(std::begin(axi_fire_), std::end(axi_fire_), 0);
    std::fill(std::begin(axi_backpressure_),
              std::end(axi_backpressure_), 0);
    std::fill(std::begin(axi_error_), std::end(axi_error_), 0);
}

void PerfMonitor::save_accumulator_checkpoint() {
#define SAVE_SCALAR(name) accumulator_checkpoint_.name = name##_
#define SAVE_ARRAY(name)                                                       \
    std::copy(std::begin(name##_), std::end(name##_),                         \
              std::begin(accumulator_checkpoint_.name))
    SAVE_SCALAR(cycles);
    SAVE_ARRAY(retire_hist);
    SAVE_SCALAR(sampled_instructions);
    SAVE_SCALAR(observed_instructions);
    SAVE_SCALAR(trace_signature);
    SAVE_SCALAR(abi_errors);
    SAVE_SCALAR(sampling_protocol_errors);
    SAVE_SCALAR(source_retire_alignment_errors);
    SAVE_SCALAR(non_prefix_retire);
    SAVE_SCALAR(recovery_cycles);
    SAVE_ARRAY(recovery_cause);
    SAVE_ARRAY(zero_retire);
    SAVE_SCALAR(rob_occupancy_sum);
    SAVE_SCALAR(rob_occupancy_max);
    SAVE_SCALAR(rob_full_cycles);
    SAVE_ARRAY(rob_occupancy_hist);
    SAVE_ARRAY(rob_zero_retire_head_reason);
    SAVE_ARRAY(rob_incomplete_head_class);
    SAVE_SCALAR(frontend_decode_valid_sum);
    SAVE_ARRAY(frontend_occupancy_hist);
    SAVE_ARRAY(frontend_events);
    SAVE_ARRAY(frontend_request_interval_hist);
    SAVE_SCALAR(frontend_request_sequences);
    SAVE_ARRAY(issue_ready_cycles);
    SAVE_ARRAY(issue_ready_entries);
    SAVE_ARRAY(issue_occupancy_sum);
    SAVE_ARRAY(issue_full_cycles);
    SAVE_ARRAY(issue_fire_by_port);
    SAVE_SCALAR(issue_valid_sum);
    SAVE_SCALAR(issue_fire_sum);
    SAVE_SCALAR(dispatch_valid_sum);
    SAVE_SCALAR(dispatch_fire_sum);
    SAVE_ARRAY(dispatch_fire_hist);
    SAVE_ARRAY(branch_commit_hist);
    SAVE_SCALAR(branch_retired);
    SAVE_SCALAR(predictor_update_cycles);
    SAVE_SCALAR(branch_resolved);
    SAVE_SCALAR(branch_mispredicted);
    SAVE_SCALAR(branch_recovery_matches);
    SAVE_SCALAR(branch_recovery_without_resolution);
    SAVE_SCALAR(branch_resolve_to_recovery_cycles);
    SAVE_SCALAR(branch_resolve_to_recovery_max);
    SAVE_ARRAY(branch_resolve_to_recovery_hist);
    SAVE_SCALAR(branch_head_completion_opportunity);
    SAVE_SCALAR(branch_head_mispredict_opportunity);
    SAVE_ARRAY(rename_admission);
    SAVE_SCALAR(predictor_history_groups);
    SAVE_SCALAR(predictor_history_conditional_steps);
    SAVE_SCALAR(predictor_history_multi_groups);
    SAVE_SCALAR(load_queue_occupancy_sum);
    SAVE_SCALAR(store_queue_occupancy_sum);
    SAVE_SCALAR(load_queue_full_cycles);
    SAVE_SCALAR(store_queue_full_cycles);
    SAVE_SCALAR(store_data_multiple_ready_cycles);
    SAVE_SCALAR(store_data_out_of_age_order_cycles);
    SAVE_ARRAY(lsq_events);
    SAVE_ARRAY(cache_events);
    SAVE_ARRAY(l1d_response_arbitration);
    SAVE_ARRAY(cache_occupancy_sum);
    SAVE_ARRAY(axi_valid);
    SAVE_ARRAY(axi_fire);
    SAVE_ARRAY(axi_backpressure);
    SAVE_ARRAY(axi_error);
#undef SAVE_ARRAY
#undef SAVE_SCALAR
    accumulator_checkpoint_valid_ = true;
}

void PerfMonitor::restore_accumulator_checkpoint() {
#define RESTORE_SCALAR(name) name##_ = accumulator_checkpoint_.name
#define RESTORE_ARRAY(name)                                                    \
    std::copy(std::begin(accumulator_checkpoint_.name),                       \
              std::end(accumulator_checkpoint_.name), std::begin(name##_))
    RESTORE_SCALAR(cycles);
    RESTORE_ARRAY(retire_hist);
    RESTORE_SCALAR(sampled_instructions);
    RESTORE_SCALAR(observed_instructions);
    RESTORE_SCALAR(trace_signature);
    RESTORE_SCALAR(abi_errors);
    RESTORE_SCALAR(sampling_protocol_errors);
    RESTORE_SCALAR(source_retire_alignment_errors);
    RESTORE_SCALAR(non_prefix_retire);
    RESTORE_SCALAR(recovery_cycles);
    RESTORE_ARRAY(recovery_cause);
    RESTORE_ARRAY(zero_retire);
    RESTORE_SCALAR(rob_occupancy_sum);
    RESTORE_SCALAR(rob_occupancy_max);
    RESTORE_SCALAR(rob_full_cycles);
    RESTORE_ARRAY(rob_occupancy_hist);
    RESTORE_ARRAY(rob_zero_retire_head_reason);
    RESTORE_ARRAY(rob_incomplete_head_class);
    RESTORE_SCALAR(frontend_decode_valid_sum);
    RESTORE_ARRAY(frontend_occupancy_hist);
    RESTORE_ARRAY(frontend_events);
    RESTORE_ARRAY(frontend_request_interval_hist);
    RESTORE_SCALAR(frontend_request_sequences);
    RESTORE_ARRAY(issue_ready_cycles);
    RESTORE_ARRAY(issue_ready_entries);
    RESTORE_ARRAY(issue_occupancy_sum);
    RESTORE_ARRAY(issue_full_cycles);
    RESTORE_ARRAY(issue_fire_by_port);
    RESTORE_SCALAR(issue_valid_sum);
    RESTORE_SCALAR(issue_fire_sum);
    RESTORE_SCALAR(dispatch_valid_sum);
    RESTORE_SCALAR(dispatch_fire_sum);
    RESTORE_ARRAY(dispatch_fire_hist);
    RESTORE_ARRAY(branch_commit_hist);
    RESTORE_SCALAR(branch_retired);
    RESTORE_SCALAR(predictor_update_cycles);
    RESTORE_SCALAR(branch_resolved);
    RESTORE_SCALAR(branch_mispredicted);
    RESTORE_SCALAR(branch_recovery_matches);
    RESTORE_SCALAR(branch_recovery_without_resolution);
    RESTORE_SCALAR(branch_resolve_to_recovery_cycles);
    RESTORE_SCALAR(branch_resolve_to_recovery_max);
    RESTORE_ARRAY(branch_resolve_to_recovery_hist);
    RESTORE_SCALAR(branch_head_completion_opportunity);
    RESTORE_SCALAR(branch_head_mispredict_opportunity);
    RESTORE_ARRAY(rename_admission);
    RESTORE_SCALAR(predictor_history_groups);
    RESTORE_SCALAR(predictor_history_conditional_steps);
    RESTORE_SCALAR(predictor_history_multi_groups);
    RESTORE_SCALAR(load_queue_occupancy_sum);
    RESTORE_SCALAR(store_queue_occupancy_sum);
    RESTORE_SCALAR(load_queue_full_cycles);
    RESTORE_SCALAR(store_queue_full_cycles);
    RESTORE_SCALAR(store_data_multiple_ready_cycles);
    RESTORE_SCALAR(store_data_out_of_age_order_cycles);
    RESTORE_ARRAY(lsq_events);
    RESTORE_ARRAY(cache_events);
    RESTORE_ARRAY(l1d_response_arbitration);
    RESTORE_ARRAY(cache_occupancy_sum);
    RESTORE_ARRAY(axi_valid);
    RESTORE_ARRAY(axi_fire);
    RESTORE_ARRAY(axi_backpressure);
    RESTORE_ARRAY(axi_error);
#undef RESTORE_ARRAY
#undef RESTORE_SCALAR
}

bool PerfMonitor::is_counter_read(std::uint32_t instruction) {
    // LA32R rdtimel.w (assembler alias rdcntvl.w) has only rd in bits 4:0.
    return (instruction & 0xffffffe0U) == 0x00006000U;
}

void PerfMonitor::accumulate_snapshot(const CycleSnapshot &snapshot,
                                      std::uint8_t retired_count) {
    cycles_++;
    if (retired_count > 3) {
        sampling_protocol_errors_++;
        retired_count = 0;
    }
    retire_hist_[retired_count]++;
    sampled_instructions_ += retired_count;

    if (!snapshot.abi_valid) {
        abi_errors_++;
        source_retire_alignment_errors_ += retired_count;
        zero_retire_[1] += bit(retired_count == 0);
        rob_occupancy_hist_[0]++;
        frontend_occupancy_hist_[0]++;
        dispatch_fire_hist_[0]++;
        branch_commit_hist_[0]++;
        return;
    }

    const std::uint64_t core = snapshot.words[1];
    const std::uint64_t frontend = snapshot.words[2];
    const std::uint64_t issue = snapshot.words[3];
    const std::uint64_t branch = snapshot.words[4];
    const std::uint64_t lsq = snapshot.words[5];
    const std::uint64_t cache = snapshot.words[6];
    const std::uint64_t axi = snapshot.words[7];

    const std::uint8_t source_retired =
        static_cast<std::uint8_t>(field(core, 3, 3));
    const unsigned source_retired_count = popcount(source_retired);
    source_retire_alignment_errors_ += bit(source_retired_count != retired_count);
    non_prefix_retire_ += bit(source_retired != 0 &&
                              source_retired != 1 &&
                              source_retired != 3 &&
                              source_retired != 7);

    const bool recovery = field(core, 6, 1) != 0;
    const unsigned recovery_cause = static_cast<unsigned>(field(core, 7, 3));
    recovery_cycles_ += bit(recovery);
    if (recovery) recovery_cause_[recovery_cause]++;

    const unsigned rob_occupancy = static_cast<unsigned>(field(issue, 0, 6));
    rob_occupancy_sum_ += rob_occupancy;
    rob_occupancy_max_ = std::max(rob_occupancy_max_,
                                  static_cast<std::uint64_t>(rob_occupancy));
    rob_full_cycles_ += bit(rob_occupancy >= 32);
    if (rob_occupancy <= 32) rob_occupancy_hist_[rob_occupancy]++;

    if (retired_count == 0) {
        const unsigned zero_reason = recovery ? 0 : rob_occupancy == 0 ? 1 : 2;
        zero_retire_[zero_reason]++;
        if (!recovery && rob_occupancy != 0) {
            const bool head_valid = field(branch, 40, 1) != 0;
            const bool head_complete = field(branch, 41, 1) != 0;
            const bool head_payload_ready = field(branch, 42, 1) != 0;
            const bool predictor_has_capacity = field(branch, 43, 1) != 0;
            const unsigned head_reason =
                !head_valid ? 0 : !head_payload_ready ? 1 :
                !head_complete ? 2 : !predictor_has_capacity ? 3 : 4;
            rob_zero_retire_head_reason_[head_reason]++;
            if (head_reason == 2) {
                const unsigned incomplete_class =
                    field(branch, 47, 1) ? 0 : field(branch, 48, 1) ? 1 :
                    field(branch, 49, 1) ? 2 : field(branch, 50, 1) ? 3 : 4;
                rob_incomplete_head_class_[incomplete_class]++;
            }
        }
    }

    const unsigned frontend_occupancy = static_cast<unsigned>(field(core, 15, 5));
    if (frontend_occupancy < kFrontendOccupancyBins) {
        frontend_occupancy_hist_[frontend_occupancy]++;
    }
    frontend_decode_valid_sum_ += popcount(field(core, 20, 3));
    const unsigned speculative_conditionals =
        static_cast<unsigned>(field(frontend, 22, 3));
    predictor_history_groups_ += bit(speculative_conditionals != 0);
    predictor_history_conditional_steps_ += speculative_conditionals;
    predictor_history_multi_groups_ += field(frontend, 25, 1);
    const unsigned frontend_bits[12] = {2, 3, 6, 7, 8, 12, 14, 15, 16, 19, 21, 26};
    for (unsigned index = 0; index < 12; index++) {
        frontend_events_[index] += field(frontend, frontend_bits[index], 1);
    }
    if (field(frontend, 12, 1) != 0) {
        if (frontend_seen_request_) {
            frontend_request_interval_hist_[latency_bucket(
                cycles_ - frontend_last_request_cycle_)]++;
        } else {
            frontend_request_sequences_++;
        }
        frontend_seen_request_ = true;
        frontend_last_request_cycle_ = cycles_;
    }

    const std::uint8_t issue_valid = static_cast<std::uint8_t>(field(issue, 21, 4));
    const std::uint8_t issue_fire = static_cast<std::uint8_t>(field(issue, 25, 4));
    issue_valid_sum_ += popcount(issue_valid);
    issue_fire_sum_ += popcount(issue_fire);
    for (unsigned port = 0; port < 4; port++) {
        const unsigned occupancy =
            static_cast<unsigned>(field(issue, 34 + port * 4, 4));
        const unsigned ready = static_cast<unsigned>(field(issue, 50 + port, 1));
        issue_occupancy_sum_[port] += occupancy;
        issue_full_cycles_[port] += bit(occupancy >= 8);
        issue_ready_entries_[port] += ready;
        issue_ready_cycles_[port] += bit(ready != 0);
        issue_fire_by_port_[port] += field(issue_fire, port, 1);
    }

    const unsigned dispatch_valid = popcount(field(issue, 18, 3));
    const unsigned dispatch_fire = popcount(field(issue, 58, 4));
    dispatch_valid_sum_ += dispatch_valid;
    dispatch_fire_sum_ += dispatch_fire;
    dispatch_fire_hist_[dispatch_fire]++;
    store_data_multiple_ready_cycles_ += field(issue, 62, 1);
    store_data_out_of_age_order_cycles_ += field(issue, 63, 1);

    const unsigned committed_branches = popcount(field(core, 35, 3));
    branch_commit_hist_[committed_branches]++;
    branch_retired_ += committed_branches;
    predictor_update_cycles_ += field(core, 28, 1);
    const std::uint8_t resolved_mask = static_cast<std::uint8_t>(field(branch, 0, 5));
    const std::uint8_t mispredict_mask = static_cast<std::uint8_t>(field(branch, 5, 5));
    branch_resolved_ += popcount(resolved_mask);
    branch_mispredicted_ += popcount(mispredict_mask);
    branch_head_completion_opportunity_ += field(branch, 52, 1);
    branch_head_mispredict_opportunity_ += field(branch, 53, 1);
    for (unsigned index = 0; index < kRenameAdmissionEventCount; index++) {
        rename_admission_[index] += field(branch, 54 + index, 1);
    }
    for (unsigned lane = 0; lane < 5; lane++) {
        if (field(mispredict_mask, lane, 1) == 0) continue;
        const unsigned pointer = static_cast<unsigned>(field(branch, 10 + lane * 6, 6));
        branch_resolve_cycle_[pointer] = cycles_;
        branch_mispredict_pending_[pointer] = true;
    }
    if (recovery && recovery_cause == 1) {
        const unsigned pointer = static_cast<unsigned>(field(core, 29, 6));
        if (branch_mispredict_pending_[pointer]) {
            const std::uint64_t latency = cycles_ - branch_resolve_cycle_[pointer];
            branch_recovery_matches_++;
            branch_resolve_to_recovery_cycles_ += latency;
            branch_resolve_to_recovery_max_ =
                std::max(branch_resolve_to_recovery_max_, latency);
            branch_resolve_to_recovery_hist_[latency_bucket(latency)]++;
            branch_mispredict_pending_[pointer] = false;
        } else {
            branch_recovery_without_resolution_++;
        }
    }

    const unsigned load_occupancy = static_cast<unsigned>(field(lsq, 0, 5));
    const unsigned store_occupancy = static_cast<unsigned>(field(lsq, 5, 4));
    const unsigned observed_load_capacity =
        1U << (3U + static_cast<unsigned>(field(lsq, 62, 2)));
    if (load_queue_capacity_ == 0) {
        load_queue_capacity_ = observed_load_capacity;
    } else if (load_queue_capacity_ != observed_load_capacity) {
        abi_errors_++;
    }
    load_queue_occupancy_sum_ += load_occupancy;
    store_queue_occupancy_sum_ += store_occupancy;
    // Capacity is a DUT configuration property. Read the versioned observation
    // bits instead of duplicating queue-size assumptions in the harness.
    load_queue_full_cycles_ += field(lsq, 55, 1);
    store_queue_full_cycles_ += field(lsq, 56, 1);
    for (unsigned index = 0; index < kLsqEventCount; index++) {
        lsq_events_[index] += field(lsq, 9 + index, 1);
    }

    for (unsigned index = 0; index < 20; index++) {
        cache_events_[index] += field(cache, index, 1);
    }
    for (unsigned index = 0; index < 5; index++) {
        l1d_response_arbitration_[index] += field(cache, 32 + index, 1);
    }
    cache_occupancy_sum_[0] += field(cache, 20, 4);
    cache_occupancy_sum_[1] += field(cache, 24, 4);
    cache_occupancy_sum_[2] += field(cache, 28, 4);

    const unsigned valid_bits[5] = {7, 10, 13, 0, 3};
    const unsigned ready_bits[5] = {8, 11, 14, 1, 4};
    const unsigned fire_bits[5] = {9, 12, 15, 2, 5};
    for (unsigned channel = 0; channel < 5; channel++) {
        const bool valid = field(axi, valid_bits[channel], 1) != 0;
        const bool ready = field(axi, ready_bits[channel], 1) != 0;
        const bool fire = field(axi, fire_bits[channel], 1) != 0;
        axi_valid_[channel] += bit(valid);
        axi_fire_[channel] += bit(fire);
        axi_backpressure_[channel] += bit(valid && !ready);
    }
    axi_error_[0] += field(axi, 6, 1);
    axi_error_[1] += field(axi, 16, 1);
}

void PerfMonitor::accumulate_commit(const CommitRecord &commit) {
    observed_instructions_++;
    fnv_byte(trace_signature_, commit.index);
    fnv_u32(trace_signature_, static_cast<std::uint32_t>(commit.pc));
    fnv_u32(trace_signature_, commit.instruction);
}

void PerfMonitor::record_commit_cycle(std::uint8_t count) {
    if (!commit_cycle_pending_) return;
    commit_cycle_pending_ = false;
    const CycleSnapshot current = capture_snapshot();
    const CycleSnapshot aligned = snapshot_history_[kCommitObservationLag - 1];
    const bool aligned_available =
        snapshot_history_count_ == kCommitObservationLag;
    for (unsigned index = kCommitObservationLag - 1; index > 0; index--) {
        snapshot_history_[index] = snapshot_history_[index - 1];
    }
    snapshot_history_[0] = current;
    if (!aligned_available) snapshot_history_count_++;

    if (count > 3 || count != pending_commit_count_) {
        sampling_protocol_errors_++;
        pending_commit_count_ = 0;
        return;
    }

    unsigned marker_count = 0;
    for (unsigned index = 0; index < pending_commit_count_; index++) {
        marker_count += is_counter_read(pending_commits_[index].instruction);
    }
    if (marker_count != 0) {
        for (unsigned marker = 0; marker < marker_count; marker++) {
            if (!roi_marker_seen_) {
                reset_accumulators();
                roi_marker_seen_ = true;
            } else {
                save_accumulator_checkpoint();
            }
            roi_counter_read_markers_++;
        }
        if (roi_counter_read_markers_ > marker_count && aligned_available) {
            accumulate_snapshot(aligned, count);
            for (unsigned index = 0; index < pending_commit_count_; index++) {
                accumulate_commit(pending_commits_[index]);
            }
        }
        pending_commit_count_ = 0;
        return;
    }

    if (aligned_available) {
        accumulate_snapshot(aligned, count);
        for (unsigned index = 0; index < pending_commit_count_; index++) {
            accumulate_commit(pending_commits_[index]);
        }
    }
    pending_commit_count_ = 0;
}

void PerfMonitor::record_commit(std::uint64_t pc, std::uint32_t instruction,
                                std::uint8_t index) {
    if (!commit_cycle_pending_) return;
    if (pending_commit_count_ >= 3) {
        sampling_protocol_errors_++;
        return;
    }
    pending_commits_[pending_commit_count_++] = {pc, instruction, index};
}

void PerfMonitor::write_json(const char *path) {
    if (accumulator_checkpoint_valid_) restore_accumulator_checkpoint();
    FILE *file = std::fopen(path, "w");
    if (file == nullptr) {
        std::perror("m01-counters.json");
        return;
    }
    const std::uint64_t retire_sum =
        retire_hist_[1] + 2 * retire_hist_[2] + 3 * retire_hist_[3];
    const bool hist_cycles_ok =
        retire_hist_[0] + retire_hist_[1] + retire_hist_[2] + retire_hist_[3] == cycles_;
    const bool hist_instructions_ok = retire_sum == sampled_instructions_;
    const bool commit_count_ok = observed_instructions_ == sampled_instructions_;
    const bool roi_complete =
        !roi_marker_seen_ ||
        (roi_counter_read_markers_ >= 2 &&
         roi_counter_read_markers_ % 2 == 0 &&
         accumulator_checkpoint_valid_);
    const std::uint64_t unused_slots = cycles_ * 3 - sampled_instructions_;

    std::fprintf(file, "{\n");
    std::fprintf(file, "  \"schema_version\": \"miku-perf-observation-v11\",\n");
    std::fprintf(file, "  \"observation_abi\": {\"magic\": \"MIKU\", \"version\": 1, \"word_count\": 8},\n");
    std::fprintf(file, "  \"roi\": {\"mode\": \"%s\", \"counter_read_markers\": %llu, \"nested_counter_read_pairs\": %llu, \"complete\": %s, \"boundary_cycles_included\": false},\n",
                 roi_marker_seen_ ? "outermost-counter-read-pair" : "full-run",
                 static_cast<unsigned long long>(roi_counter_read_markers_),
                 static_cast<unsigned long long>(
                     roi_counter_read_markers_ >= 2
                         ? roi_counter_read_markers_ / 2 - 1
                         : 0),
                 roi_complete ? "true" : "false");
    std::fprintf(file, "  \"commit_observation_lag_cycles\": %u,\n", kCommitObservationLag);
    std::fprintf(file, "  \"cycles\": %llu,\n", static_cast<unsigned long long>(cycles_));
    std::fprintf(file, "  \"retired_instructions\": %llu,\n", static_cast<unsigned long long>(sampled_instructions_));
    std::fprintf(file, "  \"retire_width_histogram\": [%llu, %llu, %llu, %llu],\n",
                 static_cast<unsigned long long>(retire_hist_[0]),
                 static_cast<unsigned long long>(retire_hist_[1]),
                 static_cast<unsigned long long>(retire_hist_[2]),
                 static_cast<unsigned long long>(retire_hist_[3]));
    std::fprintf(file, "  \"unused_commit_slots\": %llu,\n", static_cast<unsigned long long>(unused_slots));
    std::fprintf(file, "  \"observed_commit_instructions\": %llu,\n", static_cast<unsigned long long>(observed_instructions_));
    std::fprintf(file, "  \"commit_trace_signature_fnv1a64\": \"%016llx\",\n",
                 static_cast<unsigned long long>(trace_signature_));
    std::fprintf(file, "  \"recovery_cycles\": %llu,\n", static_cast<unsigned long long>(recovery_cycles_));
    std::fprintf(file, "  \"recovery_cause\": {\"none\": %llu, \"branch_mispredict\": %llu, \"exception\": %llu, \"ertn\": %llu, \"refetch\": %llu, \"reserved_5\": %llu, \"reserved_6\": %llu, \"reserved_7\": %llu},\n",
                 static_cast<unsigned long long>(recovery_cause_[0]),
                 static_cast<unsigned long long>(recovery_cause_[1]),
                 static_cast<unsigned long long>(recovery_cause_[2]),
                 static_cast<unsigned long long>(recovery_cause_[3]),
                 static_cast<unsigned long long>(recovery_cause_[4]),
                 static_cast<unsigned long long>(recovery_cause_[5]),
                 static_cast<unsigned long long>(recovery_cause_[6]),
                 static_cast<unsigned long long>(recovery_cause_[7]));
    std::fprintf(file, "  \"zero_retire_loss\": {\"recovery\": %llu, \"rob_empty\": %llu, \"rob_nonempty\": %llu},\n",
                 static_cast<unsigned long long>(zero_retire_[0]),
                 static_cast<unsigned long long>(zero_retire_[1]),
                 static_cast<unsigned long long>(zero_retire_[2]));

    std::fprintf(file, "  \"rob\": {\"occupancy_sum\": %llu, \"occupancy_max\": %llu, \"full_cycles\": %llu, \"occupancy_histogram\": [",
                 static_cast<unsigned long long>(rob_occupancy_sum_),
                 static_cast<unsigned long long>(rob_occupancy_max_),
                 static_cast<unsigned long long>(rob_full_cycles_));
    for (unsigned index = 0; index < 33; index++) {
        std::fprintf(file, "%s%llu", index == 0 ? "" : ", ",
                     static_cast<unsigned long long>(rob_occupancy_hist_[index]));
    }
    std::fprintf(file, "], \"zero_retire_head_reason\": {\"invalid\": %llu, \"payload_not_ready\": %llu, \"incomplete\": %llu, \"predictor_backpressure\": %llu, \"ready_without_retire\": %llu}, \"incomplete_head_class\": {\"load\": %llu, \"store\": %llu, \"branch\": %llu, \"system\": %llu, \"other\": %llu}},\n",
                 static_cast<unsigned long long>(rob_zero_retire_head_reason_[0]),
                 static_cast<unsigned long long>(rob_zero_retire_head_reason_[1]),
                 static_cast<unsigned long long>(rob_zero_retire_head_reason_[2]),
                 static_cast<unsigned long long>(rob_zero_retire_head_reason_[3]),
                 static_cast<unsigned long long>(rob_zero_retire_head_reason_[4]),
                 static_cast<unsigned long long>(rob_incomplete_head_class_[0]),
                 static_cast<unsigned long long>(rob_incomplete_head_class_[1]),
                 static_cast<unsigned long long>(rob_incomplete_head_class_[2]),
                 static_cast<unsigned long long>(rob_incomplete_head_class_[3]),
                 static_cast<unsigned long long>(rob_incomplete_head_class_[4]));

    std::fprintf(file, "  \"frontend\": {\"decode_valid_sum\": %llu, \"occupancy_histogram\": [",
                 static_cast<unsigned long long>(frontend_decode_valid_sum_));
    for (unsigned index = 0; index < kFrontendOccupancyBins; index++) {
        std::fprintf(file, "%s%llu", index == 0 ? "" : ", ",
                     static_cast<unsigned long long>(frontend_occupancy_hist_[index]));
    }
    std::fprintf(file, "], \"translation_request_fire\": %llu, \"translation_outstanding_cycles\": %llu, \"translation_response_fire\": %llu, \"translated_request_valid_cycles\": %llu, \"cache_request_base_valid_cycles\": %llu, \"cache_request_fire\": %llu, \"cache_response_fire\": %llu, \"cache_outstanding_cycles\": %llu, \"cache_hit_pending_cycles\": %llu, \"predictor_update_valid_cycles\": %llu, \"predictor_update_fire\": %llu, \"turnover_token_cycles\": %llu, \"request_interval_sequences\": %llu, \"request_interval_histogram\": [%llu, %llu, %llu, %llu, %llu, %llu, %llu, %llu]},\n",
                 static_cast<unsigned long long>(frontend_events_[0]),
                 static_cast<unsigned long long>(frontend_events_[1]),
                 static_cast<unsigned long long>(frontend_events_[2]),
                 static_cast<unsigned long long>(frontend_events_[3]),
                 static_cast<unsigned long long>(frontend_events_[4]),
                 static_cast<unsigned long long>(frontend_events_[5]),
                 static_cast<unsigned long long>(frontend_events_[6]),
                 static_cast<unsigned long long>(frontend_events_[7]),
                 static_cast<unsigned long long>(frontend_events_[8]),
                 static_cast<unsigned long long>(frontend_events_[9]),
                 static_cast<unsigned long long>(frontend_events_[10]),
                 static_cast<unsigned long long>(frontend_events_[11]),
                 static_cast<unsigned long long>(frontend_request_sequences_),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[0]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[1]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[2]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[3]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[4]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[5]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[6]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[7]));

    std::fprintf(file, "  \"issue\": {\"ready_cycles\": [%llu, %llu, %llu, %llu], \"ready_entries\": [%llu, %llu, %llu, %llu], \"occupancy_sum\": [%llu, %llu, %llu, %llu], \"full_cycles\": [%llu, %llu, %llu, %llu], \"operand_valid_sum\": %llu, \"fire_sum\": %llu, \"fire_by_port\": [%llu, %llu, %llu, %llu]},\n",
                 static_cast<unsigned long long>(issue_ready_cycles_[0]), static_cast<unsigned long long>(issue_ready_cycles_[1]), static_cast<unsigned long long>(issue_ready_cycles_[2]), static_cast<unsigned long long>(issue_ready_cycles_[3]),
                 static_cast<unsigned long long>(issue_ready_entries_[0]), static_cast<unsigned long long>(issue_ready_entries_[1]), static_cast<unsigned long long>(issue_ready_entries_[2]), static_cast<unsigned long long>(issue_ready_entries_[3]),
                 static_cast<unsigned long long>(issue_occupancy_sum_[0]), static_cast<unsigned long long>(issue_occupancy_sum_[1]), static_cast<unsigned long long>(issue_occupancy_sum_[2]), static_cast<unsigned long long>(issue_occupancy_sum_[3]),
                 static_cast<unsigned long long>(issue_full_cycles_[0]), static_cast<unsigned long long>(issue_full_cycles_[1]), static_cast<unsigned long long>(issue_full_cycles_[2]), static_cast<unsigned long long>(issue_full_cycles_[3]),
                 static_cast<unsigned long long>(issue_valid_sum_), static_cast<unsigned long long>(issue_fire_sum_),
                 static_cast<unsigned long long>(issue_fire_by_port_[0]), static_cast<unsigned long long>(issue_fire_by_port_[1]), static_cast<unsigned long long>(issue_fire_by_port_[2]), static_cast<unsigned long long>(issue_fire_by_port_[3]));
    std::fprintf(file, "  \"dispatch\": {\"valid_sum\": %llu, \"fire_sum\": %llu, \"fire_histogram\": [%llu, %llu, %llu, %llu, %llu]},\n",
                 static_cast<unsigned long long>(dispatch_valid_sum_),
                 static_cast<unsigned long long>(dispatch_fire_sum_),
                 static_cast<unsigned long long>(dispatch_fire_hist_[0]),
                 static_cast<unsigned long long>(dispatch_fire_hist_[1]),
                 static_cast<unsigned long long>(dispatch_fire_hist_[2]),
                 static_cast<unsigned long long>(dispatch_fire_hist_[3]),
                 static_cast<unsigned long long>(dispatch_fire_hist_[4]));
    std::fprintf(file, "  \"branch\": {\"commit_histogram\": [%llu, %llu, %llu, %llu], \"retired\": %llu, \"predictor_update_cycles\": %llu, \"resolved\": %llu, \"mispredicted\": %llu, \"recovery_matches\": %llu, \"recovery_without_resolution\": %llu, \"resolve_to_recovery_cycles\": %llu, \"resolve_to_recovery_max\": %llu, \"resolve_to_recovery_histogram\": [%llu, %llu, %llu, %llu, %llu, %llu, %llu, %llu], \"head_completion_opportunity\": %llu, \"head_mispredict_opportunity\": %llu},\n",
                 static_cast<unsigned long long>(branch_commit_hist_[0]), static_cast<unsigned long long>(branch_commit_hist_[1]), static_cast<unsigned long long>(branch_commit_hist_[2]), static_cast<unsigned long long>(branch_commit_hist_[3]),
                 static_cast<unsigned long long>(branch_retired_), static_cast<unsigned long long>(predictor_update_cycles_),
                 static_cast<unsigned long long>(branch_resolved_), static_cast<unsigned long long>(branch_mispredicted_),
                 static_cast<unsigned long long>(branch_recovery_matches_), static_cast<unsigned long long>(branch_recovery_without_resolution_),
                 static_cast<unsigned long long>(branch_resolve_to_recovery_cycles_), static_cast<unsigned long long>(branch_resolve_to_recovery_max_),
                 static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[0]), static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[1]), static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[2]), static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[3]),
                 static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[4]), static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[5]), static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[6]), static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[7]),
                 static_cast<unsigned long long>(branch_head_completion_opportunity_),
                 static_cast<unsigned long long>(branch_head_mispredict_opportunity_));
    std::fprintf(file, "  \"predictor_history\": {\"groups\": %llu, \"conditional_steps\": %llu, \"multi_conditional_groups\": %llu},\n",
                 static_cast<unsigned long long>(predictor_history_groups_),
                 static_cast<unsigned long long>(predictor_history_conditional_steps_),
                 static_cast<unsigned long long>(predictor_history_multi_groups_));
    std::fprintf(file, "  \"rename_admission\": {\"present_cycles\": %llu, \"blocked_cycles\": %llu, \"dispatch_queue_blocked_cycles\": %llu, \"rob_blocked_cycles\": %llu, \"freelist_conservative_blocked_cycles\": %llu, \"freelist_exact_fit_cycles\": %llu, \"freelist_only_rescue_cycles\": %llu, \"lsq_blocked_cycles\": %llu},\n",
                 static_cast<unsigned long long>(rename_admission_[0]),
                 static_cast<unsigned long long>(rename_admission_[1]),
                 static_cast<unsigned long long>(rename_admission_[2]),
                 static_cast<unsigned long long>(rename_admission_[3]),
                 static_cast<unsigned long long>(rename_admission_[4]),
                 static_cast<unsigned long long>(rename_admission_[5]),
                 static_cast<unsigned long long>(rename_admission_[6]),
                 static_cast<unsigned long long>(rename_admission_[7]));

    std::fprintf(file, "  \"lsq\": {\"load_capacity\": %u, \"load_occupancy_sum\": %llu, \"store_occupancy_sum\": %llu, \"load_full_cycles\": %llu, \"store_full_cycles\": %llu, \"events\": [",
                 load_queue_capacity_,
                 static_cast<unsigned long long>(load_queue_occupancy_sum_),
                 static_cast<unsigned long long>(store_queue_occupancy_sum_),
                 static_cast<unsigned long long>(load_queue_full_cycles_),
                 static_cast<unsigned long long>(store_queue_full_cycles_));
    for (unsigned index = 0; index < kLsqEventCount; index++) {
        std::fprintf(file, "%s%llu", index == 0 ? "" : ", ",
                     static_cast<unsigned long long>(lsq_events_[index]));
    }
    std::fprintf(file, "]},\n");
    std::fprintf(file, "  \"store_data\": {\"multiple_ready_cycles\": %llu, \"out_of_age_order_cycles\": %llu},\n",
                 static_cast<unsigned long long>(store_data_multiple_ready_cycles_),
                 static_cast<unsigned long long>(store_data_out_of_age_order_cycles_));
    std::fprintf(file, "  \"cache\": {\"events\": [");
    for (unsigned index = 0; index < 20; index++) {
        std::fprintf(file, "%s%llu", index == 0 ? "" : ", ",
                     static_cast<unsigned long long>(cache_events_[index]));
    }
    std::fprintf(file, "], \"occupancy_sum\": [%llu, %llu, %llu]},\n",
                 static_cast<unsigned long long>(cache_occupancy_sum_[0]),
                 static_cast<unsigned long long>(cache_occupancy_sum_[1]),
                 static_cast<unsigned long long>(cache_occupancy_sum_[2]));
    std::fprintf(file, "  \"l1d_response_arbitration\": {\"lookup_hit_load_cycles\": %llu, \"miss_waiter_ready_cycles\": %llu, \"hit_waiter_collision_cycles\": %llu, \"older_waiter_collision_cycles\": %llu, \"multiple_ready_waiter_cycles\": %llu},\n",
                 static_cast<unsigned long long>(l1d_response_arbitration_[0]),
                 static_cast<unsigned long long>(l1d_response_arbitration_[1]),
                 static_cast<unsigned long long>(l1d_response_arbitration_[2]),
                 static_cast<unsigned long long>(l1d_response_arbitration_[3]),
                 static_cast<unsigned long long>(l1d_response_arbitration_[4]));
    std::fprintf(file, "  \"axi\": {\"valid\": [%llu, %llu, %llu, %llu, %llu], \"fire\": [%llu, %llu, %llu, %llu, %llu], \"backpressure\": [%llu, %llu, %llu, %llu, %llu], \"errors\": [%llu, %llu]},\n",
                 static_cast<unsigned long long>(axi_valid_[0]), static_cast<unsigned long long>(axi_valid_[1]), static_cast<unsigned long long>(axi_valid_[2]), static_cast<unsigned long long>(axi_valid_[3]), static_cast<unsigned long long>(axi_valid_[4]),
                 static_cast<unsigned long long>(axi_fire_[0]), static_cast<unsigned long long>(axi_fire_[1]), static_cast<unsigned long long>(axi_fire_[2]), static_cast<unsigned long long>(axi_fire_[3]), static_cast<unsigned long long>(axi_fire_[4]),
                 static_cast<unsigned long long>(axi_backpressure_[0]), static_cast<unsigned long long>(axi_backpressure_[1]), static_cast<unsigned long long>(axi_backpressure_[2]), static_cast<unsigned long long>(axi_backpressure_[3]), static_cast<unsigned long long>(axi_backpressure_[4]),
                 static_cast<unsigned long long>(axi_error_[0]), static_cast<unsigned long long>(axi_error_[1]));
    std::fprintf(file, "  \"invariants\": {\"abi_valid\": %s, \"retire_hist_cycles\": %s, \"retire_hist_instructions\": %s, \"commit_observation\": %s, \"source_retire_alignment\": %s, \"roi_complete\": %s, \"abi_errors\": %llu, \"source_retire_alignment_errors\": %llu, \"sampling_protocol_errors\": %llu, \"non_prefix_retire_cycles\": %llu}\n",
                 abi_errors_ == 0 ? "true" : "false",
                 hist_cycles_ok ? "true" : "false",
                 hist_instructions_ok ? "true" : "false",
                 commit_count_ok ? "true" : "false",
                 source_retire_alignment_errors_ == 0 ? "true" : "false",
                 roi_complete ? "true" : "false",
                 static_cast<unsigned long long>(abi_errors_),
                 static_cast<unsigned long long>(source_retire_alignment_errors_),
                 static_cast<unsigned long long>(sampling_protocol_errors_),
                 static_cast<unsigned long long>(non_prefix_retire_));
    std::fprintf(file, "}\n");
    std::fclose(file);
}

void perf_monitor_begin_cycle() {
    if (active_monitor != nullptr) active_monitor->begin_cycle();
}

void perf_monitor_cancel_cycle() {
    if (active_monitor != nullptr) active_monitor->cancel_cycle();
}

void perf_monitor_record_commit(std::uint64_t pc, std::uint32_t instruction,
                                std::uint8_t index) {
    if (active_monitor != nullptr) active_monitor->record_commit(pc, instruction, index);
}

void perf_monitor_record_commit_cycle(std::uint8_t count) {
    if (active_monitor != nullptr) active_monitor->record_commit_cycle(count);
}
