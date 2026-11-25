// Native isochronous USB Audio Class streaming engine — the transport behind every
// "USB Direct" driver profile.
//
// Android's Java USB API (UsbRequest / bulkTransfer) cannot submit isochronous transfers, which
// is what every USB audio DAC streams on. This engine talks to the kernel's usbfs directly
// (USBDEVFS_SUBMITURB with USBDEVFS_URB_TYPE_ISO on the file descriptor Android already opened
// for us) — the same layer libusb, UAPP-style players and the kernel's own snd-usb-audio
// semantics live at. The timing / feedback / ring logic is in uac_stream_core.h (unit-tested on
// a desktop toolchain); this file is the usbfs and JNI plumbing.
//
// Before this runs, Kotlin has: opened the device, claimed the streaming interface (detaching
// the kernel audio driver), selected the alternate setting, and set the sampling frequency.
//
// Threading:
//   - JNI nativeWrite (Kotlin pump thread) is the single producer into the FrameRing.
//   - One transfer thread owns every URB: submits, reaps (poll + REAPURBNDELAY), refills.
//   - Stats are atomics read by nativeGetStats from any thread.

#include <jni.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>

#include "uac_stream_core.h"

#define LOG_TAG "BitPerfectUsbNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef USBDEVFS_GET_SPEED
#define USBDEVFS_GET_SPEED _IO('U', 31) // Linux 4.18+
#endif

namespace {

constexpr int kFeedbackUrbs = 2;
constexpr int kPollTimeoutMs = 20;

enum class UrbKind : uint8_t { Data, Feedback };

// usbdevfs_urb ends in a flexible array of iso packet descriptors that the kernel expects to
// find *contiguously after* the struct, so each URB lives in a raw buffer sized for its packets.
struct Urb {
    std::vector<uint8_t> storage;
    std::vector<uint8_t> data;
    UrbKind kind = UrbKind::Data;
    int packets = 0;
    bool inFlight = false;

    usbdevfs_urb* urb() { return reinterpret_cast<usbdevfs_urb*>(storage.data()); }

    void allocate(int nPackets, size_t dataBytes, UrbKind k) {
        kind = k;
        packets = nPackets;
        storage.assign(sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc) * static_cast<size_t>(nPackets), 0);
        data.assign(dataBytes, 0);
    }
};

struct Engine {
    int fd = -1;
    uint8_t dataEp = 0;
    uint8_t feedbackEp = 0; // 0 = none
    int feedbackPacketBytes = 4;
    int frameBytes = 0;
    int sampleRate = 0;
    bool useFeedback = true;

    uac::EndpointTiming timing{};
    uac::UrbPlan plan{};
    std::unique_ptr<uac::PacketSizer> sizer;
    std::unique_ptr<uac::FeedbackDecoder> feedback;
    std::unique_ptr<uac::FrameRing> ring;
    std::vector<std::unique_ptr<Urb>> urbs;

    std::atomic<bool> running{false};
    std::atomic<bool> endOfStream{false};
    std::atomic<bool> deviceGone{false};
    std::thread thread;

    // Stats
    std::atomic<int64_t> bytesSent{0};
    std::atomic<int64_t> framesSent{0};
    std::atomic<int64_t> underruns{0};
    std::atomic<int64_t> ioErrors{0};
    std::atomic<int64_t> packetErrors{0};
    std::atomic<int64_t> rateMilliHz{0};
    std::atomic<int64_t> feedbackAccepted{0};

    ~Engine() {
        if (thread.joinable()) thread.join();
    }
};

void raiseThreadPriority() {
    // ANDROID_PRIORITY_URGENT_AUDIO (-19), falling back to ANDROID_PRIORITY_AUDIO (-16).
    if (setpriority(PRIO_PROCESS, 0, -19) != 0 && setpriority(PRIO_PROCESS, 0, -16) != 0) {
        LOGW("could not raise transfer thread priority: %s", strerror(errno));
    }
}

