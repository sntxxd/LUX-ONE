#pragma once
#include <stdint.h>

struct LdrSettings {
  uint16_t on = 1500, off = 1900, confirmMs = 5000;
  uint8_t darkIsLow = 1, version = 1;
  bool valid() const {
    return version == 1 && darkIsLow <= 1 && on <= 4095 && off <= 4095 &&
      confirmMs >= 500 && confirmMs <= 10000 &&
      (darkIsLow ? on + 50 <= off : off + 50 <= on);
  }
};
