#include <jni.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <string>
#include <vector>
#include <array>
#include <algorithm>
#include <cctype>

namespace {

struct Signal {
    std::string id;
    std::string category;
    std::string weight;
    std::string title;
    std::string detail;
};

int rawOpen(const char* path) {
    return static_cast<int>(syscall(__NR_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0));
}

std::string rawRead(const char* path, size_t limit = 1024 * 1024, bool* complete = nullptr) {
    if (complete) *complete = false;
    int fd = rawOpen(path);
    if (fd < 0) return {};
    std::string out;
    std::array<char, 4096> buffer{};
    while (out.size() < limit) {
        auto count = static_cast<ssize_t>(syscall(__NR_read, fd, buffer.data(),
                                                std::min(buffer.size(), limit - out.size())));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            if (complete) *complete = count == 0;
            break;
        }
        out.append(buffer.data(), static_cast<size_t>(count));
    }
    syscall(__NR_close, fd);
    return out;
}

bool rawExists(const char* path, bool& complete) {
    int fd = rawOpen(path);
    if (fd < 0) {
        if (errno != ENOENT && errno != ENOTDIR) complete = false;
        return false;
    }
    syscall(__NR_close, fd);
    return true;
}

std::string property(const char* key) {
    std::array<char, PROP_VALUE_MAX> value{};
    int length = __system_property_get(key, value.data());
    return length > 0 ? std::string(value.data(), static_cast<size_t>(length)) : std::string{};
}

bool containsAny(const std::string& value, const std::vector<std::string>& needles) {
    std::string lower = value;
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return std::tolower(c); });

    return std::any_of(needles.begin(), needles.end(), [&](const auto& needle) {
        return lower.find(needle) != std::string::npos;
    });
}

void add(std::vector<Signal>& out, const char* id, const char* category, int weight,
         const char* title, const std::string& detail) {
    out.push_back({id, category, std::to_string(weight), title, detail});
}

void checked(std::vector<Signal>& out, const char* id, bool complete) {
    if (complete) add(out, id, "CHECK", 0, "", "");
}

