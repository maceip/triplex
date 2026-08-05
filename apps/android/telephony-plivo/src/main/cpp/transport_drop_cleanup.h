#ifndef TRIPLEX_TRANSPORT_DROP_CLEANUP_H
#define TRIPLEX_TRANSPORT_DROP_CLEANUP_H

#include <atomic>
#include <cstdint>

namespace triplex::telephony {

/**
 * Operations required when the currently connected secure SIP socket drops.
 * PJSIP remains responsible for socket ownership and reconnection; Triplex
 * only invalidates caller-audible synthesis and detaches stale RTP media.
 */
class AbruptDropDriver {
public:
  virtual ~AbruptDropDriver() = default;
  virtual void flush_stale_synthesis() noexcept = 0;
  virtual void disconnect_rtp_media() noexcept = 0;
  virtual void invalidate_registration() noexcept = 0;
};

/**
 * Fixed-state, allocation-free guard shared by production callbacks and host
 * tests. A transport token is the stable PJSIP transport address cast to an
 * integer. A late close for an old socket cannot tear down its replacement.
 */
class SecureTransportLifecycle {
public:
  void on_connected(uintptr_t transport_token) noexcept {
    if (transport_token != 0) {
      active_transport_.store(transport_token, std::memory_order_release);
    }
  }

  bool on_abrupt_drop(uintptr_t transport_token,
                      AbruptDropDriver &driver) noexcept {
    if (transport_token == 0) {
      return false;
    }
    uintptr_t expected = transport_token;
    if (!active_transport_.compare_exchange_strong(
            expected, 0, std::memory_order_acq_rel,
            std::memory_order_acquire)) {
      return false;
    }
    // Ordering is part of the contract: publish cancellation before detaching
    // media, then invalidate readiness/registration state.
    driver.flush_stale_synthesis();
    driver.disconnect_rtp_media();
    driver.invalidate_registration();
    return true;
  }

  [[nodiscard]] bool has_connected_transport() const noexcept {
    return active_transport_.load(std::memory_order_acquire) != 0;
  }

  [[nodiscard]] uintptr_t active_transport_token() const noexcept {
    return active_transport_.load(std::memory_order_acquire);
  }

private:
  std::atomic<uintptr_t> active_transport_{0};
};

} // namespace triplex::telephony

#endif