// Fills a data URB's packets from the ring (silence where the ring is short) and submits it.
bool submitData(Engine* e, Urb* u, size_t index) {
    usbdevfs_urb* urb = u->urb();
    std::memset(urb, 0, u->storage.size());
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = e->dataEp;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->number_of_packets = u->packets;
    urb->usercontext = reinterpret_cast<void*>(index);
    urb->buffer = u->data.data();

    uint8_t* out = u->data.data();
    size_t total = 0;
    bool shortRead = false;
    for (int p = 0; p < u->packets; p++) {
        int frames = e->sizer->nextFrames();
        size_t bytes = static_cast<size_t>(frames) * static_cast<size_t>(e->frameBytes);
        size_t got = e->ring->readFrames(out + total, static_cast<size_t>(frames));
        if (got < static_cast<size_t>(frames)) {
            size_t gotBytes = got * static_cast<size_t>(e->frameBytes);
            std::memset(out + total + gotBytes, 0, bytes - gotBytes);
            shortRead = true;
        }
        urb->iso_frame_desc[p].length = static_cast<unsigned int>(bytes);
        total += bytes;
        e->framesSent.fetch_add(got, std::memory_order_relaxed);
    }
    urb->buffer_length = static_cast<int>(total);
    if (shortRead && !e->endOfStream.load(std::memory_order_relaxed)) {
        e->underruns.fetch_add(1, std::memory_order_relaxed);
    }

    if (ioctl(e->fd, USBDEVFS_SUBMITURB, urb) < 0) {
        int err = errno;
        if (err == ENODEV || err == ESHUTDOWN) e->deviceGone.store(true);
        LOGE("data SUBMITURB failed: %s", strerror(err));
        e->ioErrors.fetch_add(1);
        u->inFlight = false;
        return false;
    }
    u->inFlight = true;
    return true;
}

bool submitFeedback(Engine* e, Urb* u, size_t index) {
    usbdevfs_urb* urb = u->urb();
    std::memset(urb, 0, u->storage.size());
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = e->feedbackEp;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->number_of_packets = 1;
    urb->usercontext = reinterpret_cast<void*>(index);
    urb->buffer = u->data.data();
    urb->buffer_length = e->feedbackPacketBytes;
    urb->iso_frame_desc[0].length = static_cast<unsigned int>(e->feedbackPacketBytes);
    if (ioctl(e->fd, USBDEVFS_SUBMITURB, urb) < 0) {
        LOGW("feedback SUBMITURB failed (%s) — continuing at the nominal rate", strerror(errno));
        u->inFlight = false;
        return false;
    }
    u->inFlight = true;
    return true;
}

void handleFeedback(Engine* e, usbdevfs_urb* urb) {
    if (urb->status != 0 || urb->iso_frame_desc[0].status != 0) return;
    int len = static_cast<int>(urb->iso_frame_desc[0].actual_length);
    uint32_t f = e->feedback->decode(static_cast<const uint8_t*>(urb->buffer), len);
    if (f != 0 && e->useFeedback) {
        e->sizer->setFeedback(f);
        e->feedbackAccepted.fetch_add(1, std::memory_order_relaxed);
    }
    e->rateMilliHz.store(e->sizer->effectiveRateMilliHz(), std::memory_order_relaxed);
}

int inFlightCount(Engine* e) {
    int n = 0;
    for (auto& u : e->urbs) if (u->inFlight) n++;
    return n;
}

// Reaps every completed URB currently available. Returns false if the device disappeared.
bool reapAvailable(Engine* e, bool resubmit) {
    while (true) {
        usbdevfs_urb* done = nullptr;
        if (ioctl(e->fd, USBDEVFS_REAPURBNDELAY, &done) < 0) {
            int err = errno;
            if (err == EAGAIN) return true;
            if (err == ENODEV || err == ESHUTDOWN) {
                e->deviceGone.store(true);
                LOGE("DAC disconnected");
                return false;
            }
            LOGE("REAPURBNDELAY failed: %s", strerror(err));
            e->ioErrors.fetch_add(1);
            return true;
        }
        if (done == nullptr) continue;
        size_t index = reinterpret_cast<size_t>(done->usercontext);
        if (index >= e->urbs.size()) {
            LOGE("reaped URB with unknown index %zu", index);
            continue;
        }
        Urb* u = e->urbs[index].get();
        u->inFlight = false;

        if (u->kind == UrbKind::Feedback) {
            handleFeedback(e, done);
            if (resubmit) submitFeedback(e, u, index);
            continue;
        }

        if (done->status != 0 && done->status != -ENOENT && done->status != -ECONNRESET) {
            e->ioErrors.fetch_add(1, std::memory_order_relaxed);
        }
        for (int p = 0; p < done->number_of_packets; p++) {
            if (done->iso_frame_desc[p].status != 0) e->packetErrors.fetch_add(1, std::memory_order_relaxed);
            e->bytesSent.fetch_add(done->iso_frame_desc[p].actual_length, std::memory_order_relaxed);
        }
        if (resubmit) submitData(e, u, index);
    }
}

