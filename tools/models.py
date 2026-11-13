# Better models (and textures) for every block of the mod: core, bell, incubator, nest, basket, ball.
import json, random, math
from PIL import Image
R = '/home/claude/cobblemon_sanctuary/src/main/resources'
A = f'{R}/assets/cobblemon_sanctuary'
NS = 'cobblemon_sanctuary:block/'


def clamp(v): return max(0, min(255, int(v)))


def img(fn, seed):
    random.seed(seed)
    im = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            c = fn(x, y)
            im.putpixel((x, y), tuple(clamp(v) for v in c[:3]) + ((clamp(c[3]),) if len(c) > 3 else (255,)))
    return im


def save(fn, name, seed):
    img(fn, seed).save(f'{A}/textures/block/{name}.png')


def n(a=6): return random.uniform(-a, a)


# ================================================================== textures
# crystal: faceted teal with a bright diagonal glint
def crystal(x, y):
    facet = (x + y) // 5 % 3
    base = [(70, 214, 168), (52, 186, 146), (96, 232, 190)][facet]
    if abs(x - y) <= 0 and x > 2: return (220, 255, 240)
    if x in (0, 15) or y in (0, 15): return [c * 0.7 for c in base]
    return [c + n(5) for c in base]
save(crystal, 'core_crystal', 11)


