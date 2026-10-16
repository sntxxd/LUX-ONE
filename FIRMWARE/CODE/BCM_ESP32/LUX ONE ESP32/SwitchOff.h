#pragma once
#include <stdint.h>

template<class Manual>
void cancelExteriorCommands(Manual &manual, bool whiteLeft, bool whiteRight) {
  if (manual.whiteRings < 0) {
    if (manual.whiteLeft < 0) manual.whiteLeft = whiteLeft;
    if (manual.whiteRight < 0) manual.whiteRight = whiteRight;
  }
  manual.headlight = 0;
  manual.parking = 0;
  manual.rearParking = manual.licenseLight = -1;
}

// Only emit after a real ON -> all-OFF transition. Ignore brief contact gaps.
class SwitchOff {
  bool armed = false, timing = false;
  uint32_t since = 0;
public:
  bool pending() const { return armed && timing; }
  bool update(bool active, uint32_t now) {
    if (active) { armed = true; timing = false; return false; }
    if (!armed) return false;
    if (!timing) { timing = true; since = now; }
    if (uint32_t(now - since) < 150) return false;
    armed = timing = false;
    return true;
  }
};
