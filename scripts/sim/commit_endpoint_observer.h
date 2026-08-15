#ifndef MIKU_SIM_COMMIT_ENDPOINT_OBSERVER_H
#define MIKU_SIM_COMMIT_ENDPOINT_OBSERVER_H

#include <atomic>
#include <cstdint>
#include <limits>

namespace miku_sim {

enum class CompletionSource : unsigned int {
  None = 0,
  CommitEndpoint = 1,
  Perf20Pins = 2,
  Func58Pins = 3
};

inline std::atomic<std::uint64_t> &commitEndpointTarget() {
  static std::atomic<std::uint64_t> target{0};
  return target;
}

inline std::atomic<unsigned int> &commitEndpointLaneStorage() {
  static std::atomic<unsigned int> lane{std::numeric_limits<unsigned int>::max()};
  return lane;
}

inline std::atomic<bool> &commitEndpointReachedStorage() {
  static std::atomic<bool> reached{false};
  return reached;
}

inline std::atomic<unsigned int> &completionSourceStorage() {
  static std::atomic<unsigned int> source{
      static_cast<unsigned int>(CompletionSource::None)};
  return source;
}

inline void configureCommitEndpoint(std::uint64_t pc) {
  commitEndpointTarget().store(pc, std::memory_order_relaxed);
  commitEndpointLaneStorage().store(
      std::numeric_limits<unsigned int>::max(), std::memory_order_relaxed);
  commitEndpointReachedStorage().store(false, std::memory_order_release);
  completionSourceStorage().store(
      static_cast<unsigned int>(CompletionSource::None),
      std::memory_order_release);
}

inline void observeCommit(unsigned int lane, bool valid, std::uint64_t pc) {
  const std::uint64_t target =
      commitEndpointTarget().load(std::memory_order_relaxed);
  if (valid && target != 0 && pc == target) {
    commitEndpointLaneStorage().store(lane, std::memory_order_relaxed);
    completionSourceStorage().store(
        static_cast<unsigned int>(CompletionSource::CommitEndpoint),
        std::memory_order_relaxed);
    commitEndpointReachedStorage().store(true, std::memory_order_release);
  }
}

inline bool commitEndpointReached() {
  return commitEndpointReachedStorage().load(std::memory_order_acquire);
}

inline unsigned int commitEndpointLane() {
  return commitEndpointLaneStorage().load(std::memory_order_relaxed);
}

inline bool simulationCompletionReached(int profile, std::uint32_t numData,
                                        unsigned int ledRg0,
                                        unsigned int ledRg1) {
  if (commitEndpointReached()) {
    return true;
  }
  const bool passLeds = ledRg0 == 1 && ledRg1 == 1;
  CompletionSource source = CompletionSource::None;
  if (profile == 1 && passLeds && numData != 0) {
    source = CompletionSource::Perf20Pins;
  } else if (profile == 2 && passLeds && numData == 0x3a00003aU) {
    source = CompletionSource::Func58Pins;
  }
  if (source == CompletionSource::None) {
    return false;
  }
  completionSourceStorage().store(static_cast<unsigned int>(source),
                                  std::memory_order_release);
  return true;
}

inline const char *completionSourceName() {
  switch (static_cast<CompletionSource>(
      completionSourceStorage().load(std::memory_order_acquire))) {
    case CompletionSource::CommitEndpoint:
      return "commit-endpoint";
    case CompletionSource::Perf20Pins:
      return "perf20-pins";
    case CompletionSource::Func58Pins:
      return "func58-pins";
    default:
      return "none";
  }
}

}  // namespace miku_sim

#endif
