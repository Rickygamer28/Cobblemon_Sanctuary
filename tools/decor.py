# Generates the decoration textures and models (beds in three sizes, the ball, the trough).
import json, random, math
from PIL import Image
R='/home/claude/cobblemon_sanctuary/src/main/resources'
A=f'{R}/assets/cobblemon_sanctuary'
def clamp(v): return max(0,min(255,int(v)))
def img(fn, seed):
    random.seed(seed); im=Image.new('RGBA',(16,16))
    for y in range(16):
        for x in range(16): im.putpixel((x,y),tuple(clamp(c) for c in fn(x,y))+(255,))
    return im
def save(im,n): im.save(f'{A}/textures/block/{n}.png')
# ---- textures
def rim(x,y):  # plush bolster: soft ribs, gentle shading towards the bottom
    base=(184,92,70); rib=0.9 if x%4==0 else (1.06 if x%4==2 else 1.0)
    shade=1.08-0.16*(y/15); n=random.uniform(-6,6)
    return [c*rib*shade+n for c in base]
save(img(rim,1),'bed_rim')
def rimtop(x,y):
    base=(206,112,86); n=random.uniform(-6,6)
    if (x+y)%5==0: n+=10
    return [c+n for c in base]
save(img(rimtop,2),'bed_rim_top')
def fleece(x,y):
    base=(238,228,206); n=random.uniform(-7,7)
    if (x*7+y*3)%11 in (0,1): n-=14   # little curls
    if (x*5+y*9)%13==0: n+=8
    return [c+n for c in base]
save(img(fleece,3),'bed_fleece')
def bedbase(x,y):
    base=(120,64,50); n=random.uniform(-5,5); return [c+n for c in base]
