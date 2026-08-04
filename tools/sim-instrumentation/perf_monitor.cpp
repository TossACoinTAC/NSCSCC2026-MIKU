#include "perf_monitor.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "Vsimu_top.h"
#include "Vsimu_top___024root.h"

namespace {
PerfMonitor *active_monitor = nullptr;

inline std::uint64_t bit(bool value) { return value ? 1ULL : 0ULL; }

inline unsigned popcount8(std::uint8_t value) {
    unsigned count = 0;
    while (value != 0) {
        count += value & 1U;
        value >>= 1;
    }
    return count;
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

using Root = Vsimu_top___024root;

#define NSCC_JOIN_IMPL(left, right) left##right
#define NSCC_JOIN(left, right) NSCC_JOIN_IMPL(left, right)
#define NSCC_CORE_PREFIX simu_top__DOT__soc__DOT__cpu__DOT__backendArea_core__DOT__
#define CORE(name) root->NSCC_JOIN(NSCC_CORE_PREFIX, name)
#define SYS(name) CORE(NSCC_JOIN(systemArea_core__DOT__, name))
#define CLUSTER(name) SYS(NSCC_JOIN(backend__DOT__backend__DOT__, name))
#define BACKEND(name) SYS(NSCC_JOIN(backend__DOT__backend__DOT__backend__DOT__, name))
#define ROB(name) BACKEND(NSCC_JOIN(rob__DOT__, name))
#define LSQ(name) CLUSTER(NSCC_JOIN(loadStoreQueue__DOT__, name))
#define ISSUE(q, name) BACKEND(NSCC_JOIN(issueQueues_##q##__DOT__, name))
#define ISSUE_COUNT(q) BACKEND(issueQueues_##q##__DOT__count)
#define ISSUE_ENTRY(q, e, name) BACKEND(issueQueues_##q##__DOT__queue_##e##_##name)
#define COMMIT(name) CLUSTER(NSCC_JOIN(commitAdapter_io_, name))

const char *const kAxiNames[5] = {"aw", "w", "b", "ar", "r"};
const char *const kLsqNames[8] = {
    "translation_request", "translation_response", "translation_completion",
    "load_request", "store_request", "forward", "store_data", "store_completion"};
}

PerfMonitor::PerfMonitor(Vsimu_top *top) : top_(top) {
    active_monitor = this;
}

void PerfMonitor::begin_cycle() {
    if (commit_cycle_pending_) {
        sampling_protocol_errors_++;
    }
    commit_cycle_pending_ = true;
}

void PerfMonitor::cancel_cycle() {
    commit_cycle_pending_ = false;
}

PerfMonitor::CycleSnapshot PerfMonitor::capture_snapshot() {
    CycleSnapshot snapshot;
    Root *root = top_->rootp;
    const std::uint8_t commit_offer = static_cast<std::uint8_t>(CORE(systemArea_core_io_commitValid) & 0x7U);
    const std::uint8_t retired_bits =
        static_cast<std::uint8_t>(CORE(systemArea_core_io_commit_0_retired)) |
        static_cast<std::uint8_t>(CORE(systemArea_core_io_commit_1_retired) << 1) |
        static_cast<std::uint8_t>(CORE(systemArea_core_io_commit_2_retired) << 2);
    const std::uint8_t retired_mask = commit_offer & retired_bits;
    snapshot.commit_offer = commit_offer;
    snapshot.retired_mask = retired_mask;
    snapshot.retired_count = static_cast<std::uint8_t>(popcount8(retired_mask));

    snapshot.recovery = static_cast<bool>(CORE(systemArea_core_io_recoveryValid));
    snapshot.recovery_cause = static_cast<std::uint8_t>(SYS(recoveryPayload_cause) & 0x7U);
    snapshot.exception = static_cast<bool>(COMMIT(exceptionValid));
    snapshot.ertn = static_cast<bool>(COMMIT(ertnValid));
    snapshot.idle = static_cast<bool>(COMMIT(idleValid));
    snapshot.refetch = static_cast<bool>(COMMIT(refetchValid));

    const unsigned rob_occupancy = static_cast<unsigned>(ROB(occupancy) & 0x3fU);
    snapshot.rob_occupancy = static_cast<std::uint8_t>(rob_occupancy);

    if (snapshot.recovery) {
        snapshot.zero_reason = 0;
    } else if (rob_occupancy == 0) {
        snapshot.zero_reason = 1;
    } else if (!ROB(_zz_candidates_0_state_valid_1) || !ROB(_zz_candidates_0_state_complete)) {
        snapshot.zero_reason = 2;
    } else {
        snapshot.zero_reason = 3;
    }

    const bool divider_busy = static_cast<bool>(CLUSTER(execution__DOT__divider__DOT__busy));
    const std::uint8_t ready_maps[4] = {
        static_cast<std::uint8_t>(ISSUE(0, readyMap)), static_cast<std::uint8_t>(ISSUE(1, readyMap)),
        static_cast<std::uint8_t>(ISSUE(2, readyMap)), static_cast<std::uint8_t>(ISSUE(3, readyMap))};

    if (snapshot.zero_reason == 2) {
        const std::uint8_t head_pointer = static_cast<std::uint8_t>(ROB(_zz_candidates_0_state_pointer) & 0x3fU);
        const bool load_valid[8] = {
            static_cast<bool>(LSQ(loads_0_valid)), static_cast<bool>(LSQ(loads_1_valid)),
            static_cast<bool>(LSQ(loads_2_valid)), static_cast<bool>(LSQ(loads_3_valid)),
            static_cast<bool>(LSQ(loads_4_valid)), static_cast<bool>(LSQ(loads_5_valid)),
            static_cast<bool>(LSQ(loads_6_valid)), static_cast<bool>(LSQ(loads_7_valid))};
        const std::uint8_t load_pointer[8] = {
            static_cast<std::uint8_t>(LSQ(loads_0_robPointer)), static_cast<std::uint8_t>(LSQ(loads_1_robPointer)),
            static_cast<std::uint8_t>(LSQ(loads_2_robPointer)), static_cast<std::uint8_t>(LSQ(loads_3_robPointer)),
            static_cast<std::uint8_t>(LSQ(loads_4_robPointer)), static_cast<std::uint8_t>(LSQ(loads_5_robPointer)),
            static_cast<std::uint8_t>(LSQ(loads_6_robPointer)), static_cast<std::uint8_t>(LSQ(loads_7_robPointer))};
        const bool load_address[8] = {
            static_cast<bool>(LSQ(loads_0_addressReady)), static_cast<bool>(LSQ(loads_1_addressReady)),
            static_cast<bool>(LSQ(loads_2_addressReady)), static_cast<bool>(LSQ(loads_3_addressReady)),
            static_cast<bool>(LSQ(loads_4_addressReady)), static_cast<bool>(LSQ(loads_5_addressReady)),
            static_cast<bool>(LSQ(loads_6_addressReady)), static_cast<bool>(LSQ(loads_7_addressReady))};
        const bool load_translation[8] = {
            static_cast<bool>(LSQ(loads_0_translationDone)), static_cast<bool>(LSQ(loads_1_translationDone)),
            static_cast<bool>(LSQ(loads_2_translationDone)), static_cast<bool>(LSQ(loads_3_translationDone)),
            static_cast<bool>(LSQ(loads_4_translationDone)), static_cast<bool>(LSQ(loads_5_translationDone)),
            static_cast<bool>(LSQ(loads_6_translationDone)), static_cast<bool>(LSQ(loads_7_translationDone))};
        const bool load_sent[8] = {
            static_cast<bool>(LSQ(loads_0_requestSent)), static_cast<bool>(LSQ(loads_1_requestSent)),
            static_cast<bool>(LSQ(loads_2_requestSent)), static_cast<bool>(LSQ(loads_3_requestSent)),
            static_cast<bool>(LSQ(loads_4_requestSent)), static_cast<bool>(LSQ(loads_5_requestSent)),
            static_cast<bool>(LSQ(loads_6_requestSent)), static_cast<bool>(LSQ(loads_7_requestSent))};
        const bool load_completed[8] = {
            static_cast<bool>(LSQ(loads_0_completed)), static_cast<bool>(LSQ(loads_1_completed)),
            static_cast<bool>(LSQ(loads_2_completed)), static_cast<bool>(LSQ(loads_3_completed)),
            static_cast<bool>(LSQ(loads_4_completed)), static_cast<bool>(LSQ(loads_5_completed)),
            static_cast<bool>(LSQ(loads_6_completed)), static_cast<bool>(LSQ(loads_7_completed))};

        const bool store_valid[8] = {
            static_cast<bool>(LSQ(stores_0_valid)), static_cast<bool>(LSQ(stores_1_valid)),
            static_cast<bool>(LSQ(stores_2_valid)), static_cast<bool>(LSQ(stores_3_valid)),
            static_cast<bool>(LSQ(stores_4_valid)), static_cast<bool>(LSQ(stores_5_valid)),
            static_cast<bool>(LSQ(stores_6_valid)), static_cast<bool>(LSQ(stores_7_valid))};
        const std::uint8_t store_pointer[8] = {
            static_cast<std::uint8_t>(LSQ(stores_0_robPointer)), static_cast<std::uint8_t>(LSQ(stores_1_robPointer)),
            static_cast<std::uint8_t>(LSQ(stores_2_robPointer)), static_cast<std::uint8_t>(LSQ(stores_3_robPointer)),
            static_cast<std::uint8_t>(LSQ(stores_4_robPointer)), static_cast<std::uint8_t>(LSQ(stores_5_robPointer)),
            static_cast<std::uint8_t>(LSQ(stores_6_robPointer)), static_cast<std::uint8_t>(LSQ(stores_7_robPointer))};
        const bool store_address[8] = {
            static_cast<bool>(LSQ(stores_0_addressReady)), static_cast<bool>(LSQ(stores_1_addressReady)),
            static_cast<bool>(LSQ(stores_2_addressReady)), static_cast<bool>(LSQ(stores_3_addressReady)),
            static_cast<bool>(LSQ(stores_4_addressReady)), static_cast<bool>(LSQ(stores_5_addressReady)),
            static_cast<bool>(LSQ(stores_6_addressReady)), static_cast<bool>(LSQ(stores_7_addressReady))};
        const bool store_data[8] = {
            static_cast<bool>(LSQ(stores_0_dataReady)), static_cast<bool>(LSQ(stores_1_dataReady)),
            static_cast<bool>(LSQ(stores_2_dataReady)), static_cast<bool>(LSQ(stores_3_dataReady)),
            static_cast<bool>(LSQ(stores_4_dataReady)), static_cast<bool>(LSQ(stores_5_dataReady)),
            static_cast<bool>(LSQ(stores_6_dataReady)), static_cast<bool>(LSQ(stores_7_dataReady))};
        const bool store_translation[8] = {
            static_cast<bool>(LSQ(stores_0_translationDone)), static_cast<bool>(LSQ(stores_1_translationDone)),
            static_cast<bool>(LSQ(stores_2_translationDone)), static_cast<bool>(LSQ(stores_3_translationDone)),
            static_cast<bool>(LSQ(stores_4_translationDone)), static_cast<bool>(LSQ(stores_5_translationDone)),
            static_cast<bool>(LSQ(stores_6_translationDone)), static_cast<bool>(LSQ(stores_7_translationDone))};
        const bool store_sent[8] = {
            static_cast<bool>(LSQ(stores_0_requestSent)), static_cast<bool>(LSQ(stores_1_requestSent)),
            static_cast<bool>(LSQ(stores_2_requestSent)), static_cast<bool>(LSQ(stores_3_requestSent)),
            static_cast<bool>(LSQ(stores_4_requestSent)), static_cast<bool>(LSQ(stores_5_requestSent)),
            static_cast<bool>(LSQ(stores_6_requestSent)), static_cast<bool>(LSQ(stores_7_requestSent))};
        const bool store_completed[8] = {
            static_cast<bool>(LSQ(stores_0_completed)), static_cast<bool>(LSQ(stores_1_completed)),
            static_cast<bool>(LSQ(stores_2_completed)), static_cast<bool>(LSQ(stores_3_completed)),
            static_cast<bool>(LSQ(stores_4_completed)), static_cast<bool>(LSQ(stores_5_completed)),
            static_cast<bool>(LSQ(stores_6_completed)), static_cast<bool>(LSQ(stores_7_completed))};
        const bool store_uncached[8] = {
            static_cast<bool>(LSQ(stores_0_uncached)), static_cast<bool>(LSQ(stores_1_uncached)),
            static_cast<bool>(LSQ(stores_2_uncached)), static_cast<bool>(LSQ(stores_3_uncached)),
            static_cast<bool>(LSQ(stores_4_uncached)), static_cast<bool>(LSQ(stores_5_uncached)),
            static_cast<bool>(LSQ(stores_6_uncached)), static_cast<bool>(LSQ(stores_7_uncached))};

        bool classified = false;
        if (!ROB(_zz_candidates_0_state_valid_1)) {
            snapshot.head_incomplete_reason = 12;
            classified = true;
        }
        for (unsigned entry = 0; entry < 8 && !classified; entry++) {
            if (load_valid[entry] && load_pointer[entry] == head_pointer) {
                unsigned reason = !load_address[entry] ? 0 : !load_translation[entry] ? 1 :
                    !load_sent[entry] ? 2 : !load_completed[entry] ? 3 : 4;
                snapshot.head_incomplete_reason = static_cast<std::uint8_t>(reason);
                classified = true;
            }
        }
        for (unsigned entry = 0; entry < 8 && !classified; entry++) {
            if (store_valid[entry] && store_pointer[entry] == head_pointer) {
                unsigned reason = !store_address[entry] ? 5 : !store_data[entry] ? 6 :
                    !store_translation[entry] ? 7 :
                    (store_uncached[entry] && store_sent[entry] && !store_completed[entry]) ? 8 : 9;
                snapshot.head_incomplete_reason = static_cast<std::uint8_t>(reason);
                classified = true;
            }
        }
        if (!classified && divider_busy &&
            static_cast<std::uint8_t>(CLUSTER(execution__DOT__divider__DOT__uop_robPointer)) == head_pointer) {
            snapshot.head_incomplete_reason = 10;
            classified = true;
        }
        if (!classified && static_cast<bool>(CLUSTER(execution__DOT__multiplier__DOT__valid)) &&
            static_cast<std::uint8_t>(CLUSTER(execution__DOT__multiplier__DOT__uop_robPointer)) == head_pointer) {
            snapshot.head_incomplete_reason = 11;
            classified = true;
        }
        const unsigned issue_count[4] = {
            static_cast<unsigned>(ISSUE_COUNT(0)), static_cast<unsigned>(ISSUE_COUNT(1)),
            static_cast<unsigned>(ISSUE_COUNT(2)), static_cast<unsigned>(ISSUE_COUNT(3))};
        const std::uint8_t issue_pointer[4][8] = {
            {ISSUE_ENTRY(0, 0, robPointer), ISSUE_ENTRY(0, 1, robPointer),
             ISSUE_ENTRY(0, 2, robPointer), ISSUE_ENTRY(0, 3, robPointer),
             ISSUE_ENTRY(0, 4, robPointer), ISSUE_ENTRY(0, 5, robPointer),
             ISSUE_ENTRY(0, 6, robPointer), ISSUE_ENTRY(0, 7, robPointer)},
            {ISSUE_ENTRY(1, 0, robPointer), ISSUE_ENTRY(1, 1, robPointer),
             ISSUE_ENTRY(1, 2, robPointer), ISSUE_ENTRY(1, 3, robPointer),
             ISSUE_ENTRY(1, 4, robPointer), ISSUE_ENTRY(1, 5, robPointer),
             ISSUE_ENTRY(1, 6, robPointer), ISSUE_ENTRY(1, 7, robPointer)},
            {ISSUE_ENTRY(2, 0, robPointer), ISSUE_ENTRY(2, 1, robPointer),
             ISSUE_ENTRY(2, 2, robPointer), ISSUE_ENTRY(2, 3, robPointer),
             ISSUE_ENTRY(2, 4, robPointer), ISSUE_ENTRY(2, 5, robPointer),
             ISSUE_ENTRY(2, 6, robPointer), ISSUE_ENTRY(2, 7, robPointer)},
            {ISSUE_ENTRY(3, 0, robPointer), ISSUE_ENTRY(3, 1, robPointer),
             ISSUE_ENTRY(3, 2, robPointer), ISSUE_ENTRY(3, 3, robPointer),
             ISSUE_ENTRY(3, 4, robPointer), ISSUE_ENTRY(3, 5, robPointer),
             ISSUE_ENTRY(3, 6, robPointer), ISSUE_ENTRY(3, 7, robPointer)}};
        for (unsigned queue = 0; queue < 4 && !classified; queue++) {
            for (unsigned entry = 0; entry < issue_count[queue] && !classified; entry++) {
                if (issue_pointer[queue][entry] == head_pointer) {
                    snapshot.head_incomplete_reason =
                        (ready_maps[queue] & (1U << entry)) != 0 ? 14 : 13;
                    classified = true;
                }
            }
        }
        const std::uint8_t staged_valid = static_cast<std::uint8_t>(
            ROB(stagedCompletionValid) & ROB(stagedCompletionCurrent));
        const std::uint8_t staged_pointer[5] = {
            static_cast<std::uint8_t>(ROB(stagedRobPointer_0)),
            static_cast<std::uint8_t>(ROB(stagedRobPointer_1)),
            static_cast<std::uint8_t>(ROB(stagedRobPointer_2)),
            static_cast<std::uint8_t>(ROB(stagedRobPointer_3)),
            static_cast<std::uint8_t>(ROB(stagedRobPointer_4))};
        for (unsigned lane = 0; lane < 5 && !classified; lane++) {
            if ((staged_valid & (1U << lane)) != 0 && staged_pointer[lane] == head_pointer) {
                snapshot.head_incomplete_reason = 15;
                classified = true;
            }
        }
        const std::uint8_t operand_valid = static_cast<std::uint8_t>(BACKEND(issueOperandValid));
        const std::uint8_t operand_pointer[4] = {
            static_cast<std::uint8_t>(BACKEND(issueOperandUop_0_robPointer)),
            static_cast<std::uint8_t>(BACKEND(issueOperandUop_1_robPointer)),
            static_cast<std::uint8_t>(BACKEND(issueOperandUop_2_robPointer)),
            static_cast<std::uint8_t>(BACKEND(issueOperandUop_3_robPointer))};
        for (unsigned port = 0; port < 4 && !classified; port++) {
            if ((operand_valid & (1U << port)) != 0 && operand_pointer[port] == head_pointer) {
                snapshot.head_incomplete_reason = 16;
                classified = true;
            }
        }
        const bool address_valid[3] = {
            static_cast<bool>(BACKEND(issueAddressValid_0)),
            static_cast<bool>(BACKEND(issueAddressValid_1)),
            static_cast<bool>(BACKEND(issueAddressValid_2))};
        const std::uint8_t address_pointer[3] = {
            static_cast<std::uint8_t>(BACKEND(issueAddressUop_0_robPointer)),
            static_cast<std::uint8_t>(BACKEND(issueAddressUop_1_robPointer)),
            static_cast<std::uint8_t>(BACKEND(issueAddressUop_2_robPointer))};
        for (unsigned stage = 0; stage < 3 && !classified; stage++) {
            if (address_valid[stage] && address_pointer[stage] == head_pointer) {
                snapshot.head_incomplete_reason = 17;
                classified = true;
            }
        }
        if (!classified) {
            snapshot.head_incomplete_reason = 18;
        }
    }

    if (snapshot.zero_reason == 3) {
        unsigned reason = 5;
        if (SYS(internalRedirectValid)) {
            reason = 0;
        } else if (!ROB(_zz_candidates_0_state_payloadReady)) {
            reason = 1;
        } else if (ROB(_zz_candidates_0_state_decodedExceptionValid) ||
                   ROB(_zz_candidates_0_state_completionExceptionValid)) {
            reason = 2;
        } else if (ROB(_zz_candidates_0_state_serializing)) {
            reason = 3;
        } else if (commit_offer != 0) {
            reason = 4;
        }
        snapshot.head_complete_reason = static_cast<std::uint8_t>(reason);
    }

    snapshot.decode_valid = static_cast<std::uint8_t>(
        popcount8(static_cast<std::uint8_t>(SYS(frontend__DOT__decodeInputValid))));
    snapshot.frontend_cache_request = static_cast<bool>(SYS(frontend_io_cacheRequestValid));
    snapshot.frontend_uncached_request = static_cast<bool>(SYS(frontend_io_cacheUncachedRequestValid));
    snapshot.divider_busy = divider_busy;
    snapshot.p1_ready_while_divider_busy = divider_busy && ready_maps[1] != 0;
    if (divider_busy && !previous_divider_busy_) {
        const std::uint32_t dividend = CLUSTER(execution__DOT__divider__DOT__source1Magnitude);
        const std::uint32_t divisor = CLUSTER(execution__DOT__divider__DOT__source2Magnitude);
        unsigned operand_class = divisor == 0 ? 0 : divisor == 1 ? 1 : dividend == 0 ? 2 :
            (divisor & (divisor - 1U)) == 0 ? 3 : 4;
        snapshot.divide_class = static_cast<std::uint8_t>(operand_class);
    }
    previous_divider_busy_ = divider_busy;
    for (unsigned queue = 0; queue < 4; queue++) {
        snapshot.issue_ready[queue] = static_cast<std::uint8_t>(popcount8(ready_maps[queue]));
    }

    const bool lsq_signals[8] = {
        static_cast<bool>(LSQ(translationRequestFire)), static_cast<bool>(LSQ(translationResponseFire)),
        static_cast<bool>(LSQ(translationCompletionFire)), static_cast<bool>(LSQ(loadRequestFire)),
        static_cast<bool>(LSQ(storeRequestFire)), static_cast<bool>(LSQ(forwardFire)),
        static_cast<bool>(LSQ(storeDataFire)), static_cast<bool>(LSQ(storeCompletionFire))};
    for (unsigned event = 0; event < 8; event++) {
        if (lsq_signals[event]) {
            snapshot.lsq_events |= static_cast<std::uint8_t>(1U << event);
        }
    }

    const bool valid[5] = {
        static_cast<bool>(root->simu_top__DOT__soc__DOT__m0_awvalid),
        static_cast<bool>(root->simu_top__DOT__soc__DOT__m0_wvalid),
        static_cast<bool>(root->simu_top__DOT__soc__DOT__m0_bvalid),
        static_cast<bool>(root->simu_top__DOT__soc__DOT__m0_arvalid),
        static_cast<bool>(root->simu_top__DOT__soc__DOT__m0_rvalid)};
    const bool ready[5] = {
        static_cast<bool>(root->simu_top__DOT__soc__DOT__m0_awready),
        static_cast<bool>(root->simu_top__DOT__soc__DOT__m0_wready),
        static_cast<bool>(root->simu_top__DOT__soc__DOT__m0_bready),
        static_cast<bool>(root->simu_top__DOT__soc__DOT__m0_arready),
        static_cast<bool>(root->simu_top__DOT__soc__DOT__m0_rready)};
    for (unsigned channel = 0; channel < 5; channel++) {
        if (valid[channel]) {
            snapshot.axi_valid |= static_cast<std::uint8_t>(1U << channel);
        }
        if (valid[channel] && ready[channel]) {
            snapshot.axi_fire |= static_cast<std::uint8_t>(1U << channel);
        }
    }
    snapshot.axi_error |= static_cast<std::uint8_t>(
        valid[2] && ready[2] && root->simu_top__DOT__soc__DOT__m0_bresp != 0);
    snapshot.axi_error |= static_cast<std::uint8_t>(
        (valid[4] && ready[4] && root->simu_top__DOT__soc__DOT__m0_rresp != 0) << 1);

    return snapshot;
}

void PerfMonitor::accumulate_snapshot(const CycleSnapshot &snapshot, std::uint8_t retired_count) {
    cycles_++;
    if (snapshot.retired_count != retired_count) {
        source_retire_alignment_errors_++;
        sampling_protocol_errors_++;
    }
    if (snapshot.retired_mask != snapshot.commit_offer) {
        commit_offer_vs_retired_cycles_++;
    }
    if (snapshot.retired_mask != 0 &&
        (snapshot.retired_mask & (snapshot.retired_mask + 1U)) != 0) {
        non_prefix_retire_++;
    }
    if (snapshot.recovery) {
        recovery_cycles_++;
        recovery_cause_[snapshot.recovery_cause]++;
    }
    exception_cycles_ += bit(snapshot.exception);
    ertn_cycles_ += bit(snapshot.ertn);
    idle_cycles_ += bit(snapshot.idle);
    refetch_cycles_ += bit(snapshot.refetch);

    rob_occupancy_sum_ += snapshot.rob_occupancy;
    rob_occupancy_max_ = snapshot.rob_occupancy > rob_occupancy_max_ ?
        snapshot.rob_occupancy : rob_occupancy_max_;
    if (snapshot.rob_occupancy < 33) {
        rob_occupancy_hist_[snapshot.rob_occupancy]++;
    }
    rob_full_cycles_ += bit(snapshot.rob_occupancy >= 32);

    frontend_decode_valid_sum_ += snapshot.decode_valid;
    frontend_cache_request_ += bit(snapshot.frontend_cache_request);
    frontend_uncached_request_ += bit(snapshot.frontend_uncached_request);
    divider_busy_cycles_ += bit(snapshot.divider_busy);
    p1_ready_while_divider_busy_ += bit(snapshot.p1_ready_while_divider_busy);
    if (snapshot.divide_class != kNoReason) {
        divide_classes_[snapshot.divide_class]++;
    }
    for (unsigned queue = 0; queue < 4; queue++) {
        issue_ready_cycles_[queue] += bit(snapshot.issue_ready[queue] != 0);
        issue_ready_entries_[queue] += snapshot.issue_ready[queue];
    }
    for (unsigned event = 0; event < 8; event++) {
        lsq_events_[event] += bit((snapshot.lsq_events & (1U << event)) != 0);
    }
    for (unsigned channel = 0; channel < 5; channel++) {
        const bool valid = (snapshot.axi_valid & (1U << channel)) != 0;
        const bool fire = (snapshot.axi_fire & (1U << channel)) != 0;
        axi_valid_[channel] += bit(valid);
        axi_fire_[channel] += bit(fire);
        axi_backpressure_[channel] += bit(valid && !fire);
    }
    axi_error_[0] += bit((snapshot.axi_error & 1U) != 0);
    axi_error_[1] += bit((snapshot.axi_error & 2U) != 0);

    retire_hist_[retired_count]++;
    sampled_instructions_ += retired_count;
    if (retired_count == 0) {
        zero_retire_[snapshot.zero_reason]++;
        if (snapshot.head_incomplete_reason != kNoReason) {
            head_incomplete_[snapshot.head_incomplete_reason]++;
        }
        if (snapshot.head_complete_reason != kNoReason) {
            head_complete_blocked_[snapshot.head_complete_reason]++;
        }
    }
}

void perf_monitor_begin_cycle() {
    if (active_monitor != nullptr) {
        active_monitor->begin_cycle();
    }
}

void perf_monitor_cancel_cycle() {
    if (active_monitor != nullptr) {
        active_monitor->cancel_cycle();
    }
}

void perf_monitor_record_commit(std::uint64_t pc, std::uint32_t instruction, std::uint8_t index) {
    if (active_monitor == nullptr) {
        return;
    }
    active_monitor->record_commit(pc, instruction, index);
}

void perf_monitor_record_commit_cycle(std::uint8_t count) {
    if (active_monitor != nullptr) {
        active_monitor->record_commit_cycle(count);
    }
}

void PerfMonitor::record_commit_cycle(std::uint8_t count) {
    if (!commit_cycle_pending_) {
        return;
    }
    commit_cycle_pending_ = false;
    if (count > 3) {
        sampling_protocol_errors_++;
        count = 0;
    }
    const CycleSnapshot current = capture_snapshot();
    const CycleSnapshot aligned = snapshot_history_[kCommitObservationLag - 1];
    for (unsigned index = kCommitObservationLag - 1; index > 0; index--) {
        snapshot_history_[index] = snapshot_history_[index - 1];
    }
    snapshot_history_[0] = current;
    accumulate_snapshot(aligned, count);
}

void PerfMonitor::record_commit(std::uint64_t pc, std::uint32_t instruction, std::uint8_t index) {
    if (!commit_cycle_pending_) {
        return;
    }
    observed_instructions_++;
    fnv_byte(trace_signature_, index);
    fnv_u32(trace_signature_, static_cast<std::uint32_t>(pc));
    fnv_u32(trace_signature_, instruction);
}

void PerfMonitor::write_json(const char *path) const {
    FILE *file = std::fopen(path, "w");
    if (file == nullptr) {
        std::perror("m01-counters.json");
        return;
    }
    const std::uint64_t retire_sum = retire_hist_[1] + 2 * retire_hist_[2] + 3 * retire_hist_[3];
    const bool hist_cycles_ok = retire_hist_[0] + retire_hist_[1] + retire_hist_[2] + retire_hist_[3] == cycles_;
    const bool hist_instructions_ok = retire_sum == sampled_instructions_;
    const bool commit_count_ok = observed_instructions_ == sampled_instructions_;
    const std::uint64_t unused_slots = cycles_ * 3 - sampled_instructions_;
    std::fprintf(file, "{\n");
    std::fprintf(file, "  \"schema_version\": \"nscc-m01-v2\",\n");
    std::fprintf(file, "  \"roi\": \"difftest-observation-window-source-aligned\",\n");
    std::fprintf(file, "  \"commit_observation_lag_cycles\": %u,\n", kCommitObservationLag);
    std::fprintf(file, "  \"cycles\": %llu,\n", static_cast<unsigned long long>(cycles_));
    std::fprintf(file, "  \"retired_instructions\": %llu,\n", static_cast<unsigned long long>(sampled_instructions_));
    std::fprintf(file, "  \"retire_width_histogram\": [%llu, %llu, %llu, %llu],\n",
                 static_cast<unsigned long long>(retire_hist_[0]), static_cast<unsigned long long>(retire_hist_[1]),
                 static_cast<unsigned long long>(retire_hist_[2]), static_cast<unsigned long long>(retire_hist_[3]));
    std::fprintf(file, "  \"unused_commit_slots\": %llu,\n", static_cast<unsigned long long>(unused_slots));
    std::fprintf(file, "  \"commit_trace_signature_fnv1a64\": \"%016llx\",\n",
                 static_cast<unsigned long long>(trace_signature_));
    std::fprintf(file, "  \"observed_commit_instructions\": %llu,\n", static_cast<unsigned long long>(observed_instructions_));
    std::fprintf(file, "  \"recovery_cycles\": %llu,\n", static_cast<unsigned long long>(recovery_cycles_));
    std::fprintf(file, "  \"recovery_cause\": {\"none\": %llu, \"branch_mispredict\": %llu, \"exception\": %llu, \"ertn\": %llu, \"refetch\": %llu, \"reserved_5\": %llu, \"reserved_6\": %llu, \"reserved_7\": %llu},\n",
                 static_cast<unsigned long long>(recovery_cause_[0]), static_cast<unsigned long long>(recovery_cause_[1]),
                 static_cast<unsigned long long>(recovery_cause_[2]), static_cast<unsigned long long>(recovery_cause_[3]),
                 static_cast<unsigned long long>(recovery_cause_[4]), static_cast<unsigned long long>(recovery_cause_[5]),
                 static_cast<unsigned long long>(recovery_cause_[6]), static_cast<unsigned long long>(recovery_cause_[7]));
    std::fprintf(file, "  \"zero_retire_loss\": {\"recovery\": %llu, \"rob_empty\": %llu, \"head_incomplete\": %llu, \"head_complete_blocked\": %llu, \"unclassified\": %llu},\n",
                 static_cast<unsigned long long>(zero_retire_[0]), static_cast<unsigned long long>(zero_retire_[1]),
                 static_cast<unsigned long long>(zero_retire_[2]), static_cast<unsigned long long>(zero_retire_[3]),
                 static_cast<unsigned long long>(zero_retire_[4]));
    std::fprintf(file, "  \"head_incomplete_detail\": {\"load_address\": %llu, \"load_translation\": %llu, \"load_order_or_request\": %llu, \"load_response\": %llu, \"load_completion_to_rob\": %llu, \"store_address\": %llu, \"store_data\": %llu, \"store_translation\": %llu, \"store_uncached_response\": %llu, \"store_completion_to_rob\": %llu, \"divider\": %llu, \"multiplier\": %llu, \"rob_candidate_not_valid\": %llu, \"issue_operand_wait\": %llu, \"issue_ready_wait\": %llu, \"rob_staged_completion\": %llu, \"issue_operand_stage\": %llu, \"issue_address_stage\": %llu, \"execution_dispatch_or_untracked\": %llu},\n",
                 static_cast<unsigned long long>(head_incomplete_[0]), static_cast<unsigned long long>(head_incomplete_[1]),
                 static_cast<unsigned long long>(head_incomplete_[2]), static_cast<unsigned long long>(head_incomplete_[3]),
                 static_cast<unsigned long long>(head_incomplete_[4]), static_cast<unsigned long long>(head_incomplete_[5]),
                 static_cast<unsigned long long>(head_incomplete_[6]), static_cast<unsigned long long>(head_incomplete_[7]),
                 static_cast<unsigned long long>(head_incomplete_[8]), static_cast<unsigned long long>(head_incomplete_[9]),
                 static_cast<unsigned long long>(head_incomplete_[10]), static_cast<unsigned long long>(head_incomplete_[11]),
                 static_cast<unsigned long long>(head_incomplete_[12]), static_cast<unsigned long long>(head_incomplete_[13]),
                 static_cast<unsigned long long>(head_incomplete_[14]), static_cast<unsigned long long>(head_incomplete_[15]),
                 static_cast<unsigned long long>(head_incomplete_[16]), static_cast<unsigned long long>(head_incomplete_[17]),
                 static_cast<unsigned long long>(head_incomplete_[18]));
    std::fprintf(file, "  \"head_complete_blocked_detail\": {\"redirect\": %llu, \"payload_not_ready\": %llu, \"exception\": %llu, \"serializing\": %llu, \"commit_offered_not_observed\": %llu, \"other\": %llu},\n",
                 static_cast<unsigned long long>(head_complete_blocked_[0]), static_cast<unsigned long long>(head_complete_blocked_[1]),
                 static_cast<unsigned long long>(head_complete_blocked_[2]), static_cast<unsigned long long>(head_complete_blocked_[3]),
                 static_cast<unsigned long long>(head_complete_blocked_[4]), static_cast<unsigned long long>(head_complete_blocked_[5]));
    std::fprintf(file, "  \"commit_side_effect_cycles\": {\"exception\": %llu, \"ertn\": %llu, \"idle\": %llu, \"refetch\": %llu},\n",
                 static_cast<unsigned long long>(exception_cycles_), static_cast<unsigned long long>(ertn_cycles_),
                 static_cast<unsigned long long>(idle_cycles_), static_cast<unsigned long long>(refetch_cycles_));
    std::fprintf(file, "  \"rob\": {\"occupancy_sum\": %llu, \"occupancy_max\": %llu, \"full_cycles\": %llu},\n",
                 static_cast<unsigned long long>(rob_occupancy_sum_), static_cast<unsigned long long>(rob_occupancy_max_),
                 static_cast<unsigned long long>(rob_full_cycles_));
    std::fprintf(file, "  \"frontend\": {\"decode_valid_sum\": %llu, \"cache_request\": %llu, \"uncached_request\": %llu},\n",
                 static_cast<unsigned long long>(frontend_decode_valid_sum_), static_cast<unsigned long long>(frontend_cache_request_),
                 static_cast<unsigned long long>(frontend_uncached_request_));
    std::fprintf(file, "  \"issue\": {\"ready_cycles\": [%llu, %llu, %llu, %llu], \"ready_entries\": [%llu, %llu, %llu, %llu]},\n",
                 static_cast<unsigned long long>(issue_ready_cycles_[0]), static_cast<unsigned long long>(issue_ready_cycles_[1]),
                 static_cast<unsigned long long>(issue_ready_cycles_[2]), static_cast<unsigned long long>(issue_ready_cycles_[3]),
                 static_cast<unsigned long long>(issue_ready_entries_[0]), static_cast<unsigned long long>(issue_ready_entries_[1]),
                 static_cast<unsigned long long>(issue_ready_entries_[2]), static_cast<unsigned long long>(issue_ready_entries_[3]));
    std::fprintf(file, "  \"execution\": {\"divider_busy_cycles\": %llu, \"p1_ready_while_divider_busy\": %llu, \"divide_operand_classes\": {\"divisor_zero\": %llu, \"divisor_abs_one\": %llu, \"dividend_zero\": %llu, \"divisor_power_of_two\": %llu, \"ordinary\": %llu}},\n",
                 static_cast<unsigned long long>(divider_busy_cycles_),
                 static_cast<unsigned long long>(p1_ready_while_divider_busy_),
                 static_cast<unsigned long long>(divide_classes_[0]), static_cast<unsigned long long>(divide_classes_[1]),
                 static_cast<unsigned long long>(divide_classes_[2]), static_cast<unsigned long long>(divide_classes_[3]),
                 static_cast<unsigned long long>(divide_classes_[4]));
    std::fprintf(file, "  \"lsq\": {\"translation_request\": %llu, \"translation_response\": %llu, \"translation_completion\": %llu, \"load_request\": %llu, \"store_request\": %llu, \"forward\": %llu, \"store_data\": %llu, \"store_completion\": %llu},\n",
                 static_cast<unsigned long long>(lsq_events_[0]), static_cast<unsigned long long>(lsq_events_[1]),
                 static_cast<unsigned long long>(lsq_events_[2]), static_cast<unsigned long long>(lsq_events_[3]),
                 static_cast<unsigned long long>(lsq_events_[4]), static_cast<unsigned long long>(lsq_events_[5]),
                 static_cast<unsigned long long>(lsq_events_[6]), static_cast<unsigned long long>(lsq_events_[7]));
    std::fprintf(file, "  \"axi\": {\n");
    for (unsigned channel = 0; channel < 5; channel++) {
        std::fprintf(file, "    \"%s\": {\"valid\": %llu, \"fire\": %llu, \"backpressure\": %llu}%s\n",
                     kAxiNames[channel], static_cast<unsigned long long>(axi_valid_[channel]),
                     static_cast<unsigned long long>(axi_fire_[channel]), static_cast<unsigned long long>(axi_backpressure_[channel]),
                     channel == 4 ? "" : ",");
    }
    std::fprintf(file, "  },\n");
    std::fprintf(file, "  \"axi_error\": {\"bresp\": %llu, \"rresp\": %llu},\n",
                 static_cast<unsigned long long>(axi_error_[0]), static_cast<unsigned long long>(axi_error_[1]));
    std::fprintf(file, "  \"invariants\": {\"retire_hist_cycles\": %s, \"retire_hist_instructions\": %s, \"commit_observation\": %s, \"source_retire_alignment\": %s, \"source_retire_alignment_errors\": %llu, \"sampling_protocol_errors\": %llu, \"non_prefix_retire_cycles\": %llu, \"commit_offer_vs_retired_cycles\": %llu}\n",
                 hist_cycles_ok ? "true" : "false", hist_instructions_ok ? "true" : "false",
                 commit_count_ok ? "true" : "false",
                 source_retire_alignment_errors_ == 0 ? "true" : "false",
                 static_cast<unsigned long long>(source_retire_alignment_errors_),
                 static_cast<unsigned long long>(sampling_protocol_errors_), static_cast<unsigned long long>(non_prefix_retire_),
                 static_cast<unsigned long long>(commit_offer_vs_retired_cycles_));
    std::fprintf(file, "}\n");
    std::fclose(file);
}
