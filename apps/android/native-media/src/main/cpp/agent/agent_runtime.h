#ifndef TRIPLEX_NATIVE_AGENT_RUNTIME_H
#define TRIPLEX_NATIVE_AGENT_RUNTIME_H

#include <cstdint>
#include <string>
#include <variant>
#include "../audio/frame_pool.h"

namespace triplex {
namespace native {

struct AsrPartialResult {
    std::string text;
    bool is_final;
    int64_t timestamp;
    int64_t epoch;
    float confidence;
};

struct ReasoningResult {
    std::string response;
    std::string action;
    int64_t latency_ms;
    int64_t epoch;
};

struct TtsFrame {
    int16_t* data;
    size_t size;
    int64_t timestamp;
    int64_t epoch;
};

struct InterruptionEvent {
    std::string reason;
    int64_t epoch;
    int64_t timestamp;
};

struct Epoch {
    int64_t value;
    int64_t start_time;
    bool valid;
    
    static Epoch create() {
        return Epoch {
            .value = std::chrono::steady_clock::now().time_since_epoch().count(),
            .start_time = std::chrono::steady_clock::now().time_since_epoch().count(),
            .valid = true
        };
    }
    
    void invalidate() {
        valid = false;
    }
    
    bool isValid() const {
        return valid;
    }
};

class AgentState {
public:
    enum class State {
        IDLE,
        LISTENING,
        PROCESSING_ASR,
        PROCESSING_REASONING,
        SPEAKING,
        INTERRUPTED
    };
    
    AgentState() : state_(State::IDLE), epoch_(Epoch::create()) {}
    
    void transition(State new_state);
    void interrupt(const std::string& reason);
    void commitEpoch();
    
    bool canTransition(State new_state) const;
    State getCurrentState() const { return state_; }
    const Epoch& getCurrentEpoch() const { return epoch_; }
    
private:
    State state_;
    Epoch epoch_;
};

class AgentRuntime {
public:
    AgentRuntime();
    ~AgentRuntime();
    
    bool initialize();
    void shutdown();
    
    void processAudioFrame(Frame* frame);
    void injectAudioFrame(const int16_t* data, size_t size, int64_t epoch);
    
    void startUtterance();
    void endUtterance();
    
    void interrupt();
    void resume();
    
    AgentState::State getState() const { return state_.getCurrentState(); }
    const Epoch& getCurrentEpoch() const { return state_.getCurrentEpoch(); }
    
    int64_t getLatency() const;
    
private:
    AgentState state_;
    void* vad_ = nullptr;
    void* asr_ = nullptr;
    void* reasoning_ = nullptr;
    void* tts_ = nullptr;
    
    FramePool frame_pool_;
    
    bool initialized_ = false;
};

}
}

#endif
