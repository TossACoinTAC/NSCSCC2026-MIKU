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

inline unsigned latency_bucket(std::uint64_t cycles) {
    if (cycles <= 3) {
        return static_cast<unsigned>(cycles);
    }
    if (cycles <= 7) {
        return 4;
    }
    if (cycles <= 15) {
        return 5;
    }
    if (cycles <= 31) {
        return 6;
    }
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
#define LSQ_IO(name) CLUSTER(NSCC_JOIN(loadStoreQueue_io_, name))
#define ATU(name) CORE(NSCC_JOIN(systemArea_addressTranslation__DOT__, name))
#define ATU_TLB(name) ATU(NSCC_JOIN(area_tlb__DOT__, name))
#define ISSUE(q, name) BACKEND(NSCC_JOIN(issueQueues_##q##__DOT__, name))
#define ISSUE_COUNT(q) BACKEND(issueQueues_##q##__DOT__count)
#define ISSUE_ENTRY(q, e, name) BACKEND(issueQueues_##q##__DOT__queue_##e##_##name)
#define ISSUE_ROW(q, name) { \
    static_cast<std::uint8_t>(ISSUE_ENTRY(q, 0, name)), \
    static_cast<std::uint8_t>(ISSUE_ENTRY(q, 1, name)), \
    static_cast<std::uint8_t>(ISSUE_ENTRY(q, 2, name)), \
    static_cast<std::uint8_t>(ISSUE_ENTRY(q, 3, name)), \
    static_cast<std::uint8_t>(ISSUE_ENTRY(q, 4, name)), \
    static_cast<std::uint8_t>(ISSUE_ENTRY(q, 5, name)), \
    static_cast<std::uint8_t>(ISSUE_ENTRY(q, 6, name)), \
    static_cast<std::uint8_t>(ISSUE_ENTRY(q, 7, name)) }
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
    snapshot.recovery_cause = static_cast<std::uint8_t>(BACKEND(rob_io_recovery_cause) & 0x7U);
    snapshot.recovery_pointer = static_cast<std::uint8_t>(BACKEND(rob_io_recovery_robPointer) & 0x3fU);
    snapshot.exception = static_cast<bool>(COMMIT(exceptionValid));
    snapshot.ertn = static_cast<bool>(COMMIT(ertnValid));
    snapshot.idle = static_cast<bool>(COMMIT(idleValid));
    snapshot.refetch = static_cast<bool>(COMMIT(refetchValid));

    const unsigned rob_occupancy = static_cast<unsigned>(ROB(occupancy) & 0x3fU);
    snapshot.rob_occupancy = static_cast<std::uint8_t>(rob_occupancy);
    snapshot.rob_head_pointer = static_cast<std::uint8_t>(ROB(_zz_candidates_0_state_pointer) & 0x3fU);

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
    const std::uint8_t issue_counts[4] = {
        static_cast<std::uint8_t>(ISSUE_COUNT(0)), static_cast<std::uint8_t>(ISSUE_COUNT(1)),
        static_cast<std::uint8_t>(ISSUE_COUNT(2)), static_cast<std::uint8_t>(ISSUE_COUNT(3))};

    if (snapshot.zero_reason == 2) {
        const std::uint8_t head_pointer = static_cast<std::uint8_t>(ROB(_zz_candidates_0_state_pointer) & 0x3fU);
        const auto &head_payload = ROB(_zz___05Fzz_candidates_0_payload_pc);
        const bool head_payload_ready = static_cast<bool>(ROB(_zz_candidates_0_state_payloadReady));
        const bool head_is_load = ((head_payload[3] >> 7U) & 1U) != 0;
        const bool head_is_store = ((head_payload[3] >> 8U) & 1U) != 0;
        const unsigned head_load_index = (head_payload[3] >> 29U) & 0x7U;
        const unsigned head_store_index = head_payload[4] & 0x7U;
        const std::uint8_t staged_valid = static_cast<std::uint8_t>(
            ROB(stagedCompletionValid) & ROB(stagedCompletionCurrent));
        const std::uint8_t staged_pointer[5] = {
            static_cast<std::uint8_t>(ROB(stagedRobPointer_0)),
            static_cast<std::uint8_t>(ROB(stagedRobPointer_1)),
            static_cast<std::uint8_t>(ROB(stagedRobPointer_2)),
            static_cast<std::uint8_t>(ROB(stagedRobPointer_3)),
            static_cast<std::uint8_t>(ROB(stagedRobPointer_4))};
        const bool staged_branch[5] = {
            static_cast<bool>(ROB(stagedBranchResolved_0)),
            static_cast<bool>(ROB(stagedBranchResolved_1)),
            static_cast<bool>(ROB(stagedBranchResolved_2)),
            static_cast<bool>(ROB(stagedBranchResolved_3)),
            false};
        for (unsigned lane = 0; lane < 5 && ROB(_zz_candidates_0_state_valid_1); lane++) {
            if ((staged_valid & (1U << lane)) == 0 || staged_pointer[lane] != head_pointer) {
                continue;
            }
            snapshot.e02_head_staged_lane = static_cast<std::uint8_t>(lane);
            snapshot.e02_head_staged_class = head_is_load ? 0 : head_is_store ? 1 :
                staged_branch[lane] ? 2 : lane == 4 ? 3 : static_cast<std::uint8_t>(4 + lane);
            break;
        }
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
            snapshot.head_incomplete_reason = 14;
            classified = true;
        } else if (!head_payload_ready) {
            snapshot.head_incomplete_reason = 15;
            classified = true;
        } else if (head_is_load) {
            const unsigned entry = head_load_index;
            if (!load_valid[entry] || load_pointer[entry] != head_pointer) {
                snapshot.head_incomplete_reason = 5;
            } else {
                snapshot.head_incomplete_reason = !load_address[entry] ? 0 :
                    !load_translation[entry] ? 1 : !load_sent[entry] ? 2 :
                    !load_completed[entry] ? 3 : 4;
            }
            classified = true;
        } else if (head_is_store) {
            const unsigned entry = head_store_index;
            if (!store_valid[entry] || store_pointer[entry] != head_pointer) {
                snapshot.head_incomplete_reason = 11;
            } else if (!store_address[entry]) {
                snapshot.head_incomplete_reason = 6;
            } else if (!store_data[entry]) {
                snapshot.head_incomplete_reason = 7;
            } else if (!store_translation[entry]) {
                snapshot.head_incomplete_reason = 8;
                if (static_cast<unsigned>(LSQ(storeHead)) != entry) {
                    snapshot.store_translation_reason = 0;
                } else if (LSQ(translationCancelPending)) {
                    snapshot.store_translation_reason = 1;
                } else if (LSQ(translationActive)) {
                    if (LSQ(translationOwnerStore) &&
                        static_cast<unsigned>(LSQ(translationOwnerStoreIndex)) == entry &&
                        static_cast<std::uint8_t>(LSQ(translationOwnerRobPointer)) == head_pointer) {
                        if (LSQ_IO(translationResponse_valid)) {
                            snapshot.store_translation_reason = LSQ(translationResponseFire) ? 7 : 6;
                        } else if (ATU_TLB(area_dataWalkPending)) {
                            snapshot.store_translation_reason = 3;
                        } else if (ATU_TLB(area_dataProbePending)) {
                            snapshot.store_translation_reason = 2;
                        } else if (ATU(area_dataSearchPending)) {
                            snapshot.store_translation_reason = 4;
                        } else if (ATU(area_dataResponseValid)) {
                            snapshot.store_translation_reason = 5;
                        } else {
                            snapshot.store_translation_reason = 8;
                        }
                    } else if (LSQ(translationOwnerStore)) {
                        snapshot.store_translation_reason = 9;
                    } else {
                        snapshot.store_translation_reason = 10;
                    }
                } else if (LSQ(translationRequestFire)) {
                    snapshot.store_translation_reason = 11;
                } else if (LSQ_IO(translationRequest_valid)) {
                    snapshot.store_translation_reason = 12;
                } else {
                    snapshot.store_translation_reason = 13;
                }
            } else if (store_uncached[entry] && !store_completed[entry]) {
                snapshot.head_incomplete_reason = store_sent[entry] ? 9 : 22;
            } else {
                snapshot.head_incomplete_reason = 10;
                if (static_cast<unsigned>(LSQ(storeHead)) != entry) {
                    snapshot.store_completion_reason = 0;
                } else if (!store_completed[entry]) {
                    if (LSQ(storeCompletionFire)) {
                        snapshot.store_completion_reason = 3;
                    } else if (LSQ(storeCompletionCandidate)) {
                        snapshot.store_completion_reason = LSQ(forwardCandidate) ? 2 : 1;
                    } else {
                        snapshot.store_completion_reason = 8;
                    }
                } else if (LSQ(completionValid) &&
                           static_cast<std::uint8_t>(LSQ(completion_robPointer)) == head_pointer) {
                    snapshot.store_completion_reason = 4;
                } else if ((CLUSTER(execution_io_completionValid) & (1U << 3)) != 0 &&
                           static_cast<std::uint8_t>(CLUSTER(execution_io_completion_3_robPointer)) == head_pointer) {
                    snapshot.store_completion_reason = 5;
                } else if ((ROB(stagedCompletionValid) & ROB(stagedCompletionCurrent) & (1U << 3)) != 0 &&
                           static_cast<std::uint8_t>(ROB(stagedRobPointer_3)) == head_pointer) {
                    snapshot.store_completion_reason = 6;
                } else {
                    snapshot.store_completion_reason = 7;
                }
            }
            classified = true;
        }
        if (!classified && divider_busy &&
            static_cast<std::uint8_t>(CLUSTER(execution__DOT__divider__DOT__uop_robPointer)) == head_pointer) {
            snapshot.head_incomplete_reason = 12;
            classified = true;
        }
        if (!classified && static_cast<bool>(CLUSTER(execution__DOT__multiplier__DOT__valid)) &&
            static_cast<std::uint8_t>(CLUSTER(execution__DOT__multiplier__DOT__uop_robPointer)) == head_pointer) {
            snapshot.head_incomplete_reason = 13;
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
                        (ready_maps[queue] & (1U << entry)) != 0 ? 17 : 16;
                    classified = true;
                }
            }
        }
        for (unsigned lane = 0; lane < 5 && !classified; lane++) {
            if ((staged_valid & (1U << lane)) != 0 && staged_pointer[lane] == head_pointer) {
                snapshot.head_incomplete_reason = 18;
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
                snapshot.head_incomplete_reason = 19;
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
                snapshot.head_incomplete_reason = 20;
                classified = true;
            }
        }
        if (!classified) {
            snapshot.head_incomplete_reason = 21;
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
    snapshot.frontend_occupancy = static_cast<std::uint8_t>(SYS(frontend__DOT__count) & 0xfU);
    snapshot.frontend_translation_request_fire =
        static_cast<bool>(ATU(area_instructionRequestFire));
    snapshot.frontend_translation_outstanding =
        static_cast<bool>(SYS(frontend__DOT__translationOutstanding));
    snapshot.frontend_translated_request_valid =
        static_cast<bool>(SYS(frontend__DOT__translatedRequestValid));
    snapshot.frontend_cache_request_base_valid =
        static_cast<bool>(SYS(frontend__DOT__cacheRequestBaseValid));
    snapshot.frontend_cache_request_fire =
        static_cast<bool>(SYS(frontend__DOT__requestFire));
    snapshot.frontend_cache_response_fire =
        static_cast<bool>(SYS(frontend__DOT__responseFire));
    snapshot.frontend_cache_outstanding =
        static_cast<bool>(SYS(frontend__DOT__cacheOutstanding));
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
        snapshot.issue_occupancy[queue] = static_cast<std::uint8_t>(issue_counts[queue] & 0xfU);
    }
    snapshot.issue_operand_valid = static_cast<std::uint8_t>(BACKEND(issueOperandValid) & 0xfU);
    snapshot.issue_fire = static_cast<std::uint8_t>(
        snapshot.issue_operand_valid & CLUSTER(execution_io_issueReady) & 0xfU);
    snapshot.dispatch_valid = static_cast<std::uint8_t>(
        popcount8(static_cast<std::uint8_t>(BACKEND(dispatchWindow_io_outputValid) & 0x7U)));
    const std::uint8_t dispatch_port_valid =
        static_cast<std::uint8_t>(BACKEND(router_io_portValid) & 0xfU);
    const std::uint8_t dispatch_port_ready =
        static_cast<std::uint8_t>(BACKEND(router_io_portReady) & 0xfU);
    snapshot.dispatch_fire = static_cast<std::uint8_t>(dispatch_port_valid & dispatch_port_ready);

    snapshot.committed_branch_mask = static_cast<std::uint8_t>(SYS(committedBranch) & 0x7U);
    snapshot.predictor_update_valid =
        SYS(predictorUpdateQueue__DOT__count) != 0 &&
        !SYS(frontend__DOT__targetPredictor__DOT__invalidating);
    const std::uint8_t staged_branch_valid = static_cast<std::uint8_t>(
        ROB(stagedCompletionValid) & ROB(stagedCompletionCurrent) & 0x1fU);
    const bool staged_branch_resolved[5] = {
        static_cast<bool>(ROB(stagedBranchResolved_0)),
        static_cast<bool>(ROB(stagedBranchResolved_1)),
        static_cast<bool>(ROB(stagedBranchResolved_2)),
        static_cast<bool>(ROB(stagedBranchResolved_3)), false};
    const bool staged_branch_mispredict[5] = {
        static_cast<bool>(ROB(stagedBranchMispredict_0)),
        static_cast<bool>(ROB(stagedBranchMispredict_1)),
        static_cast<bool>(ROB(stagedBranchMispredict_2)),
        static_cast<bool>(ROB(stagedBranchMispredict_3)),
        static_cast<bool>(ROB(stagedBranchMispredict_4))};
    const std::uint8_t staged_branch_pointer[5] = {
        static_cast<std::uint8_t>(ROB(stagedRobPointer_0)),
        static_cast<std::uint8_t>(ROB(stagedRobPointer_1)),
        static_cast<std::uint8_t>(ROB(stagedRobPointer_2)),
        static_cast<std::uint8_t>(ROB(stagedRobPointer_3)),
        static_cast<std::uint8_t>(ROB(stagedRobPointer_4))};
    for (unsigned lane = 0; lane < 5; lane++) {
        snapshot.branch_resolved_pointer[lane] = staged_branch_pointer[lane] & 0x3fU;
        if ((staged_branch_valid & (1U << lane)) != 0 && staged_branch_resolved[lane]) {
            snapshot.branch_resolved_mask |= static_cast<std::uint8_t>(1U << lane);
            if (staged_branch_mispredict[lane]) {
                snapshot.branch_mispredict_mask |= static_cast<std::uint8_t>(1U << lane);
            }
        }
    }

    // D01 lower bound: the router's P3 payload defaults to dispatch lane 0
    // when P3 cannot accept anything. With IQ3's registered enqueue-ready
    // asserted, P3 ready can only be low because the Store Data Queue is full.
    // Restricting this to lane 0 avoids attributing prefix stalls from older
    // lanes to SDQ backpressure.
    const std::uint8_t dispatch_valid =
        static_cast<std::uint8_t>(BACKEND(dispatchWindow_io_outputValid) & 0x7U);
    const bool iq3_enqueue_ready = static_cast<bool>(ISSUE(3, enqueueReadyReg));
    const bool p3_ready = (BACKEND(router_io_portReady) & (1U << 3)) != 0;
    snapshot.d01_oldest_load_candidate = !snapshot.recovery &&
        (dispatch_valid & 1U) != 0 &&
        static_cast<bool>(BACKEND(router_io_portInput_3_decoded_isLoad)) &&
        iq3_enqueue_ready;
    snapshot.d01_oldest_load_blocked_by_sdq =
        snapshot.d01_oldest_load_candidate && !p3_ready;

    const std::uint8_t registered_wake =
        static_cast<std::uint8_t>(BACKEND(rob_io_completionWakeupCandidateValid));
    const std::uint8_t direct_wake =
        static_cast<std::uint8_t>(CLUSTER(execution_io_directWakeupValid));
    const std::uint8_t registered_pdst[3] = {
        static_cast<std::uint8_t>(ROB(stagedPdst_0)),
        static_cast<std::uint8_t>(ROB(stagedPdst_1)),
        static_cast<std::uint8_t>(ROB(stagedPdst_2))};
    const std::uint8_t direct_pdst[3] = {
        static_cast<std::uint8_t>(BACKEND(issueOperandUop_0_pdst)),
        static_cast<std::uint8_t>(BACKEND(issueOperandUop_1_pdst)),
        static_cast<std::uint8_t>(BACKEND(issueOperandUop_2_pdst))};
    const unsigned issue_entries[4] = {
        static_cast<unsigned>(ISSUE_COUNT(0)), static_cast<unsigned>(ISSUE_COUNT(1)),
        static_cast<unsigned>(ISSUE_COUNT(2)), static_cast<unsigned>(ISSUE_COUNT(3))};
    const std::uint8_t issue_psrc1[4][8] = {
        ISSUE_ROW(0, psrc1), ISSUE_ROW(1, psrc1), ISSUE_ROW(2, psrc1), ISSUE_ROW(3, psrc1)};
    const std::uint8_t issue_psrc2[4][8] = {
        ISSUE_ROW(0, psrc2), ISSUE_ROW(1, psrc2), ISSUE_ROW(2, psrc2), ISSUE_ROW(3, psrc2)};
    const std::uint8_t issue_src1_ready[4][8] = {
        ISSUE_ROW(0, source1Ready), ISSUE_ROW(1, source1Ready),
        ISSUE_ROW(2, source1Ready), ISSUE_ROW(3, source1Ready)};
    const std::uint8_t issue_src2_ready[4][8] = {
        ISSUE_ROW(0, source2Ready), ISSUE_ROW(1, source2Ready),
        ISSUE_ROW(2, source2Ready), ISSUE_ROW(3, source2Ready)};
    for (unsigned lane = 0; lane < 3 && !snapshot.recovery; lane++) {
        const bool conflict = (registered_wake & (1U << lane)) != 0 &&
            (direct_wake & (1U << lane)) != 0 && direct_pdst[lane] != 0 &&
            direct_pdst[lane] != registered_pdst[lane];
        if (!conflict) {
            continue;
        }
        snapshot.w01_conflict_mask |= static_cast<std::uint8_t>(1U << lane);
        unsigned affected = 0;
        for (unsigned queue = 0; queue < 4; queue++) {
            for (unsigned entry = 0; entry < issue_entries[queue] && entry < 8; entry++) {
                const bool waits_for_source1 = !issue_src1_ready[queue][entry] &&
                    issue_psrc1[queue][entry] == direct_pdst[lane];
                const bool waits_for_source2 = !issue_src2_ready[queue][entry] &&
                    issue_psrc2[queue][entry] == direct_pdst[lane];
                affected += waits_for_source1 || waits_for_source2;
            }
        }
        snapshot.w01_affected_consumers[lane] = static_cast<std::uint8_t>(affected);
        if (affected != 0) {
            snapshot.w01_affected_mask |= static_cast<std::uint8_t>(1U << lane);
        }
    }

    if (LSQ(translationRequestFire)) {
        snapshot.l05_translation_request_mode = ATU(area_dataTranslate) ? 3 :
            ATU(area_dataDmw0) ? 1 : ATU(area_dataDmw1) ? 2 : 0;
        snapshot.l05_translation_request_store = !LSQ(loadNeedsTranslation);
    }
    snapshot.l05_translation_response = static_cast<bool>(LSQ(translationResponseFire));
    if (LSQ(loadHeadReady) && LSQ(unknownOlderStore) != 0 && LSQ(translationActive) &&
        LSQ(translationOwnerStore) && !ATU(area_dataContext_translationEnabled)) {
        snapshot.l05_direct_store_block_mode = ATU(area_dataContext_dmw0Enabled) ? 1 :
            ATU(area_dataContext_dmw1Enabled) ? 2 : 0;
    }

    const bool load_valid_for_completion[8] = {
        static_cast<bool>(LSQ(loads_0_valid)), static_cast<bool>(LSQ(loads_1_valid)),
        static_cast<bool>(LSQ(loads_2_valid)), static_cast<bool>(LSQ(loads_3_valid)),
        static_cast<bool>(LSQ(loads_4_valid)), static_cast<bool>(LSQ(loads_5_valid)),
        static_cast<bool>(LSQ(loads_6_valid)), static_cast<bool>(LSQ(loads_7_valid))};
    const std::uint8_t load_pointer_for_completion[8] = {
        static_cast<std::uint8_t>(LSQ(loads_0_robPointer)),
        static_cast<std::uint8_t>(LSQ(loads_1_robPointer)),
        static_cast<std::uint8_t>(LSQ(loads_2_robPointer)),
        static_cast<std::uint8_t>(LSQ(loads_3_robPointer)),
        static_cast<std::uint8_t>(LSQ(loads_4_robPointer)),
        static_cast<std::uint8_t>(LSQ(loads_5_robPointer)),
        static_cast<std::uint8_t>(LSQ(loads_6_robPointer)),
        static_cast<std::uint8_t>(LSQ(loads_7_robPointer))};
    const std::uint8_t completion_pointer =
        static_cast<std::uint8_t>(LSQ_IO(completion_robPointer));
    bool completion_matches_load = false;
    for (unsigned entry = 0; entry < 8; entry++) {
        completion_matches_load |= load_valid_for_completion[entry] &&
            load_pointer_for_completion[entry] == completion_pointer;
    }
    const std::uint8_t completion_pdst =
        static_cast<std::uint8_t>(LSQ_IO(completion_pdst));
    snapshot.w02_load_completion = !snapshot.recovery &&
        static_cast<bool>(LSQ_IO(completionValid)) &&
        static_cast<bool>(LSQ_IO(completion_writesPdst)) && completion_pdst != 0 &&
        !static_cast<bool>(LSQ_IO(completion_exception_valid)) &&
        static_cast<std::uint8_t>(LSQ_IO(completion_recoveryEpoch)) ==
            static_cast<std::uint8_t>(BACKEND(recoveryEpoch)) &&
        completion_matches_load;
    if (snapshot.w02_load_completion) {
        for (unsigned queue = 0; queue < 4; queue++) {
            unsigned affected = 0;
            for (unsigned entry = 0; entry < issue_entries[queue] && entry < 8; entry++) {
                const bool waits_for_source1 = !issue_src1_ready[queue][entry] &&
                    issue_psrc1[queue][entry] == completion_pdst;
                const bool waits_for_source2 = !issue_src2_ready[queue][entry] &&
                    issue_psrc2[queue][entry] == completion_pdst;
                affected += waits_for_source1 || waits_for_source2;
            }
            snapshot.w02_affected_consumers[queue] = static_cast<std::uint8_t>(affected);
            if (affected != 0) {
                snapshot.w02_affected_queue_mask |= static_cast<std::uint8_t>(1U << queue);
            }
        }
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
    if (snapshot.frontend_occupancy < 9) {
        frontend_occupancy_hist_[snapshot.frontend_occupancy]++;
    }
    frontend_translation_request_fire_ += bit(snapshot.frontend_translation_request_fire);
    frontend_translation_outstanding_cycles_ += bit(snapshot.frontend_translation_outstanding);
    frontend_translated_request_valid_cycles_ += bit(snapshot.frontend_translated_request_valid);
    frontend_cache_request_base_valid_cycles_ += bit(snapshot.frontend_cache_request_base_valid);
    frontend_cache_request_fire_ += bit(snapshot.frontend_cache_request_fire);
    frontend_cache_response_fire_ += bit(snapshot.frontend_cache_response_fire);
    frontend_cache_outstanding_cycles_ += bit(snapshot.frontend_cache_outstanding);
    if (snapshot.frontend_cache_request_fire) {
        if (frontend_seen_request_) {
            const std::uint64_t interval = cycles_ - frontend_last_request_cycle_;
            frontend_request_interval_hist_[latency_bucket(interval)]++;
        }
        frontend_last_request_cycle_ = cycles_;
        frontend_seen_request_ = true;
    }
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
        issue_occupancy_sum_[queue] += snapshot.issue_occupancy[queue];
        issue_full_cycles_[queue] += bit(snapshot.issue_occupancy[queue] >= 8);
        issue_fire_by_port_[queue] += bit((snapshot.issue_fire & (1U << queue)) != 0);
    }
    const unsigned issue_valid = popcount8(snapshot.issue_operand_valid);
    const unsigned issue_fire = popcount8(snapshot.issue_fire);
    const unsigned dispatch_fire = popcount8(snapshot.dispatch_fire);
    issue_valid_sum_ += issue_valid;
    issue_fire_sum_ += issue_fire;
    dispatch_valid_sum_ += snapshot.dispatch_valid;
    dispatch_fire_sum_ += dispatch_fire;
    dispatch_fire_hist_[dispatch_fire]++;

    bool unresolved_mispredict = false;
    for (bool pending : branch_mispredict_pending_) {
        unresolved_mispredict = unresolved_mispredict || pending;
    }
    if (unresolved_mispredict) {
        branch_dispatch_fire_after_mispredict_ += dispatch_fire;
        branch_issue_fire_after_mispredict_ += issue_fire;
    }

    const unsigned committed_branches = popcount8(snapshot.committed_branch_mask);
    branch_commit_hist_[committed_branches]++;
    branch_retired_ += committed_branches;
    branch_multi_commit_cycles_ += bit(committed_branches > 1);
    predictor_update_cycles_ += bit(snapshot.predictor_update_valid);
    for (unsigned lane = 0; lane < 5; lane++) {
        if ((snapshot.branch_resolved_mask & (1U << lane)) == 0) {
            continue;
        }
        branch_resolved_++;
        if ((snapshot.branch_mispredict_mask & (1U << lane)) == 0) {
            continue;
        }
        branch_mispredicted_++;
        const std::uint8_t pointer = snapshot.branch_resolved_pointer[lane] & 0x3fU;
        if (!branch_mispredict_pending_[pointer]) {
            branch_mispredict_pending_[pointer] = true;
            branch_resolve_cycle_[pointer] = cycles_;
            const unsigned older_entries =
                (static_cast<unsigned>(pointer) - snapshot.rob_head_pointer) & 0x3fU;
            if (older_entries <= 31) {
                branch_older_entries_at_mispredict_sum_ += older_entries;
            }
        }
    }
    if (snapshot.recovery) {
        if (snapshot.recovery_cause == 1) {
            const std::uint8_t pointer = snapshot.recovery_pointer & 0x3fU;
            if (branch_mispredict_pending_[pointer]) {
                const std::uint64_t latency = cycles_ - branch_resolve_cycle_[pointer];
                branch_recovery_matches_++;
                branch_resolve_to_recovery_cycles_ += latency;
                branch_resolve_to_recovery_hist_[latency_bucket(latency)]++;
                branch_resolve_to_recovery_max_ = latency > branch_resolve_to_recovery_max_ ?
                    latency : branch_resolve_to_recovery_max_;
            } else {
                branch_recovery_without_resolution_++;
            }
        }
        for (bool &pending : branch_mispredict_pending_) {
            pending = false;
        }
    }
    d01_oldest_load_candidate_cycles_ += bit(snapshot.d01_oldest_load_candidate);
    d01_oldest_load_blocked_by_sdq_cycles_ +=
        bit(snapshot.d01_oldest_load_blocked_by_sdq);
    if (snapshot.e02_head_staged_lane != kNoReason) {
        e02_head_staged_cycles_++;
        e02_head_staged_lane_[snapshot.e02_head_staged_lane]++;
        e02_head_staged_class_[snapshot.e02_head_staged_class]++;
    }
    for (unsigned lane = 0; lane < 3; lane++) {
        w01_conflict_cycles_[lane] += bit((snapshot.w01_conflict_mask & (1U << lane)) != 0);
        w01_affected_cycles_[lane] += bit((snapshot.w01_affected_mask & (1U << lane)) != 0);
        w01_affected_consumers_[lane] += snapshot.w01_affected_consumers[lane];
    }
    if (l05_translation_pending_) {
        l05_pending_age_++;
    }
    if (snapshot.l05_translation_response) {
        if (l05_translation_pending_) {
            l05_responses_[l05_pending_mode_][l05_pending_owner_]++;
            l05_response_latency_cycles_[l05_pending_mode_][l05_pending_owner_] +=
                l05_pending_age_;
            l05_translation_pending_ = false;
        } else {
            l05_boundary_responses_++;
        }
    }
    if (snapshot.l05_translation_request_mode != kNoReason) {
        if (l05_translation_pending_) {
            l05_protocol_errors_++;
        }
        l05_pending_mode_ = snapshot.l05_translation_request_mode;
        l05_pending_owner_ = snapshot.l05_translation_request_store ? 1 : 0;
        l05_pending_age_ = 0;
        l05_translation_pending_ = true;
        l05_requests_[l05_pending_mode_][l05_pending_owner_]++;
    }
    if (snapshot.l05_direct_store_block_mode != kNoReason) {
        l05_direct_store_blocked_load_cycles_[snapshot.l05_direct_store_block_mode]++;
    }
    if (snapshot.w02_load_completion) {
        w02_load_completions_++;
        const bool any_affected = snapshot.w02_affected_queue_mask != 0;
        const bool safe_affected = (snapshot.w02_affected_queue_mask & 0x7U) != 0;
        w02_affected_cycles_ += bit(any_affected);
        w02_safe_affected_cycles_ += bit(safe_affected);
        for (unsigned queue = 0; queue < 4; queue++) {
            w02_affected_cycles_by_queue_[queue] +=
                bit((snapshot.w02_affected_queue_mask & (1U << queue)) != 0);
            w02_affected_consumers_[queue] += snapshot.w02_affected_consumers[queue];
        }
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
        if (snapshot.store_translation_reason != kNoReason) {
            store_translation_[snapshot.store_translation_reason]++;
        }
        if (snapshot.store_completion_reason != kNoReason) {
            store_completion_[snapshot.store_completion_reason]++;
        }
        queue_identity_mismatches_ += bit(snapshot.head_incomplete_reason == 5 ||
                                          snapshot.head_incomplete_reason == 11);
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
    std::fprintf(file, "  \"schema_version\": \"nscc-m01-v7\",\n");
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
    std::fprintf(file, "  \"head_incomplete_detail\": {\"load_address\": %llu, \"load_translation\": %llu, \"load_order_or_request\": %llu, \"load_response\": %llu, \"load_completion_to_rob\": %llu, \"load_queue_identity_mismatch\": %llu, \"store_address\": %llu, \"store_data\": %llu, \"store_translation\": %llu, \"store_uncached_request\": %llu, \"store_uncached_response\": %llu, \"store_completion_to_rob\": %llu, \"store_queue_identity_mismatch\": %llu, \"divider\": %llu, \"multiplier\": %llu, \"rob_candidate_not_valid\": %llu, \"rob_payload_not_ready\": %llu, \"issue_operand_wait\": %llu, \"issue_ready_wait\": %llu, \"rob_staged_completion\": %llu, \"issue_operand_stage\": %llu, \"issue_address_stage\": %llu, \"execution_dispatch_or_untracked\": %llu},\n",
                 static_cast<unsigned long long>(head_incomplete_[0]), static_cast<unsigned long long>(head_incomplete_[1]),
                 static_cast<unsigned long long>(head_incomplete_[2]), static_cast<unsigned long long>(head_incomplete_[3]),
                 static_cast<unsigned long long>(head_incomplete_[4]), static_cast<unsigned long long>(head_incomplete_[5]),
                 static_cast<unsigned long long>(head_incomplete_[6]), static_cast<unsigned long long>(head_incomplete_[7]),
                 static_cast<unsigned long long>(head_incomplete_[8]), static_cast<unsigned long long>(head_incomplete_[22]),
                 static_cast<unsigned long long>(head_incomplete_[9]), static_cast<unsigned long long>(head_incomplete_[10]),
                 static_cast<unsigned long long>(head_incomplete_[11]),
                 static_cast<unsigned long long>(head_incomplete_[12]), static_cast<unsigned long long>(head_incomplete_[13]),
                 static_cast<unsigned long long>(head_incomplete_[14]), static_cast<unsigned long long>(head_incomplete_[15]),
                 static_cast<unsigned long long>(head_incomplete_[16]), static_cast<unsigned long long>(head_incomplete_[17]),
                 static_cast<unsigned long long>(head_incomplete_[18]), static_cast<unsigned long long>(head_incomplete_[19]),
                 static_cast<unsigned long long>(head_incomplete_[20]), static_cast<unsigned long long>(head_incomplete_[21]));
    std::fprintf(file, "  \"store_translation_detail\": {\"older_store_queue_entry\": %llu, \"cancel_pending\": %llu, \"active_head_tlb_probe\": %llu, \"active_head_tlb_walk\": %llu, \"active_head_atu_search\": %llu, \"active_head_atu_response\": %llu, \"active_head_response_blocked\": %llu, \"active_head_response_fire\": %llu, \"active_head_other\": %llu, \"active_other_store\": %llu, \"active_load\": %llu, \"request_fire\": %llu, \"request_blocked\": %llu, \"request_not_formed\": %llu},\n",
                 static_cast<unsigned long long>(store_translation_[0]), static_cast<unsigned long long>(store_translation_[1]),
                 static_cast<unsigned long long>(store_translation_[2]), static_cast<unsigned long long>(store_translation_[3]),
                 static_cast<unsigned long long>(store_translation_[4]), static_cast<unsigned long long>(store_translation_[5]),
                 static_cast<unsigned long long>(store_translation_[6]), static_cast<unsigned long long>(store_translation_[7]),
                 static_cast<unsigned long long>(store_translation_[8]), static_cast<unsigned long long>(store_translation_[9]),
                 static_cast<unsigned long long>(store_translation_[10]), static_cast<unsigned long long>(store_translation_[11]),
                 static_cast<unsigned long long>(store_translation_[12]), static_cast<unsigned long long>(store_translation_[13]));
    std::fprintf(file, "  \"store_completion_detail\": {\"older_store_queue_entry\": %llu, \"blocked_by_data_response\": %llu, \"blocked_by_load_forward\": %llu, \"completion_fire\": %llu, \"lsq_completion_register\": %llu, \"execution_lane3\": %llu, \"rob_staged_lane3\": %llu, \"completed_waiting_other\": %llu, \"candidate_not_ready\": %llu},\n",
                 static_cast<unsigned long long>(store_completion_[0]), static_cast<unsigned long long>(store_completion_[1]),
                 static_cast<unsigned long long>(store_completion_[2]), static_cast<unsigned long long>(store_completion_[3]),
                 static_cast<unsigned long long>(store_completion_[4]), static_cast<unsigned long long>(store_completion_[5]),
                 static_cast<unsigned long long>(store_completion_[6]), static_cast<unsigned long long>(store_completion_[7]),
                 static_cast<unsigned long long>(store_completion_[8]));
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
    std::fprintf(file, "  \"frontend\": {\"decode_valid_sum\": %llu, \"occupancy_histogram\": [%llu, %llu, %llu, %llu, %llu, %llu, %llu, %llu, %llu], \"translation_request_fire\": %llu, \"translation_outstanding_cycles\": %llu, \"translated_request_valid_cycles\": %llu, \"cache_request_base_valid_cycles\": %llu, \"cache_request_fire\": %llu, \"cache_response_fire\": %llu, \"cache_outstanding_cycles\": %llu, \"request_interval_histogram\": [%llu, %llu, %llu, %llu, %llu, %llu, %llu, %llu], \"cache_request\": %llu, \"uncached_request\": %llu},\n",
                 static_cast<unsigned long long>(frontend_decode_valid_sum_),
                 static_cast<unsigned long long>(frontend_occupancy_hist_[0]),
                 static_cast<unsigned long long>(frontend_occupancy_hist_[1]),
                 static_cast<unsigned long long>(frontend_occupancy_hist_[2]),
                 static_cast<unsigned long long>(frontend_occupancy_hist_[3]),
                 static_cast<unsigned long long>(frontend_occupancy_hist_[4]),
                 static_cast<unsigned long long>(frontend_occupancy_hist_[5]),
                 static_cast<unsigned long long>(frontend_occupancy_hist_[6]),
                 static_cast<unsigned long long>(frontend_occupancy_hist_[7]),
                 static_cast<unsigned long long>(frontend_occupancy_hist_[8]),
                 static_cast<unsigned long long>(frontend_translation_request_fire_),
                 static_cast<unsigned long long>(frontend_translation_outstanding_cycles_),
                 static_cast<unsigned long long>(frontend_translated_request_valid_cycles_),
                 static_cast<unsigned long long>(frontend_cache_request_base_valid_cycles_),
                 static_cast<unsigned long long>(frontend_cache_request_fire_),
                 static_cast<unsigned long long>(frontend_cache_response_fire_),
                 static_cast<unsigned long long>(frontend_cache_outstanding_cycles_),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[0]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[1]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[2]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[3]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[4]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[5]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[6]),
                 static_cast<unsigned long long>(frontend_request_interval_hist_[7]),
                 static_cast<unsigned long long>(frontend_cache_request_),
                 static_cast<unsigned long long>(frontend_uncached_request_));
    std::fprintf(file, "  \"issue\": {\"ready_cycles\": [%llu, %llu, %llu, %llu], \"ready_entries\": [%llu, %llu, %llu, %llu], \"occupancy_sum\": [%llu, %llu, %llu, %llu], \"full_cycles\": [%llu, %llu, %llu, %llu], \"operand_valid_sum\": %llu, \"fire_sum\": %llu, \"fire_by_port\": [%llu, %llu, %llu, %llu]},\n",
                 static_cast<unsigned long long>(issue_ready_cycles_[0]), static_cast<unsigned long long>(issue_ready_cycles_[1]),
                 static_cast<unsigned long long>(issue_ready_cycles_[2]), static_cast<unsigned long long>(issue_ready_cycles_[3]),
                 static_cast<unsigned long long>(issue_ready_entries_[0]), static_cast<unsigned long long>(issue_ready_entries_[1]),
                 static_cast<unsigned long long>(issue_ready_entries_[2]), static_cast<unsigned long long>(issue_ready_entries_[3]),
                 static_cast<unsigned long long>(issue_occupancy_sum_[0]), static_cast<unsigned long long>(issue_occupancy_sum_[1]),
                 static_cast<unsigned long long>(issue_occupancy_sum_[2]), static_cast<unsigned long long>(issue_occupancy_sum_[3]),
                 static_cast<unsigned long long>(issue_full_cycles_[0]), static_cast<unsigned long long>(issue_full_cycles_[1]),
                 static_cast<unsigned long long>(issue_full_cycles_[2]), static_cast<unsigned long long>(issue_full_cycles_[3]),
                 static_cast<unsigned long long>(issue_valid_sum_), static_cast<unsigned long long>(issue_fire_sum_),
                 static_cast<unsigned long long>(issue_fire_by_port_[0]), static_cast<unsigned long long>(issue_fire_by_port_[1]),
                 static_cast<unsigned long long>(issue_fire_by_port_[2]), static_cast<unsigned long long>(issue_fire_by_port_[3]));
    std::fprintf(file, "  \"dispatch\": {\"valid_sum\": %llu, \"fire_sum\": %llu, \"fire_histogram\": [%llu, %llu, %llu, %llu], \"d01_oldest_load_candidate_cycles\": %llu, \"d01_oldest_load_blocked_by_sdq_cycles\": %llu},\n",
                 static_cast<unsigned long long>(dispatch_valid_sum_),
                 static_cast<unsigned long long>(dispatch_fire_sum_),
                 static_cast<unsigned long long>(dispatch_fire_hist_[0]),
                 static_cast<unsigned long long>(dispatch_fire_hist_[1]),
                 static_cast<unsigned long long>(dispatch_fire_hist_[2]),
                 static_cast<unsigned long long>(dispatch_fire_hist_[3]),
                 static_cast<unsigned long long>(d01_oldest_load_candidate_cycles_),
                 static_cast<unsigned long long>(d01_oldest_load_blocked_by_sdq_cycles_));
    std::fprintf(file, "  \"branch\": {\"commit_histogram\": [%llu, %llu, %llu, %llu], \"retired\": %llu, \"multi_commit_cycles\": %llu, \"predictor_update_cycles\": %llu, \"resolved\": %llu, \"mispredicted\": %llu, \"recovery_matches\": %llu, \"recovery_without_resolution\": %llu, \"resolve_to_recovery_cycles\": %llu, \"resolve_to_recovery_max\": %llu, \"resolve_to_recovery_histogram\": [%llu, %llu, %llu, %llu, %llu, %llu, %llu, %llu], \"older_entries_at_mispredict_sum\": %llu, \"dispatch_fire_after_unrecovered_mispredict\": %llu, \"issue_fire_after_unrecovered_mispredict\": %llu},\n",
                 static_cast<unsigned long long>(branch_commit_hist_[0]),
                 static_cast<unsigned long long>(branch_commit_hist_[1]),
                 static_cast<unsigned long long>(branch_commit_hist_[2]),
                 static_cast<unsigned long long>(branch_commit_hist_[3]),
                 static_cast<unsigned long long>(branch_retired_),
                 static_cast<unsigned long long>(branch_multi_commit_cycles_),
                 static_cast<unsigned long long>(predictor_update_cycles_),
                 static_cast<unsigned long long>(branch_resolved_),
                 static_cast<unsigned long long>(branch_mispredicted_),
                 static_cast<unsigned long long>(branch_recovery_matches_),
                 static_cast<unsigned long long>(branch_recovery_without_resolution_),
                 static_cast<unsigned long long>(branch_resolve_to_recovery_cycles_),
                 static_cast<unsigned long long>(branch_resolve_to_recovery_max_),
                 static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[0]),
                 static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[1]),
                 static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[2]),
                 static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[3]),
                 static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[4]),
                 static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[5]),
                 static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[6]),
                 static_cast<unsigned long long>(branch_resolve_to_recovery_hist_[7]),
                 static_cast<unsigned long long>(branch_older_entries_at_mispredict_sum_),
                 static_cast<unsigned long long>(branch_dispatch_fire_after_mispredict_),
                 static_cast<unsigned long long>(branch_issue_fire_after_mispredict_));
    std::fprintf(file, "  \"e02\": {\"head_staged_cycles\": %llu, \"by_lane\": [%llu, %llu, %llu, %llu, %llu], \"by_class\": {\"load\": %llu, \"store\": %llu, \"branch\": %llu, \"multiplier\": %llu, \"lane0_other\": %llu, \"lane1_other\": %llu, \"lane2_other\": %llu, \"lane3_other\": %llu}},\n",
                 static_cast<unsigned long long>(e02_head_staged_cycles_),
                 static_cast<unsigned long long>(e02_head_staged_lane_[0]),
                 static_cast<unsigned long long>(e02_head_staged_lane_[1]),
                 static_cast<unsigned long long>(e02_head_staged_lane_[2]),
                 static_cast<unsigned long long>(e02_head_staged_lane_[3]),
                 static_cast<unsigned long long>(e02_head_staged_lane_[4]),
                 static_cast<unsigned long long>(e02_head_staged_class_[0]),
                 static_cast<unsigned long long>(e02_head_staged_class_[1]),
                 static_cast<unsigned long long>(e02_head_staged_class_[2]),
                 static_cast<unsigned long long>(e02_head_staged_class_[3]),
                 static_cast<unsigned long long>(e02_head_staged_class_[4]),
                 static_cast<unsigned long long>(e02_head_staged_class_[5]),
                 static_cast<unsigned long long>(e02_head_staged_class_[6]),
                 static_cast<unsigned long long>(e02_head_staged_class_[7]));
    std::fprintf(file, "  \"w01\": {\"conflict_cycles_by_lane\": [%llu, %llu, %llu], \"affected_cycles_by_lane\": [%llu, %llu, %llu], \"affected_consumer_entries_by_lane\": [%llu, %llu, %llu]},\n",
                 static_cast<unsigned long long>(w01_conflict_cycles_[0]),
                 static_cast<unsigned long long>(w01_conflict_cycles_[1]),
                 static_cast<unsigned long long>(w01_conflict_cycles_[2]),
                 static_cast<unsigned long long>(w01_affected_cycles_[0]),
                 static_cast<unsigned long long>(w01_affected_cycles_[1]),
                 static_cast<unsigned long long>(w01_affected_cycles_[2]),
                 static_cast<unsigned long long>(w01_affected_consumers_[0]),
                 static_cast<unsigned long long>(w01_affected_consumers_[1]),
                 static_cast<unsigned long long>(w01_affected_consumers_[2]));
    std::fprintf(file, "  \"l05\": {\"requests_by_mode\": {\"direct\": [%llu, %llu], \"dmw0\": [%llu, %llu], \"dmw1\": [%llu, %llu], \"tlb\": [%llu, %llu]}, \"responses_by_mode\": {\"direct\": [%llu, %llu], \"dmw0\": [%llu, %llu], \"dmw1\": [%llu, %llu], \"tlb\": [%llu, %llu]}, \"response_latency_cycles_by_mode\": {\"direct\": [%llu, %llu], \"dmw0\": [%llu, %llu], \"dmw1\": [%llu, %llu], \"tlb\": [%llu, %llu]}, \"direct_dmw_store_blocked_load_cycles\": {\"direct\": %llu, \"dmw0\": %llu, \"dmw1\": %llu}, \"boundary_responses\": %llu, \"protocol_errors\": %llu, \"pending_at_end\": %s},\n",
                 static_cast<unsigned long long>(l05_requests_[0][0]), static_cast<unsigned long long>(l05_requests_[0][1]),
                 static_cast<unsigned long long>(l05_requests_[1][0]), static_cast<unsigned long long>(l05_requests_[1][1]),
                 static_cast<unsigned long long>(l05_requests_[2][0]), static_cast<unsigned long long>(l05_requests_[2][1]),
                 static_cast<unsigned long long>(l05_requests_[3][0]), static_cast<unsigned long long>(l05_requests_[3][1]),
                 static_cast<unsigned long long>(l05_responses_[0][0]), static_cast<unsigned long long>(l05_responses_[0][1]),
                 static_cast<unsigned long long>(l05_responses_[1][0]), static_cast<unsigned long long>(l05_responses_[1][1]),
                 static_cast<unsigned long long>(l05_responses_[2][0]), static_cast<unsigned long long>(l05_responses_[2][1]),
                 static_cast<unsigned long long>(l05_responses_[3][0]), static_cast<unsigned long long>(l05_responses_[3][1]),
                 static_cast<unsigned long long>(l05_response_latency_cycles_[0][0]), static_cast<unsigned long long>(l05_response_latency_cycles_[0][1]),
                 static_cast<unsigned long long>(l05_response_latency_cycles_[1][0]), static_cast<unsigned long long>(l05_response_latency_cycles_[1][1]),
                 static_cast<unsigned long long>(l05_response_latency_cycles_[2][0]), static_cast<unsigned long long>(l05_response_latency_cycles_[2][1]),
                 static_cast<unsigned long long>(l05_response_latency_cycles_[3][0]), static_cast<unsigned long long>(l05_response_latency_cycles_[3][1]),
                 static_cast<unsigned long long>(l05_direct_store_blocked_load_cycles_[0]),
                 static_cast<unsigned long long>(l05_direct_store_blocked_load_cycles_[1]),
                 static_cast<unsigned long long>(l05_direct_store_blocked_load_cycles_[2]),
                 static_cast<unsigned long long>(l05_boundary_responses_),
                 static_cast<unsigned long long>(l05_protocol_errors_),
                 l05_translation_pending_ ? "true" : "false");
    std::fprintf(file, "  \"w02\": {\"load_completions\": %llu, \"affected_cycles\": %llu, \"safe_p0_p2_affected_cycles\": %llu, \"affected_cycles_by_queue\": [%llu, %llu, %llu, %llu], \"affected_consumer_entries_by_queue\": [%llu, %llu, %llu, %llu]},\n",
                 static_cast<unsigned long long>(w02_load_completions_),
                 static_cast<unsigned long long>(w02_affected_cycles_),
                 static_cast<unsigned long long>(w02_safe_affected_cycles_),
                 static_cast<unsigned long long>(w02_affected_cycles_by_queue_[0]),
                 static_cast<unsigned long long>(w02_affected_cycles_by_queue_[1]),
                 static_cast<unsigned long long>(w02_affected_cycles_by_queue_[2]),
                 static_cast<unsigned long long>(w02_affected_cycles_by_queue_[3]),
                 static_cast<unsigned long long>(w02_affected_consumers_[0]),
                 static_cast<unsigned long long>(w02_affected_consumers_[1]),
                 static_cast<unsigned long long>(w02_affected_consumers_[2]),
                 static_cast<unsigned long long>(w02_affected_consumers_[3]));
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
    std::fprintf(file, "  \"invariants\": {\"retire_hist_cycles\": %s, \"retire_hist_instructions\": %s, \"commit_observation\": %s, \"source_retire_alignment\": %s, \"queue_identity\": %s, \"queue_identity_mismatches\": %llu, \"source_retire_alignment_errors\": %llu, \"sampling_protocol_errors\": %llu, \"non_prefix_retire_cycles\": %llu, \"commit_offer_vs_retired_cycles\": %llu}\n",
                 hist_cycles_ok ? "true" : "false", hist_instructions_ok ? "true" : "false",
                 commit_count_ok ? "true" : "false",
                 source_retire_alignment_errors_ == 0 ? "true" : "false",
                 queue_identity_mismatches_ == 0 ? "true" : "false",
                 static_cast<unsigned long long>(queue_identity_mismatches_),
                 static_cast<unsigned long long>(source_retire_alignment_errors_),
                 static_cast<unsigned long long>(sampling_protocol_errors_), static_cast<unsigned long long>(non_prefix_retire_),
                 static_cast<unsigned long long>(commit_offer_vs_retired_cycles_));
    std::fprintf(file, "}\n");
    std::fclose(file);
}