void transferLoop(Engine* e) {
    raiseThreadPriority();

    // Prefill: wait (bounded) until the ring holds the whole URB queue's worth of audio, so the
    // stream doesn't open with a burst of silence padding.
    const size_t want = static_cast<size_t>(e->plan.urbCount) * static_cast<size_t>(e->plan.packetsPerUrb) *
                        static_cast<size_t>(e->sizer->maxFrames()) * static_cast<size_t>(e->frameBytes) / 2;
    for (int waited = 0; waited < 500 && e->running.load() && e->ring->availableBytes() < std::min(want, e->ring->capacity() / 2); waited += 2) {
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }

    for (size_t i = 0; i < e->urbs.size(); i++) {
        Urb* u = e->urbs[i].get();
        if (u->kind == UrbKind::Data) submitData(e, u, i);
        else submitFeedback(e, u, i);
    }
    LOGI("streaming: %d pkts/s, %d pkts/URB, %d URBs (~%d ms), feedback ep=0x%02x",
         e->timing.packetsPerSecond, e->plan.packetsPerUrb, e->plan.urbCount,
         e->plan.urbCount * e->plan.packetsPerUrb * 1000 / e->timing.packetsPerSecond, e->feedbackEp);

    pollfd pfd{e->fd, POLLOUT | POLLWRNORM, 0};
    while (e->running.load(std::memory_order_relaxed)) {
        int pr = poll(&pfd, 1, kPollTimeoutMs);
        if (pr < 0 && errno != EINTR) {
            LOGE("poll failed: %s", strerror(errno));
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        if (pfd.revents & (POLLERR | POLLHUP)) {
            e->deviceGone.store(true);
            LOGE("usbfs reported POLLERR/POLLHUP — DAC disconnected");
            break;
        }
        if (!reapAvailable(e, /*resubmit=*/true)) break;
        if (inFlightCount(e) == 0) {
            LOGE("no transfers in flight — stopping");
            break;
        }
    }

    // Shutdown: cancel everything still queued, then reap until the kernel has returned every
    // URB, so it never writes into memory we are about to free.
    for (auto& u : e->urbs) {
        if (u->inFlight) ioctl(e->fd, USBDEVFS_DISCARDURB, u->urb());
    }
    for (int i = 0; i < 100 && inFlightCount(e) > 0 && !e->deviceGone.load(); i++) {
        poll(&pfd, 1, 10);
        reapAvailable(e, /*resubmit=*/false);
    }
    LOGI("stopped: sent=%lld bytes, underruns=%lld, ioErrors=%lld, packetErrors=%lld, feedback=%lld",
         static_cast<long long>(e->bytesSent.load()), static_cast<long long>(e->underruns.load()),
         static_cast<long long>(e->ioErrors.load()), static_cast<long long>(e->packetErrors.load()),
         static_cast<long long>(e->feedbackAccepted.load()));
}

uac::BusSpeed querySpeed(int fd) {
    int s = ioctl(fd, USBDEVFS_GET_SPEED);
    if (s < 0) return uac::BusSpeed::Unknown;
    return static_cast<uac::BusSpeed>(s);
}

} // namespace

