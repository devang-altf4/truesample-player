#include "Log.h"

#include <cstdarg>
#include <cstdio>
#include <ctime>
#include <mutex>

namespace ualog {
namespace {

std::mutex gMutex;
FILE* gFile = nullptr;

}  // namespace

void setFile(const char* path) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gFile) std::fclose(gFile);
    gFile = path ? std::fopen(path, "a") : nullptr;
}

void write(int priority, const char* format, ...) {
    char message[1024];
    va_list args;
    va_start(args, format);
    std::vsnprintf(message, sizeof message, format, args);
    va_end(args);
    __android_log_write(priority, "UsbAudio", message);

    std::lock_guard<std::mutex> lock(gMutex);
    if (!gFile) return;
    timespec now{};
    clock_gettime(CLOCK_REALTIME, &now);
    tm local{};
    localtime_r(&now.tv_sec, &local);
    static const char kLevel[] = "??VDIWEF";
    std::fprintf(gFile, "%02d:%02d:%02d.%03ld %c %s\n", local.tm_hour, local.tm_min, local.tm_sec,
                 now.tv_nsec / 1000000, priority >= 0 && priority < 8 ? kLevel[priority] : '?', message);
    std::fflush(gFile);
}

}  // namespace ualog