std::vector<Signal> collect() {
    std::vector<Signal> out;

    // Root Detection
    const std::array<const char*, 10> rootPaths = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/data/adb/magisk", "/data/adb/ksu", "/debug_ramdisk/.magisk",
        "/system/app/Superuser.apk", "/system/bin/.ext/.su", "/cache/su"
    };
    bool rootComplete = true;
    for (const char* path : rootPaths) {
        if (rawExists(path, rootComplete)) {
            rootComplete = true;
            add(out, "root_artifact", "ROOT", 40, "Privileged artifact", path);
            break;
        }
    }

    checked(out, "root_artifact", rootComplete);

    // Mount Checks
    bool mountsComplete;
    const std::string mounts = rawRead("/proc/self/mountinfo", 1024 * 1024, &mountsComplete);
    checked(out, "suspicious_mount", mountsComplete);
    if (containsAny(mounts, {"magisk", "kernelsu", "apatch", "overlay"}))
        add(out, "suspicious_mount", "ROOT", 35, "Suspicious mount namespace", "Magisk, KernelSU, APatch, or overlay marker exposed");

    // Memory Maps Checks
    bool mapsComplete;
    const std::string maps = rawRead("/proc/self/maps", 1024 * 1024, &mapsComplete);
    checked(out, "injected_library", mapsComplete);
    checked(out, "anonymous_rwx", mapsComplete);

    if (containsAny(maps, {"frida", "xposed", "lsposed", "zygisk", "substrate"}))
        add(out, "injected_library", "HOOKING", 50, "Injected framework", "A known instrumentation or hooking marker is mapped");

    bool anonymousExecutable = false;
    size_t start = 0;
    while (start < maps.size()) {
        size_t end = maps.find('\n', start);
        std::string line = maps.substr(start, end - start);
        if (line.find("rwxp") != std::string::npos && line.find('/') == std::string::npos) {
            anonymousExecutable = true;
            break;
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    if (anonymousExecutable)
        add(out, "anonymous_rwx", "HOOKING", 25, "Writable executable memory", "An anonymous RWX mapping is present");

    // Tracer Detection
    bool statusComplete;
    const std::string status = rawRead("/proc/self/status", 1024 * 1024, &statusComplete);
    auto tracer = status.find("TracerPid:");
    checked(out, "tracer", statusComplete && tracer != std::string::npos);
    if (tracer != std::string::npos) {
        auto value = status.find_first_of("123456789", tracer + 10);
        if (value != std::string::npos && value < status.find('\n', tracer))
            add(out, "tracer", "HOOKING", 30, "Process is traced", "TracerPid is non-zero");
    }

    // Emulator Detection
    const std::array<const char*, 7> emulatorPaths = {
        "/dev/qemu_pipe", "/dev/socket/qemud", "/dev/goldfish_pipe",
        "/sys/qemu_trace", "/system/lib/libc_malloc_debug_qemu.so", "/dev/ttyS0", "/dev/ttyS1"
    };
    int virtualArtifacts = 0;
    bool virtualComplete = true;
    for (const char* path : emulatorPaths) if (rawExists(path, virtualComplete)) ++virtualArtifacts;
    checked(out, "virtual_device", virtualComplete);
    if (virtualArtifacts > 0)
        add(out, "virtual_device", "EMULATOR", std::min(45, 20 + virtualArtifacts * 5), "Virtual hardware artifacts", std::to_string(virtualArtifacts) + " emulator device nodes found");

    bool cmdlineComplete;
    const std::string cmdline = rawRead("/proc/cmdline", 1024 * 1024, &cmdlineComplete);
    checked(out, "virtual_kernel", cmdlineComplete);
    if (containsAny(cmdline, {"qemu=1", "goldfish", "ranchu", "androidboot.hardware=virtual"}))
        add(out, "virtual_kernel", "EMULATOR", 40, "Virtual kernel parameters", "Kernel command line exposes emulator markers");

    const auto hw = property("ro.hardware");
    const auto bootHw = property("ro.boot.hardware");
    const auto board = property("ro.product.board");
    const std::string hardware = hw + " " + bootHw + " " + board;
    checked(out, "virtual_properties", !hw.empty() && !bootHw.empty() && !board.empty());
    if (containsAny(hardware, {"goldfish", "ranchu", "vbox", "nox", "ttvm", "qemu"}))
        add(out, "virtual_properties", "EMULATOR", 35, "Virtual hardware properties", hardware);

    // ROM & Bootloader Checks
    const std::string flashLocked = property("ro.boot.flash.locked");
    checked(out, "bootloader_unlocked", flashLocked == "0" || flashLocked == "1");
    if (flashLocked == "0") add(out, "bootloader_unlocked", "ROM", 35, "Bootloader unlocked", "ro.boot.flash.locked=0");

    const std::string verified = property("ro.boot.verifiedbootstate");
    checked(out, "verified_boot", verified == "green" || verified == "orange" || verified == "red" || verified == "yellow");
    if (verified == "orange" || verified == "red" || verified == "yellow")
        add(out, "verified_boot", "ROM", verified == "red" ? 50 : 35, "Verified Boot degraded", "verified boot state=" + verified);

    const std::string tags = property("ro.build.tags");
    checked(out, "test_keys", !tags.empty());
    if (tags.find("test-keys") != std::string::npos)
        add(out, "test_keys", "ROM", 25, "Build uses test keys", tags);

    const std::string buildType = property("ro.build.type");
    checked(out, "debug_build", buildType == "user" || buildType == "userdebug" || buildType == "eng");
    if (buildType == "eng" || buildType == "userdebug")
        add(out, "debug_build", "ROM", 20, "Non-production system build", "ro.build.type=" + buildType);

    const std::string selinux = rawRead("/sys/fs/selinux/enforce", 8);
    checked(out, "selinux_permissive", !selinux.empty() && (selinux[0] == '0' || selinux[0] == '1'));
    if (!selinux.empty() && selinux[0] == '0')
        add(out, "selinux_permissive", "ROM", 45, "SELinux is permissive", "Kernel is not enforcing mandatory access control");

    return out;
}

} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_xheghun_devicetrust_internal_NativeSignalSource_collectEncoded(JNIEnv* env, jobject) {
    auto signals = collect();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(signals.size()), stringClass, nullptr);

    for (size_t i = 0; i < signals.size(); ++i) {
        const auto& s = signals[i];
        std::string encoded = s.id + "\t" + s.category + "\t" + s.weight + "\t" + s.title + "\t" + s.detail;
        jstring value = env->NewStringUTF(encoded.c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }

    return result;
}