save(img(bedbase,4),'bed_base')
# ball: side stripes, top/bottom wedges meeting at a white cap
COLS=[(232,72,64),(246,244,236),(64,132,222),(248,206,64)]
def ballside(x,y):
    c=COLS[(x//4)%4]; s=1.0-0.18*abs(y-7.5)/7.5; n=random.uniform(-5,5)
    return [v*s+n for v in c]
save(img(ballside,5),'ball_side')
def balltop(x,y):
    dx,dy=x-7.5,y-7.5
    if dx*dx+dy*dy<5: return (250,250,246)
    a=(math.atan2(dy,dx)+math.pi)/(2*math.pi); c=COLS[int(a*4)%4]; n=random.uniform(-5,5)
    return [v+n for v in c]
save(img(balltop,6),'ball_top')
def wood(x,y):  # warm planks running sideways
    plank=y//4; base=[(150,104,62),(142,98,58),(156,110,66),(146,100,60)][plank]
    n=random.uniform(-7,7)+(6 if (x+plank*5)%9==0 else 0)
    if y%4==3: return [c*0.72 for c in base]
    return [c+n for c in base]
save(img(wood,7),'trough_wood')
def iron(x,y):
    n=random.uniform(-8,8); return [92+n,94+n,100+n] if y not in (0,15) else [70,72,78]
save(img(iron,8),'trough_band')
def food(x,y):
    r=random.random()
    if r<0.16: return (206,46,62)
    if r<0.27: return (78,98,206)
    if r<0.36: return (246,196,64)
    if r<0.42: return (120,190,80)
    return [c+random.uniform(-12,12) for c in (204,168,84)]
save(img(food,9),'trough_food')

# ---- model helpers
FACES=['north','south','east','west','up','down']
def uv_for(face,f,t):
    x1,y1,z1=f; x2,y2,z2=t
    return {'down':[x1,16-z2,x2,16-z1],'up':[x1,z1,x2,z2],'north':[16-x2,16-y2,16-x1,16-y1],
            'south':[x1,16-y2,x2,16-y1],'west':[z1,16-y2,z2,16-y1],'east':[16-z2,16-y2,16-z1,16-y1]}[face]
def box(f,t,tex,faces=FACES,top=None,bottom=None):
    """A box that may span several blocks: split into 16-wide pieces so textures tile instead of stretching;
    only the outside faces are kept."""
    out=[]
    xs=cuts(f[0],t[0]); zs=cuts(f[2],t[2])
    for (ax,bx) in xs:
        for (az,bz) in zs:
            ox=math.floor(ax/16)*16 if ax!=bx else 0; oz=math.floor(az/16)*16
            lf=[ax-ox,f[1],az-oz]; lt=[bx-ox,t[1],bz-oz]
            fc={}
            for face in faces:
                if face=='west' and ax!=f[0]: continue
                if face=='east' and bx!=t[0]: continue
                if face=='north' and az!=f[2]: continue
                if face=='south' and bz!=t[2]: continue
                tx=top if face=='up' and top else bottom if face=='down' and bottom else tex
                fc[face]={'texture':tx,'uv':[round(v,3) for v in uv_for(face,lf,lt)]}
            if fc: out.append({'from':[ax,f[1],az],'to':[bx,t[1],bz],'faces':fc})
    return out
def cuts(a,b):
    pts=[a]+[v for v in range(math.floor(a/16)*16+16, math.ceil(b), 16) if a<v<b]+[b]
    return list(zip(pts[:-1],pts[1:]))
def write(name,textures,elements):
    json.dump({'parent':'minecraft:block/block','render_type':'minecraft:cutout','textures':textures,'elements':elements},open(f'{A}/models/block/{name}.json','w'),indent=1)

# ---- beds: a round plush bed with a low front to step in
def bed(n):
    W=16*n; x0=0 if n<3 else -16; z0=x0; x1=x0+W; z1=z0+W
    t=[3,5,6][n-1]; hr=[6,8,10][n-1]; hc=[3,4,5][n-1]; c=t
    el=[]
    el+=box([x0+1,0,z0+1],[x1-1,1,z1-1],'#base',faces=['north','south','east','west','down'])  # base
    el+=box([x0+t,1,z0+t],[x1-t,hc,z1-t],'#fleece')                                  # cushion
    el+=box([x0+c,0,z1-t],[x1-c,hr,z1],'#rim',top='#rimtop')                        # back (south)
    el+=box([x0+c,0,z0],[x1-c,hr-[2,3,4][n-1],z0+t],'#rim',top='#rimtop')           # front (north, lower: the way in)
    el+=box([x0,0,z0+c],[x0+t,hr,z1-c],'#rim',top='#rimtop')                        # left
    el+=box([x1-t,0,z0+c],[x1,hr,z1-c],'#rim',top='#rimtop')                        # right
    for (ax,az) in [(x0+1,z0+1),(x1-1-c,z0+1),(x0+1,z1-1-c),(x1-1-c,z1-1-c)]:     # rounded corners
        h=hr-1 if az>z0+W/2 else hr-[2,3,4][n-1]
        el+=box([ax,0,az],[ax+c,h,az+c],'#rim',top='#rimtop')
    return el
tx={'particle':'cobblemon_sanctuary:block/bed_rim','rim':'cobblemon_sanctuary:block/bed_rim','rimtop':'cobblemon_sanctuary:block/bed_rim_top',
    'fleece':'cobblemon_sanctuary:block/bed_fleece','base':'cobblemon_sanctuary:block/bed_base'}
for n,name in [(1,'pokemon_bed'),(2,'large_pokemon_bed'),(3,'huge_pokemon_bed')]: write(name,tx,bed(n))
# ---- ball: a rounder sphere
ball=[]
for f,t in [([5,0,5],[11,8,11]),([4,1,5],[12,7,11]),([5,1,4],[11,7,12]),([4.5,0.5,4.5],[11.5,7.5,11.5])]:
    ball.append({'from':f,'to':t,'faces':{fc:{'texture':'#top' if fc in ('up','down') else '#side','uv':[0,0,16,16] if fc in ('up','down') else [0,1,16,15]} for fc in FACES}})
write('bouncy_ball',{'particle':'cobblemon_sanctuary:block/ball_side','side':'cobblemon_sanctuary:block/ball_side','top':'cobblemon_sanctuary:block/ball_top'},ball)
# ---- trough on little legs, with iron bands and a heap of food
def trough(level):
    el=[]
    for (ax,az) in [(1,3),(13,3),(1,11),(13,11)]: el+=box([ax,0,az],[ax+2,3,az+2],'#wood')
    el+=box([1,3,3],[15,4,13],'#wood')
    el+=box([1,4,3],[15,10,4.5],'#wood'); el+=box([1,4,11.5],[15,10,13],'#wood')
    el+=box([1,4,4.5],[2.5,10,11.5],'#wood'); el+=box([13.5,4,4.5],[15,10,11.5],'#wood')
    for bx in (3.5,11.5):  # iron bands around the outside
        el+=box([bx,3.6,2.8],[bx+1,9.6,13.2],'#band',faces=['north','south','up','down'])
    if level>=1: el+=box([2.5,4,4.5],[13.5,6.5 if level==1 else 8,11.5],'#food',faces=['up','north','south','east','west'])
    if level>=2: el+=box([4,8,5.5],[12,9.5,10.5],'#food',faces=['up','north','south','east','west'])
    return el
ttx={'particle':'cobblemon_sanctuary:block/trough_wood','wood':'cobblemon_sanctuary:block/trough_wood','band':'cobblemon_sanctuary:block/trough_band','food':'cobblemon_sanctuary:block/trough_food'}
write('feeding_trough',ttx,trough(0)); write('feeding_trough_low',ttx,trough(1)); write('feeding_trough_full',ttx,trough(2))
json.dump({'textures':{'particle':'cobblemon_sanctuary:block/bed_rim'}},open(f'{A}/models/block/bed_part.json','w'))
print('done')
