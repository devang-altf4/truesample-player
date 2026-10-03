// Logging to logcat plus an optional file. Some phones (vivo/iQOO) drop all
// third-party logcat output, so the file is the only reliable trace there.
#pragma once

#include <android/log.h>

namespace ualog {

void setFile(const char* path);  // nullptr closes the file
void write(int priority, const char* format, ...) __attribute__((format(printf, 2, 3)));

}  // namespace ualog

#define LOGI(...) ualog::write(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) ualog::write(ANDROID_LOG_WARN, __VA_ARGS__)
#define LOGE(...) ualog::write(ANDROID_LOG_ERROR, __VA_ARGS__)
