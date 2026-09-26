#include "../BCM_Principal/ShowEngine.h"
#include "../BCM_Principal/LdrSettings.h"
#include <cassert>
#include <iostream>

int main() {
  LdrSettings ldr;
  assert(ldr.valid());
  ldr.on = 1800; ldr.off = 1801; assert(!ldr.valid());
  ldr.off = 1850; assert(ldr.valid());
  ldr.darkIsLow = 0; assert(!ldr.valid());
  ldr.on = 2200; ldr.off = 1800; assert(ldr.valid());
  ldr.on = 4096; assert(!ldr.valid());
  ldr.on = 2200; ldr.confirmMs = 499; assert(!ldr.valid());
  ldr.confirmMs = 10000; assert(ldr.valid());
  ldr.confirmMs = 10001; assert(!ldr.valid());
  ShowPattern p;
  assert(p.valid());
  ShowEngine show;
  assert(show.tick(100, p) == 0 && !show.running);
  p.masks[0] = 8; p.masks[1] = 16;
  show.start(100);
  assert(show.tick(599, p) == 8 && show.step == 0);
  assert(show.tick(600, p) == 16 && show.step == 1);
  assert(show.tick(8100, p) == 8 && show.step == 0);
  p.repeat = 0;
  assert(show.tick(8100, p) == 0 && !show.running);
  show.start(0xFFFFFF00u);
  assert(show.tick(0x00000100u, p) == 16 && show.step == 1);
  show.stop(); assert(show.tick(0x200, p) == 0);
  p.masks[2] = 3; assert(!p.valid());
  p.masks[2] = 128; assert(!p.valid());
  p.masks[2] = 0; p.durations[2] = 199; assert(!p.valid());
  p.durations[2] = 5001; assert(!p.valid());
  p.durations[2] = 200; assert(p.valid());
  p.version = 2; assert(!p.valid());
  show.start(0); assert(show.tick(0, p) == 0 && !show.running);
  ChirpEngine chirp;
  chirp.start(100, 3, 100, 200);
  assert(chirp.tick(100)); assert(chirp.tick(199));
  assert(!chirp.tick(200)); assert(!chirp.tick(399));
  assert(chirp.tick(400)); assert(!chirp.tick(500));
  assert(chirp.tick(700)); assert(!chirp.tick(800));
  assert(chirp.count == 0);
  chirp.start(0xFFFFFFF0u, 1, 50, 50);
  assert(chirp.tick(0x10)); assert(!chirp.tick(0x22));
  chirp.start(0, 5, 1000, 2000);
  assert(!chirp.tick(20000)); // un loop tardío no prolonga el claxon
  std::cout << "ShowEngine / ChirpEngine: all checks passed\n";
}
