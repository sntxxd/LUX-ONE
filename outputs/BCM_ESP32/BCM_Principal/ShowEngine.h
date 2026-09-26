#pragma once
#include <stdint.h>

// Bits iguales a STATE: bajas, altas, cuartos, blanco I/D, naranja I/D.
struct ShowPattern {
  uint16_t masks[16] = {};
  uint16_t durations[16] = {};
  uint8_t repeat = 1;
  uint8_t version = 1;
  ShowPattern() { for (auto &duration : durations) duration = 500; }
  bool valid() const {
    if (version != 1 || repeat > 1) return false;
    for (int i = 0; i < 16; ++i)
      if (masks[i] > 127 || (masks[i] & 3) == 3 || durations[i] < 200 || durations[i] > 5000) return false;
    return true;
  }
};

struct ShowEngine {
  bool running = false;
  uint32_t started = 0;
  uint8_t step = 0;
  void stop() { running = false; step = 0; }
  void start(uint32_t now) { started = now; step = 0; running = true; }
  uint16_t tick(uint32_t now, const ShowPattern &pattern) {
    if (!running) return 0;
    uint32_t total = 0;
    for (auto duration : pattern.durations) total += duration;
    if (!pattern.valid() || total == 0) { stop(); return 0; }
    uint32_t elapsed = now - started;
    if (!pattern.repeat && elapsed >= total) { stop(); return 0; }
    elapsed %= total;
    for (step = 0; step < 16; ++step) {
      if (elapsed < pattern.durations[step]) return pattern.masks[step];
      elapsed -= pattern.durations[step];
    }
    stop(); return 0;
  }
};

struct ChirpEngine {
  uint32_t started = 0;
  uint16_t onMs = 220, gapMs = 200;
  uint8_t count = 0;
  void start(uint32_t now, uint8_t pulses, uint16_t duration, uint16_t gap) {
    started = now; count = pulses; onMs = duration; gapMs = gap;
  }
  bool tick(uint32_t now) {
    if (!count) return false;
    const uint32_t cycle = onMs + gapMs;
    const uint32_t elapsed = now - started;
    if (elapsed >= cycle * (count - 1) + onMs) { count = 0; return false; }
    return elapsed % cycle < onMs;
  }
};