extern "C" {

// Returns an engine handle, or 0 on invalid arguments.
JNIEXPORT jlong JNICALL
Java_com_windstrom5_rebit_usb_UsbNativeIsochronousStreamer_nativeOpen(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jint fd, jint dataEndpoint, jint dataMaxPacketSize, jint dataInterval,
        jint feedbackEndpoint, jint feedbackMaxPacketSize,
        jint sampleRateHz, jint frameSizeBytes, jboolean isUac2,
        jint targetLatencyMs, jboolean useFeedback) {
    if (fd < 0 || dataMaxPacketSize <= 0 || sampleRateHz <= 0 || frameSizeBytes <= 0) {
        LOGE("nativeOpen: invalid arguments fd=%d mps=%d rate=%d frame=%d", fd, dataMaxPacketSize, sampleRateHz, frameSizeBytes);
        return 0;
    }
    auto* e = new Engine();
    e->fd = fd;
    e->dataEp = static_cast<uint8_t>(dataEndpoint);
    e->feedbackEp = static_cast<uint8_t>(feedbackEndpoint > 0 ? feedbackEndpoint : 0);
    e->feedbackPacketBytes = std::clamp(static_cast<int>(feedbackMaxPacketSize & 0x7FF), 3, 4);
    e->frameBytes = frameSizeBytes;
    e->sampleRate = sampleRateHz;
    e->useFeedback = useFeedback == JNI_TRUE;

    const uac::BusSpeed speed = querySpeed(fd);
    e->timing = uac::computeTiming(speed, dataInterval, dataMaxPacketSize, isUac2 == JNI_TRUE);
    e->plan = uac::planUrbs(e->timing, std::clamp(static_cast<int>(targetLatencyMs), 4, 250));
    e->sizer = std::make_unique<uac::PacketSizer>(sampleRateHz, e->timing, frameSizeBytes);
    e->feedback = std::make_unique<uac::FeedbackDecoder>(
        uac::nominalPerBusIntervalQ16(sampleRateHz, e->timing.busIntervalsPerSecond));
    e->rateMilliHz.store(static_cast<int64_t>(sampleRateHz) * 1000);

    // Ring: the larger of 150 ms or twice the URB queue, so the Kotlin side has slack for GC.
    const size_t bytesPerSecond = static_cast<size_t>(sampleRateHz) * static_cast<size_t>(frameSizeBytes);
    const size_t ringBytes = std::max(bytesPerSecond * 150 / 1000,
                                      bytesPerSecond * static_cast<size_t>(std::max(1, static_cast<int>(targetLatencyMs))) * 2 / 1000);
    e->ring = std::make_unique<uac::FrameRing>(ringBytes, frameSizeBytes);

    const size_t dataBytes = static_cast<size_t>(e->plan.packetsPerUrb) * static_cast<size_t>(e->timing.maxPacketBytes);
    for (int i = 0; i < e->plan.urbCount; i++) {
        auto u = std::make_unique<Urb>();
        u->allocate(e->plan.packetsPerUrb, dataBytes, UrbKind::Data);
        e->urbs.push_back(std::move(u));
    }
    if (e->feedbackEp != 0) {
        for (int i = 0; i < kFeedbackUrbs; i++) {
            auto u = std::make_unique<Urb>();
            u->allocate(1, 8, UrbKind::Feedback);
            e->urbs.push_back(std::move(u));
        }
    }

    LOGI("nativeOpen: speed=%d bInterval=%d mps=%d → %d pkts/s, max %d B/pkt; rate=%d frame=%dB; "
         "latency=%dms; feedback ep=0x%02x (%s)",
         static_cast<int>(speed), dataInterval, dataMaxPacketSize, e->timing.packetsPerSecond,
         e->timing.maxPacketBytes, sampleRateHz, frameSizeBytes, targetLatencyMs, e->feedbackEp,
         e->useFeedback ? "adaptive" : "ignored");
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jboolean JNICALL
Java_com_windstrom5_rebit_usb_UsbNativeIsochronousStreamer_nativeStart(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (e == nullptr) return JNI_FALSE;
    if (e->running.exchange(true)) return JNI_TRUE;
    e->thread = std::thread(transferLoop, e);
    return JNI_TRUE;
}

// Blocks until all of [length] bytes are queued, the timeout passes, or the stream stops.
// Returns the number of bytes queued.
JNIEXPORT jint JNICALL
Java_com_windstrom5_rebit_usb_UsbNativeIsochronousStreamer_nativeWrite(
        JNIEnv* env, jobject, jlong handle, jbyteArray data, jint offset, jint length, jint timeoutMs) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (e == nullptr || length <= 0) return 0;
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return 0;
    const auto* src = reinterpret_cast<const uint8_t*>(bytes) + offset;
    size_t done = 0;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (done < static_cast<size_t>(length) && e->running.load(std::memory_order_relaxed) && !e->deviceGone.load()) {
        done += e->ring->write(src + done, static_cast<size_t>(length) - done);
        if (done < static_cast<size_t>(length)) {
            if (std::chrono::steady_clock::now() >= deadline) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return static_cast<jint>(done);
}

// End of input: underruns are no longer counted, and the ring is allowed to run dry.
JNIEXPORT void JNICALL
Java_com_windstrom5_rebit_usb_UsbNativeIsochronousStreamer_nativeEndOfStream(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (e != nullptr) e->endOfStream.store(true);
}

// [bytesSent, underruns, ioErrors, packetErrors, rateMilliHz, bufferedBytes, deviceGone(0/1),
//  framesSent, feedbackAccepted, packetsPerSecond, urbLatencyUs]
JNIEXPORT void JNICALL
Java_com_windstrom5_rebit_usb_UsbNativeIsochronousStreamer_nativeGetStats(
        JNIEnv* env, jobject, jlong handle, jlongArray out) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (e == nullptr) return;
    jlong s[11] = {
        e->bytesSent.load(), e->underruns.load(), e->ioErrors.load(), e->packetErrors.load(),
        e->rateMilliHz.load(), static_cast<jlong>(e->ring->availableBytes()), e->deviceGone.load() ? 1 : 0,
        e->framesSent.load(), e->feedbackAccepted.load(), e->timing.packetsPerSecond,
        static_cast<jlong>(1000000LL * e->plan.urbCount * e->plan.packetsPerUrb / e->timing.packetsPerSecond)
    };
    env->SetLongArrayRegion(out, 0, 11, s);
}

JNIEXPORT void JNICALL
Java_com_windstrom5_rebit_usb_UsbNativeIsochronousStreamer_nativeStop(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (e == nullptr) return;
    e->running.store(false);
    if (e->thread.joinable()) e->thread.join();
}

JNIEXPORT void JNICALL
Java_com_windstrom5_rebit_usb_UsbNativeIsochronousStreamer_nativeClose(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (e == nullptr) return;
    e->running.store(false);
    if (e->thread.joinable()) e->thread.join();
    delete e;
}

} // extern "C"
