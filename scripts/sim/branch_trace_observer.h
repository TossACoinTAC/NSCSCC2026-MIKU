#ifndef MIKU_BRANCH_TRACE_OBSERVER_H
#define MIKU_BRANCH_TRACE_OBSERVER_H

#include <cstdint>

extern "C" void miku_branch_trace_init(std::uint8_t pht_index_width,
                                        std::uint8_t metadata_valid_bit);

extern "C" void miku_branch_trace_event(
    std::uint64_t cycle,
    std::uint8_t lane,
    std::uint32_t rob_pointer,
    std::uint32_t pc,
    std::uint32_t instruction,
    std::uint8_t predictor_type,
    std::uint8_t actual_taken,
    std::uint32_t actual_target,
    std::uint32_t predictor_metadata,
    std::uint8_t pht_index_width,
    std::uint8_t metadata_valid_bit);

#endif
