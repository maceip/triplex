#ifndef TRIPLEX_NATIVE_SIP_TRANSPORT_H
#define TRIPLEX_NATIVE_SIP_TRANSPORT_H

#include <cstdint>
#include <functional>
#include <string>

namespace triplex {
namespace native {

struct SipEndpoint {
    std::string sip_uri;
    std::string auth_user;
    std::string auth_pass;
    std::string realm;
};

struct CallInfo {
    std::string call_id;
    std::string destination;
    int64_t epoch;
    int64_t start_time;
};

enum class RegistrationState {
    UNREGISTERED,
    REGISTERING,
    REGISTERED,
    UNREGISTERING,
    FAILED
};

enum class CallState {
    IDLE,
    CALLING,
    RINGING,
    CONFIRMED,
    DISCONNECTED
};

class SipStateCallback {
public:
    virtual ~SipStateCallback() = default;
    virtual void onRegistrationStateChanged(RegistrationState state) = 0;
    virtual void onCallStateChanged(CallState state, const CallInfo& info) = 0;
    virtual void onIncomingCall(const CallInfo& info) = 0;
};

class NativeSipTransport {
public:
    NativeSipTransport();
    ~NativeSipTransport();
    
    bool initialize();
    bool registerEndpoint(const SipEndpoint& endpoint);
    void unregisterEndpoint();
    
    bool makeCall(const std::string& destination, int64_t epoch, const std::string& call_id);
    void hangup();
    
    void setStateCallback(SipStateCallback* callback) { state_callback_ = callback; }
    
    RegistrationState getRegistrationState() const { return registration_state_; }
    CallState getCallState() const { return call_state_; }
    
private:
    RegistrationState registration_state_ = RegistrationState::UNREGISTERED;
    CallState call_state_ = CallState::IDLE;
    SipStateCallback* state_callback_ = nullptr;
    
    void* pjsip_endpoint_ = nullptr;
    void* pjsip_account_ = nullptr;
    void* pjsip_call_ = nullptr;
    
    bool initializePjsip();
    void cleanupPjsip();
};

}
}

#endif
