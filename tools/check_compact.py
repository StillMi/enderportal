"""Source/geometry regression checks; not a substitute for in-game testing."""
from pathlib import Path
import math
import re

ROOT = Path(__file__).resolve().parents[1]
manager = (ROOT / 'src/main/java/com/example/enderportal/portal/PortalManager.java').read_text(encoding='utf-8')
entity = (ROOT / 'src/main/java/com/example/enderportal/block/RemotePortalBlockEntity.java').read_text(encoding='utf-8')
timers = (ROOT / 'src/main/java/com/example/enderportal/portal/PortalTimers.java').read_text(encoding='utf-8')

start = manager.index('    private static List<Piece> pieces(')
end = manager.index('    /** Pre-1.14 geometry', start)
new = manager[start:end]
assert 'lx, 0, 1' in new
assert 'fillBottom' not in new
assert 'stepY' not in new
assert re.search(r'STEP_COUNT = 1;', manager)
assert re.search(r'PLATFORM_DEPTH = 1;', manager)
assert 'legacyPieces' in manager
assert 'oldPlatform' in manager
assert 'LayoutVersion' in entity and 'EntryStep' in entity
assert 'portal.isSameEntrance(charge.portal)' in timers

frame = {(x,y,0) for x in range(4) for y in (0,4)}
frame |= {(x,y,0) for x in (0,3) for y in range(1,4)}
face = {(x,y,0) for x in (1,2) for y in range(1,4)}
step = {(x,0,1) for x in (1,2)}
assert len(frame) == 14 and len(face) == 6 and len(step) == 2
assert len(frame | face | step) == 22
assert min(y for _,y,_ in frame | face | step) == 0
assert max(z for _,_,z in frame | face | step) == 1

# Direction vectors and their counter-clockwise lateral axis.
directions = {
    'north': ((0,-1),(-1,0)),
    'south': ((0,1),(1,0)),
    'east': ((1,0),(0,-1)),
    'west': ((-1,0),(0,1)),
}
checks = 0
for name,(front,lateral) in directions.items():
    for origin in ((0,0),(15,15),(-16,-16),(101,-57)):
        support = {(origin[0] + lateral[0]*x + front[0],
                    origin[1] + lateral[1]*x + front[1]) for x in (1,2)}
        for h in range(256):
            lx = 2.0 + ((h / 255.0) - 0.5) * 0.8
            lz = 1.5
            x = origin[0] + 0.5 + lateral[0]*(lx-0.5) + front[0]*(lz-0.5)
            z = origin[1] + 0.5 + lateral[1]*(lx-0.5) + front[1]*(lz-0.5)
            assert (math.floor(x),math.floor(z)) in support, (name,origin,h,x,z)
            # A normal player's 0.6-wide box stays laterally inside the two-wide step.
            assert 1.0 <= lx-0.3 and lx+0.3 <= 3.0
            checks += 1
print('PASS: 14 frame + 6 face + 2 stairs; no platform or support pillars')
print(f'PASS: {checks} landing centre checks across four orientations and chunk boundaries')
print('PASS: layout persistence and legacy-only teardown paths present')
print('PASS: six portal face blocks share an entrance timer identity')
print('LIMIT: geometry/source checks only; no Minecraft client or server runtime test')
