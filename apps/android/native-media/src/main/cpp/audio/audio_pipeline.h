#ifndef TRIPLEX_NATIVE_AUDIO_PIPELINE_H
#define TRIPLEX_NATIVE_AUDIO_PIPELINE_H

#include "frame_pool.h"
#include <atomic>
#include <functional>

namespace triplex {
namespace native {

class AudioCaptureCallback {
public:
    virtual ~AudioCaptureCallback() = default;
    virtual void onFrame(Frame* frame) = 0;
    virtual void onError(const char* message) = 0;
};

class AudioPlaybackCallback {
public:
    virtual ~AudioPlaybackCallback() = default;
    virtual Frame* onRequestFrame(int64_t timestamp, int64_t epoch) = 0;
    virtual void onError(const char* message) = 0;
};

struct AudioConfig {
    int sample_rate = 16000;
    int channels = 1;
    int frame_duration_ms = 10;
    int samples_per_frame = 160;
    int frame_size_bytes = 320;
    bool echo_cancellation = true;
    bool noise_suppression = true;
};

class NativeAudioPipeline {
public:
    NativeAudioPipeline(const AudioConfig& config);
    ~NativeAudioPipeline();
    
    bool start(int64_t epoch);
    void stop();
    
    void setCaptureCallback(AudioCaptureCallback* callback) { capture_callback_ = callback; }
    void setPlaybackCallback(AudioPlaybackCallback* callback) { playback_callback_ = callback; }
    
    bool injectGenerated(const int16_t* data, size_t size, int64_t timestamp, int64_t epoch);
    
    int64_t getDroppedFrames() const { return dropped_frames_.load(); }
    int64_t getCurrentEpoch() const { return epoch_.load(); }
    
    bool isRunning() const { return state_.load() == State::RUNNING; }
    
private:
    void captureThread();
    void playbackThread();
    
    AudioConfig config_;
    FramePool frame_pool_;
    
    std::atomic<State> state_;
    std::atomic<int64_t> epoch_;
    std::atomic<int64_t> dropped_frames_;
    
    AudioCaptureCallback* capture_callback_ = nullptr;
    AudioPlaybackCallback* playback_callback_ = nullptr;
    
    enum class State {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    };
    
    void* audio_record_ = nullptr;
    void* audio_track_ = nullptr;
};

}
}

#endif
