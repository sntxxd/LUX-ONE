#include "../BCM_Principal/SwitchOff.h"
#include <cassert>
#include <iostream>
int main() {
  struct Manual {
    int headlight = 2, parking = 1, rearParking = 1, licenseLight = 1;
    int whiteRings = -1, whiteLeft = -1, whiteRight = -1;
    int orangeRings = 1, orangeLeft = 0, orangeRight = 1;
  } manual;
  cancelExteriorCommands(manual, true, false);
  assert(manual.headlight == 0 && manual.parking == 0);
  assert(manual.rearParking == -1 && manual.licenseLight == -1);
  assert(manual.whiteLeft == 1 && manual.whiteRight == 0);
  assert(manual.orangeRings == 1 && manual.orangeLeft == 0 && manual.orangeRight == 1);
  manual.whiteRings = 0; manual.whiteLeft = -1; manual.whiteRight = 1;
  cancelExteriorCommands(manual, true, true);
  assert(manual.whiteRings == 0 && manual.whiteLeft == -1 && manual.whiteRight == 1);
  SwitchOff edge;
  assert(!edge.update(false, 0));
  assert(!edge.update(false, 500)); // boot with switch off is not an event
  assert(!edge.update(true, 600));
  assert(!edge.update(false, 700));
  assert(edge.pending());
  assert(!edge.update(true, 800)); // high/low contact gap
  assert(!edge.pending());
  assert(!edge.update(false, 900));
  assert(!edge.update(false, 1049));
  assert(edge.update(false, 1050));
  assert(!edge.pending());
  assert(!edge.update(false, 2000)); // one event only
  assert(!edge.update(true, 0xffffff00u));
  assert(!edge.update(false, 0xfffffff0u));
  assert(!edge.update(false, 0x85u));
  assert(edge.update(false, 0x86u)); // millis wrap
  std::cout << "switch_off: OK\n";
}