# pedestal side: dark polished stone with a carved band of glowing runes
def ped_side(x, y):
    base = (70, 72, 82)
    if y in (0, 15): return (52, 54, 62)
    if 6 <= y <= 9:
        if y in (6, 9): return (44, 46, 54)
        if (x % 4 in (1, 2)) and ((x // 4 + y) % 2 == 0): return (84, 236, 180)
        return (38, 40, 48)
    if x in (0, 15): return (58, 60, 70)
    return [c + n(4) for c in base]
save(ped_side, 'core_pedestal_side', 12)


# pedestal top: stone tiles with an inlaid green ring
def ped_top(x, y):
    d = max(abs(x - 7.5), abs(y - 7.5))
    if 5.5 <= d <= 6.5: return (84, 220, 168)
    if d > 7: return (56, 58, 66)
    if (x // 4 + y // 4) % 2: return [86 + n(4), 88 + n(4), 98 + n(4)]
    return [96 + n(4), 98 + n(4), 108 + n(4)]
save(ped_top, 'core_pedestal_top', 13)


# polished gold trim
def gold(x, y):
    base = (238, 196, 70)
    if y == 0: return (255, 236, 140)
    if y == 15: return (176, 128, 30)
    return [c * (1.05 - 0.15 * (x / 15)) + n(4) for c in base]
save(gold, 'bell_gold', 14)


# dark wooden beam / post with grain
def beam(x, y):
    base = (78, 54, 34)
    g = 8 if (x * 3 + y // 3) % 7 == 0 else 0
    return [c + n(4) + g for c in base]
save(beam, 'hub_bell_side', 15)


def beam_top(x, y):
    d = math.hypot(x - 7.5, y - 7.5)
    ring = 10 if int(d) % 3 == 0 else 0
    return [96 + ring + n(3), 68 + ring + n(3), 42 + ring + n(3)]
save(beam_top, 'hub_bell_top', 16)


# roof shingles
def shingle(x, y):
    row = y // 4
    off = 4 if row % 2 else 0
    if y % 4 == 3 or (x + off) % 8 == 0: return (70, 40, 34)
    return [150 + n(8), 72 + n(6), 54 + n(6)]
save(shingle, 'bell_roof', 17)


# copper casing with rivets and vents
def copper(x, y):
    base = (198, 112, 78)
    if (x, y) in [(1, 1), (14, 1), (1, 14), (14, 14)]: return (240, 180, 130)
    if 5 <= y <= 10 and x % 3 == 1 and 3 <= x <= 12: return (70, 34, 24)
    if y in (0, 15) or x in (0, 15): return (160, 84, 58)
    return [c + n(5) for c in base]
save(copper, 'incubator_side', 18)


def copper_top(x, y):
    d = math.hypot(x - 7.5, y - 7.5)
    if d < 3: return (255, 170, 80)
    if d < 4: return (150, 80, 56)
    return [204 + n(5), 118 + n(5), 82 + n(5)]
save(copper_top, 'incubator_top', 19)


def coil(x, y):  # glowing heating coil
    if y % 3 == 1: return (255, 150 + n(20), 60)
    return (120, 60, 40)
save(coil, 'incubator_coil', 20)


# glass dome: mostly clear with a soft rim and a glint
def glass(x, y):
    if x in (0, 15) or y in (0, 15): return (220, 240, 246, 200)
    if x == y + 3 or x == y + 4: return (255, 255, 255, 150)
    return (200, 230, 240, 50)
save(glass, 'incubator_glass', 21)


# egg: cream with green spots
def egg(x, y):
    spots = [(3, 3), (10, 5), (6, 10), (12, 12), (2, 12), (9, 1)]
    for sx, sy in spots:
        if (x - sx) ** 2 + (y - sy) ** 2 <= 2: return (96, 186, 112)
    return [246 + n(3), 238 + n(3), 214 + n(3)]
save(egg, 'egg_shell', 22)


# nest twigs: tangled sticks
def twigs(x, y):
    t = (x * 2 + y * 3) % 7 < 2 or (x * 5 - y * 2) % 9 < 2
    if t: return [118 + n(8), 84 + n(6), 50 + n(5)]
    return [86 + n(6), 60 + n(5), 36 + n(4)]
save(twigs, 'nest_twigs', 23)


def straw(x, y):
    t = (x + y * 2) % 5 < 2
    return [222 + n(8), 192 + n(8), 110 + n(8)] if t else [196 + n(8), 160 + n(8), 84 + n(6)]
save(straw, 'nest_straw', 24)


# woven basket
def weave(x, y):
    over = ((x // 4) + (y // 4)) % 2 == 0
    if over: c = [196, 150, 88] if (y % 4) not in (0, 3) else [168, 124, 70]
    else: c = [176, 132, 76] if (x % 4) not in (0, 3) else [146, 106, 60]
    return [v + n(5) for v in c]
save(weave, 'basket_weave', 25)


def basket_rim(x, y):
    return [150 + n(6), 104 + n(5), 60 + n(4)] if y % 4 else [120, 82, 46]
save(basket_rim, 'basket_rim', 26)


# a heap of berries and apricorns
def fruit(x, y):
    blobs = [(3, 3, (220, 60, 70)), (9, 2, (80, 110, 220)), (13, 6, (246, 200, 70)), (5, 9, (240, 140, 60)),
             (11, 12, (220, 60, 70)), (2, 14, (120, 196, 90)), (8, 7, (190, 80, 200)), (14, 14, (80, 110, 220))]
    for bx, by, c in blobs:
        d = (x - bx) ** 2 + (y - by) ** 2
        if d <= 3:
            hi = 30 if (x - bx, y - by) in [(-1, -1), (0, -1)] else 0
            return [v + hi for v in c]
    return [104 + n(8), 150 + n(8), 70 + n(6)]  # leaves between
save(fruit, 'basket_fill', 27)


# ================================================================== model helpers
FACES = ['north', 'south', 'east', 'west', 'up', 'down']


def uv_for(face, f, t):
    x1, y1, z1 = f; x2, y2, z2 = t
    return {'down': [x1, 16 - z2, x2, 16 - z1], 'up': [x1, z1, x2, z2], 'north': [16 - x2, 16 - y2, 16 - x1, 16 - y1],
            'south': [x1, 16 - y2, x2, 16 - y1], 'west': [z1, 16 - y2, z2, 16 - y1], 'east': [16 - z2, 16 - y2, 16 - z1, 16 - y1]}[face]


def fixuv(u):
    a, b, c, d = u
    def fit(p, q):
        if p < 0: q -= p; p = 0
        if q > 16: p -= q - 16; q = 16
        return max(0, p), q
    a, c = fit(a, c); b, d = fit(b, d)
    return [round(v, 3) for v in (a, b, c, d)]


def box(f, t, tex, faces=FACES, top=None, bottom=None, rot=None, light=False, uv=None):
    fc = {}
    for face in faces:
        tx = top if face == 'up' and top else bottom if face == 'down' and bottom else tex
        fc[face] = {'texture': tx, 'uv': uv if uv else fixuv(uv_for(face, f, t))}
    e = {'from': f, 'to': t, 'faces': fc}
    if rot: e['rotation'] = rot
    if light: e['neoforge_data'] = {'block_light': 15, 'sky_light': 15}
    return [e]


def write(name, textures, elements, render='minecraft:cutout'):
    m = {'parent': 'minecraft:block/block', 'textures': textures, 'elements': elements}
    if render: m['render_type'] = render
    json.dump(m, open(f'{A}/models/block/{name}.json', 'w'), indent=1)


def T(**kw): return {k: (v if ':' in v else NS + v) for k, v in kw.items()}


# ================================================================== Sanctuary Core: a little shrine with a floating crystal
core = []
core += box([0, 0, 0], [16, 2, 16], '#base')
core += box([1, 2, 1], [15, 3, 15], '#base', top='#top')
core += box([4, 3, 4], [12, 10, 12], '#side', top='#top')
for (x, z) in [(3, 3), (11, 3), (3, 11), (11, 11)]:            # corner columns with gold caps
    core += box([x, 3, z], [x + 2, 9, z + 2], '#base')
    core += box([x - 0.25, 9, z - 0.25], [x + 2.25, 10, z + 2.25], '#gold')
core += box([3.5, 7, 3.5], [12.5, 7.5, 12.5], '#gold', faces=['north', 'south', 'east', 'west'])
core += box([2, 10, 2], [14, 11, 14], '#base', top='#top')    # the dish
for f, t in [([2, 11, 2], [14, 12, 3]), ([2, 11, 13], [14, 12, 14]), ([2, 11, 3], [3, 12, 13]), ([13, 11, 3], [14, 12, 13])]:
    core += box(f, t, '#gold')
core += box([6, 13, 6], [10, 22, 10], '#crystal', rot={'angle': 45, 'axis': 'y', 'origin': [8, 16, 8]}, light=True)
core += box([7, 22, 7], [9, 24, 9], '#crystal', rot={'angle': 45, 'axis': 'y', 'origin': [8, 16, 8]}, light=True)
core += box([7, 11.5, 7], [9, 13, 9], '#crystal', light=True)
for f, t, ang, axis, o in [([3.5, 11, 7], [5.5, 16, 9], -22.5, 'z', [4.5, 11, 8]), ([10.5, 11, 7], [12.5, 15, 9], 22.5, 'z', [11.5, 11, 8]),
                           ([7, 11, 3.5], [9, 14.5, 5.5], 22.5, 'x', [8, 11, 4.5]), ([7, 11, 10.5], [9, 14, 12.5], -22.5, 'x', [8, 11, 11.5])]:
    core += box(f, t, '#crystal', rot={'angle': ang, 'axis': axis, 'origin': o}, light=True)
write('sanctuary_core', T(particle='core_pedestal_side', base='minecraft:block/polished_deepslate', side='core_pedestal_side',
                          top='core_pedestal_top', gold='bell_gold', crystal='core_crystal'), core)

# ================================================================== Hub Bell: a little bell tower with a roof
bell = []
bell += box([0, 0, 0], [16, 2, 16], '#stone')
for x in (1, 12):
    bell += box([x, 2, 6], [x + 3, 4, 10], '#stone')
    bell += box([x + 0.5, 4, 6.5], [x + 2.5, 15, 9.5], '#post', top='#post_top', bottom='#post_top')
bell += box([0.5, 14, 6], [15.5, 16, 10], '#post', top='#post_top')                         # crossbeam
bell += box([-1, 16, 4], [17, 17, 12], '#roof')                                               # roof
bell += box([1, 17, 5], [15, 18, 11], '#roof')
bell += box([3, 18, 6.5], [13, 19, 9.5], '#roof')
bell += box([7.5, 12.5, 7.5], [8.5, 14, 8.5], '#post')                                       # hanger
bell += box([6, 11.5, 6], [10, 12.5, 10], '#bell')                                           # crown
bell += box([5, 7.5, 5], [11, 11.5, 11], '#bell')                                            # body
bell += box([4, 6.5, 4], [12, 7.5, 12], '#bell')                                             # lip
bell += box([7.25, 5.5, 7.25], [8.75, 7, 8.75], '#post')                                     # clapper
write('hub_bell', T(particle='bell_gold', bell='bell_gold', post='hub_bell_side', post_top='hub_bell_top',
                    stone='minecraft:block/polished_andesite', roof='bell_roof'), bell)


# ================================================================== Incubator: copper base, glowing coil, glass dome, egg inside
def incubator(with_egg):
    el = []
    el += box([1, 0, 1], [15, 1, 15], '#copper_dark')
    el += box([2, 1, 2], [14, 5, 14], '#copper', top='#copper_top')
    for (x, z) in [(1, 1), (13, 1), (1, 13), (13, 13)]:
        el += box([x, 0, z], [x + 2, 6, z + 2], '#copper_dark')
    el += box([4, 5, 4], [12, 6, 12], '#coil', light=True)
    el += box([3, 5, 3], [13, 13, 13], '#glass')                                   # dome
    el += box([4, 13, 4], [12, 14.5, 12], '#glass')
    el += box([6.5, 14.5, 6.5], [9.5, 15.5, 9.5], '#copper', top='#copper_top')
    el += box([7.5, 15.5, 7.5], [8.5, 16.5, 8.5], '#coil', light=True)              # little lamp
    if with_egg:
        el += box([6, 6, 6], [10, 7, 10], '#egg')
        el += box([5.5, 7, 5.5], [10.5, 10, 10.5], '#egg')
        el += box([6, 10, 6], [10, 11.5, 10], '#egg')
        el += box([6.75, 11.5, 6.75], [9.25, 12.25, 9.25], '#egg')
    return el
it = T(particle='incubator_side', copper='incubator_side', copper_top='incubator_top', copper_dark='minecraft:block/cut_copper',
       coil='incubator_coil', glass='incubator_glass', egg='egg_shell')
write('incubator', it, incubator(False), 'minecraft:translucent')
write('incubator_egg', it, incubator(True), 'minecraft:translucent')

# ================================================================== Nest: a round nest of twigs lined with straw, an egg on top
nest = []
nest += box([3, 0, 3], [13, 1, 13], '#twigs')
for f, t in [([2, 1, 4], [14, 3, 12]), ([4, 1, 2], [12, 3, 14]), ([3, 1, 3], [13, 3, 13])]:      # rounded ring
    nest += box(f, t, '#twigs', top='#straw')
for f, t in [([1, 2, 5], [3, 4, 11]), ([13, 2, 5], [15, 4, 11]), ([5, 2, 1], [11, 4, 3]), ([5, 2, 13], [11, 4, 15])]:
    nest += box(f, t, '#twigs')
for (x, z) in [(2.5, 2.5), (11.5, 2.5), (2.5, 11.5), (11.5, 11.5)]:
    nest += box([x, 2, z], [x + 2, 3.5, z + 2], '#twigs')
nest += box([4, 3, 4], [12, 3.5, 12], '#straw', faces=['up'])
nest += box([6, 3.5, 6], [10, 4.5, 10], '#egg')                                    # egg
nest += box([5.5, 4.5, 5.5], [10.5, 7.5, 10.5], '#egg')
nest += box([6, 7.5, 6], [10, 9, 10], '#egg')
nest += box([6.75, 9, 6.75], [9.25, 9.75, 9.25], '#egg')
write('egg_nest', T(particle='nest_twigs', twigs='nest_twigs', straw='nest_straw', egg='egg_shell'), nest)

# ================================================================== Gathering Basket: rounded woven basket, arched handle, heaped fruit
bk = []
bk += box([3, 0, 3], [13, 1, 13], '#weave')
for f, t in [([2, 1, 3], [14, 8, 13]), ([3, 1, 2], [13, 8, 14])]:
    bk += box(f, t, '#weave', faces=['north', 'south', 'east', 'west', 'down'])
for f, t in [([1.5, 7, 2.5], [14.5, 8.5, 13.5]), ([2.5, 7, 1.5], [13.5, 8.5, 14.5])]:          # rim
    bk += box(f, t, '#rim', faces=['north', 'south', 'east', 'west'])
for f, t in [([1.5, 8.5, 2.5], [14.5, 8.75, 3.5]), ([1.5, 8.5, 12.5], [14.5, 8.75, 13.5]), ([1.5, 8.5, 3.5], [2.5, 8.75, 12.5]), ([13.5, 8.5, 3.5], [14.5, 8.75, 12.5])]:
    bk += box(f, t, '#rim')
bk += box([3, 7, 3], [13, 8.5, 13], '#fill', faces=['up'])                                       # the heap
bk += box([4, 8.5, 4], [12, 9.5, 12], '#fill', faces=['up', 'north', 'south', 'east', 'west'])
bk += box([5.5, 9.5, 5.5], [10.5, 10.25, 10.5], '#fill', faces=['up', 'north', 'south', 'east', 'west'])
for x in (2, 13):                                                                                  # arched handle
    bk += box([x, 8.5, 7.25], [x + 1, 13, 8.75], '#rim')
bk += box([3, 13, 7.25], [5, 15, 8.75], '#rim')
bk += box([11, 13, 7.25], [13, 15, 8.75], '#rim')
bk += box([5, 15, 7.25], [11, 16, 8.75], '#rim')
write('gathering_basket', T(particle='basket_weave', weave='basket_weave', rim='basket_rim', fill='basket_fill'), bk)

# ================================================================== Bouncy Ball: a rounder ball, built in slices
ball = []
for y0, y1, w in [(0, 1, 4), (1, 2, 6), (2, 6, 8), (6, 7, 6), (7, 8, 4)]:
    a = 8 - w / 2; b = 8 + w / 2
    side = {'texture': '#side'}
    ball += box([a, y0, a + 1], [b, y1, b - 1], '#side', top='#top', bottom='#top')
    ball += box([a + 1, y0, a], [b - 1, y1, b], '#side', top='#top', bottom='#top')
for e in ball:  # wrap the stripes all the way round and keep the wedges centred on top
    for k, fc in e['faces'].items():
        x1, y1, z1 = e['from']; x2, y2, z2 = e['to']
        if k in ('up', 'down'): fc['uv'] = [x1, z1, x2, z2]
        else: fc['uv'] = fixuv([(x1 if k in ('north', 'south') else z1), 16 - y2 * 2, (x2 if k in ('north', 'south') else z2), 16 - y1 * 2])
write('bouncy_ball', T(particle='ball_side', side='ball_side', top='ball_top'), ball)
print('models done')
