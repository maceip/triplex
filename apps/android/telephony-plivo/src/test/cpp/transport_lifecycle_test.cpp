#include "transport_drop_cleanup.h"

#include <exception>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

using triplex::telephony::AbruptDropDriver;
using triplex::telephony::SecureTransportLifecycle;

void expect(bool condition, const std::string &message) {
  if (!condition) {
    throw std::runtime_error(message);
  }
}

class MockRtpStreamDriver final : public AbruptDropDriver {
public:
  void flush_stale_synthesis() noexcept override {
    operations.emplace_back("flush");
    ++flush_count;
    synthesis_queued = false;
  }

  void disconnect_rtp_media() noexcept override {
    operations.emplace_back("disconnect_media");
    ++disconnect_count;
    media_attached = false;
  }

  void invalidate_registration() noexcept override {
    operations.emplace_back("invalidate_registration");
    ++invalidation_count;
    registered = false;
  }

  bool synthesis_queued = true;
  bool media_attached = true;
  bool registered = true;
  unsigned flush_count = 0;
  unsigned disconnect_count = 0;
  unsigned invalidation_count = 0;
  std::vector<std::string> operations;
};

class MockSocketDriver {
public:
  explicit MockSocketDriver(uintptr_t token) : token_(token) {
    expect(token != 0, "mock socket token must be non-zero");
  }

  void connect(SecureTransportLifecycle &lifecycle) {
    connected_ = true;
    lifecycle.on_connected(token_);
  }

  bool abrupt_drop(SecureTransportLifecycle &lifecycle,
                   MockRtpStreamDriver &rtp) {
    connected_ = false;
    return lifecycle.on_abrupt_drop(token_, rtp);
  }

  [[nodiscard]] bool connected() const { return connected_; }
  [[nodiscard]] uintptr_t token() const { return token_; }

private:
  uintptr_t token_;
  bool connected_ = false;
};

struct CheckResult {
  std::string id;
  bool passed;
  std::string detail;
};

class Runner {
public:
  void check(const std::string &id, const std::function<void()> &body) {
    try {
      body();
      results_.push_back({id, true, ""});
    } catch (const std::exception &error) {
      results_.push_back({id, false, error.what()});
    }
  }

  [[nodiscard]] bool all_passed() const {
    for (const auto &result : results_) {
      if (!result.passed) {
        return false;
      }
    }
    return !results_.empty();
  }

  [[nodiscard]] const std::vector<CheckResult> &results() const {
    return results_;
  }

private:
  std::vector<CheckResult> results_;
};

std::string json_escape(const std::string &value) {
  std::string escaped;
  escaped.reserve(value.size());
  for (const char character : value) {
    switch (character) {
    case '\\':
      escaped += "\\\\";
      break;
    case '"':
      escaped += "\\\"";
      break;
    case '\n':
      escaped += "\\n";
      break;
    case '\r':
      escaped += "\\r";
      break;
    case '\t':
      escaped += "\\t";
      break;
    default:
      escaped += character;
    }
  }
  return escaped;
}

void write_evidence(const std::filesystem::path &output, const Runner &runner) {
  if (!output.parent_path().empty()) {
    std::filesystem::create_directories(output.parent_path());
  }
  std::ofstream stream(output, std::ios::out | std::ios::trunc);
  if (!stream) {
    throw std::runtime_error("unable to create validation artifact");
  }
  stream << "{\n"
         << "  \"schema\": \"triplex.transport-validation-suite.v1\",\n"
         << "  \"suite\": \"cpp_transport_lifecycle\",\n"
         << "  \"evidence_kind\": \"CI_MOCK\",\n"
         << "  \"all_passed\": "
         << (runner.all_passed() ? "true" : "false") << ",\n"
         << "  \"production_ready\": false,\n"
         << "  \"checks\": [\n";
  const auto &results = runner.results();
  for (size_t index = 0; index < results.size(); ++index) {
    const auto &result = results[index];
    stream << "    {\"id\": \"" << json_escape(result.id)
           << "\", \"status\": \"" << (result.passed ? "PASS" : "FAIL")
           << "\"";
    if (!result.detail.empty()) {
      stream << ", \"detail\": \"" << json_escape(result.detail) << "\"";
    }
    stream << "}" << (index + 1 == results.size() ? "\n" : ",\n");
  }
  stream << "  ]\n}\n";
  if (!stream) {
    throw std::runtime_error("unable to finish validation artifact");
  }
}

