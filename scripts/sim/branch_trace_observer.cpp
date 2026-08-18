#include "branch_trace_observer.h"

#include <cstdio>
#include <cstdlib>

namespace {

FILE *trace_file = nullptr;
bool trace_disabled = false;
std::uint64_t event_sequence = 0;
std::uint64_t marker_sequence = 0;
std::uint8_t configured_pht_index_width = 12;
std::uint8_t configured_metadata_valid_bit = 14;

const char *trace_path() {
    const char *configured = std::getenv("MIKU_BRANCH_TRACE_PATH");
    return configured != nullptr && configured[0] != '\0'
               ? configured
               : "branch-trace-v2.jsonl";
}

void close_trace() {
    if (trace_file != nullptr) {
        std::fflush(trace_file);
        std::fclose(trace_file);
        trace_file = nullptr;
    }
}

void open_trace(std::uint8_t pht_index_width, std::uint8_t metadata_valid_bit) {
    if (trace_file != nullptr || trace_disabled) {
        return;
    }
    trace_file = std::fopen(trace_path(), "w");
    if (trace_file == nullptr) {
        trace_disabled = true;
        std::fprintf(
            stderr,
            "warning: cannot open branch trace '%s'; branch observation disabled\n",
            trace_path());
        return;
    }
    std::setvbuf(trace_file, nullptr, _IOFBF, 1U << 20);
    std::fprintf(
        trace_file,
        "{\"kind\":\"header\",\"format\":\"miku-branch-trace-v2\","
        "\"schema_version\":2,\"pht_index_width\":%u,\"metadata_valid_bit\":%u}\n",
        static_cast<unsigned>(pht_index_width),
        static_cast<unsigned>(metadata_valid_bit));
    std::atexit(close_trace);
}

}  // namespace

extern "C" void miku_branch_trace_init(std::uint8_t pht_index_width,
                                        std::uint8_t metadata_valid_bit) {
    configured_pht_index_width = pht_index_width;
    configured_metadata_valid_bit = metadata_valid_bit;
    open_trace(pht_index_width, metadata_valid_bit);
}

extern "C" void miku_branch_trace_marker(
    std::uint64_t cycle,
    std::uint8_t lane,
    std::uint32_t pc,
    std::uint32_t instruction) {
    open_trace(configured_pht_index_width, configured_metadata_valid_bit);
    if (trace_file == nullptr) {
        return;
    }
    std::fprintf(
        trace_file,
        "{\"kind\":\"marker\",\"seq\":%llu,\"cycle\":%llu,\"lane\":%u,"
        "\"pc\":\"0x%08x\",\"instruction\":\"0x%08x\"}\n",
        static_cast<unsigned long long>(marker_sequence++),
        static_cast<unsigned long long>(cycle),
        static_cast<unsigned>(lane),
        pc,
        instruction);
}

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
    std::uint8_t metadata_valid_bit) {
    open_trace(pht_index_width, metadata_valid_bit);
    if (trace_file == nullptr) {
        return;
    }
    if (pht_index_width == 0 || pht_index_width > 16 ||
        metadata_valid_bit >= 32) {
        std::fprintf(stderr, "warning: invalid branch trace metadata layout\n");
        close_trace();
        trace_disabled = true;
        return;
    }

    const std::uint32_t index_mask = (1U << pht_index_width) - 1U;
    const std::uint32_t pht_index = predictor_metadata & index_mask;
    const std::uint32_t pht_state =
        (predictor_metadata >> pht_index_width) & 0x3U;
    const std::uint32_t pht_valid =
        (predictor_metadata >> metadata_valid_bit) & 0x1U;
    const bool low_confidence = pht_valid != 0 && (pht_state == 1 || pht_state == 2);

    std::fprintf(
        trace_file,
        "{\"kind\":\"branch\",\"seq\":%llu,\"cycle\":%llu,\"lane\":%u,"
        "\"rob_pointer\":%u,\"pc\":\"0x%08x\",\"instruction\":\"0x%08x\","
        "\"predictor_type\":%u,\"actual_taken\":%u,\"actual_target\":\"0x%08x\","
        "\"predictor_metadata\":\"0x%04x\",\"pht_index\":%u,\"pht_state\":%u,"
        "\"pht_valid\":%u,\"low_confidence_pht\":%s}\n",
        static_cast<unsigned long long>(event_sequence++),
        static_cast<unsigned long long>(cycle),
        static_cast<unsigned>(lane),
        static_cast<unsigned>(rob_pointer),
        pc,
        instruction,
        static_cast<unsigned>(predictor_type),
        static_cast<unsigned>(actual_taken != 0),
        actual_target,
        predictor_metadata & 0xffffU,
        static_cast<unsigned>(pht_index),
        static_cast<unsigned>(pht_state),
        static_cast<unsigned>(pht_valid),
        low_confidence ? "true" : "false");
}
