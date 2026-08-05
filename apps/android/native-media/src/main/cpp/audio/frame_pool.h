#ifndef TRIPLEX_NATIVE_FRAME_POOL_H
#define TRIPLEX_NATIVE_FRAME_POOL_H

#include <cstdint>
#include <atomic>
#include <vector>
#include <mutex>
#include <condition_variable>

namespace triplex {
namespace native {

struct Frame {
    int16_t* data;
    size_t size;
    int64_t timestamp;
    int64_t epoch;
    uint32_t flags;
    
    static constexpr uint32_t FLAG_GENERATED = 0x01;
    static constexpr uint32_t FLAG_INTERRUPTED = 0x02;
    static constexpr uint32_t FLAG_PARTIAL = 0x04;
    
    void reset() {
        if (data && size > 0) {
            memset(data, 0, size * sizeof(int16_t));
        }
        timestamp = 0;
        epoch = 0;
        flags = 0;
    }
};

class FramePool {
public:
    FramePool(size_t pool_size, size_t frame_size_bytes);
    ~FramePool();
    
    Frame* acquire();
    void release(Frame* frame);
    
    void clear();
    
private:
    std::vector<Frame> frames_;
    std::vector<std::atomic<bool>> available_;
    size_t pool_size_;
    size_t frame_size_bytes_;
    std::mutex mutex_;
};

class FrameCirculator {
public:
    FrameCirculator(FramePool& pool, int64_t epoch)
        : pool_(pool), epoch_(epoch), current_frame_(nullptr), position_(0) {}
    
    ~FrameCirculator() {
        if (current_frame_) {
            pool_.release(current_frame_);
        }
    }
    
    bool write(const int16_t* data, size_t samples);
    Frame* complete();
    
    void discard() {
        if (current_frame_) {
            pool_.release(current_frame_);
            current_frame_ = nullptr;
        }
        position_ = 0;
    }
    
private:
    FramePool& pool_;
    int64_t epoch_;
    Frame* current_frame_;
    size_t position_;
};

}
}

#endif