std::filesystem::path output_path(int argc, char **argv) {
  for (int index = 1; index + 1 < argc; ++index) {
    if (std::string(argv[index]) == "--output") {
      return argv[index + 1];
    }
  }
  return "cpp-transport-validation.json";
}

} // namespace

int main(int argc, char **argv) {
  Runner runner;

  runner.check("abrupt_drop_flushes_and_detaches", [] {
    SecureTransportLifecycle lifecycle;
    MockSocketDriver wifi_socket(0x1000);
    MockRtpStreamDriver rtp;
    wifi_socket.connect(lifecycle);

    expect(wifi_socket.abrupt_drop(lifecycle, rtp),
           "active socket drop was not claimed");
    expect(!wifi_socket.connected(), "mock socket still connected after drop");
    expect(!lifecycle.has_connected_transport(),
           "lifecycle retained dropped transport");
    expect(!rtp.synthesis_queued, "stale synthesis was not flushed");
    expect(!rtp.media_attached, "RTP media remained attached");
    expect(!rtp.registered, "registration remained ready");
    expect(rtp.operations ==
               std::vector<std::string>{"flush", "disconnect_media",
                                        "invalidate_registration"},
           "abrupt cleanup operation order changed");
  });

  runner.check("duplicate_drop_cleanup_is_idempotent", [] {
    SecureTransportLifecycle lifecycle;
    MockSocketDriver wifi_socket(0x2000);
    MockRtpStreamDriver rtp;
    wifi_socket.connect(lifecycle);
    expect(wifi_socket.abrupt_drop(lifecycle, rtp), "first drop was ignored");
    expect(!wifi_socket.abrupt_drop(lifecycle, rtp),
           "duplicate drop repeated cleanup");
    expect(rtp.flush_count == 1 && rtp.disconnect_count == 1 &&
               rtp.invalidation_count == 1,
           "cleanup was not exactly once");
  });

  runner.check("stale_socket_drop_preserves_replacement", [] {
    SecureTransportLifecycle lifecycle;
    MockSocketDriver wifi_socket(0x3000);
    MockSocketDriver cellular_socket(0x4000);
    MockRtpStreamDriver rtp;
    wifi_socket.connect(lifecycle);
    cellular_socket.connect(lifecycle);

    expect(!wifi_socket.abrupt_drop(lifecycle, rtp),
           "stale Wi-Fi close tore down cellular replacement");
    expect(lifecycle.active_transport_token() == cellular_socket.token(),
           "replacement socket was not retained");
    expect(rtp.operations.empty(), "stale socket close touched RTP state");
  });

  runner.check("reconnected_socket_rearms_cleanup", [] {
    SecureTransportLifecycle lifecycle;
    MockSocketDriver wifi_socket(0x5000);
    MockSocketDriver cellular_socket(0x6000);
    MockRtpStreamDriver rtp;
    wifi_socket.connect(lifecycle);
    expect(wifi_socket.abrupt_drop(lifecycle, rtp), "Wi-Fi drop was ignored");

    rtp.synthesis_queued = true;
    rtp.media_attached = true;
    rtp.registered = true;
    cellular_socket.connect(lifecycle);
    expect(cellular_socket.abrupt_drop(lifecycle, rtp),
           "cellular drop after recovery was ignored");
    expect(rtp.flush_count == 2 && rtp.disconnect_count == 2 &&
               rtp.invalidation_count == 2,
           "reconnect did not rearm exactly-once cleanup");
  });

  try {
    write_evidence(output_path(argc, argv), runner);
  } catch (const std::exception &error) {
    std::cerr << error.what() << '\n';
    return 2;
  }

  if (!runner.all_passed()) {
    for (const auto &result : runner.results()) {
      if (!result.passed) {
        std::cerr << result.id << ": " << result.detail << '\n';
      }
    }
    return 1;
  }
  return 0;
}
