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

def paw(x,y):
    c=fleece(x,y)
    pads=[(7.5,9.5,2.6)]+[(px,py,1.2) for px,py in [(4.5,5.5),(7,4),(9.5,4.5),(11,6.5)]]
    for px,py,r in pads:
        if (x-px)**2+(y-py)**2<=r*r: return [c[0]*0.78,c[1]*0.72,c[2]*0.7]
    return c
save(img(paw,31),'bed_fleece_paw')
def trim(x,y): return [236+random.uniform(-5,5),226+random.uniform(-5,5),200+random.uniform(-5,5)] if y%3 else [210,196,170]
save(img(trim,32),'bed_trim')
def pillow2(x,y):
    if x in (0,15) or y in (0,15): return [130,150,186]
    if (x in (1,14) or y in (1,14)) and (x+y)%2: return [220,228,240]
    return [164+random.uniform(-6,6),184+random.uniform(-6,6),214+random.uniform(-6,6)]
save(img(pillow2,33),'bed_pillow')
def post(x,y):
    base=(116,80,46); g=10 if (x*3+y)%6==0 else 0
    return [v+random.uniform(-5,5)+g for v in base] if x not in (0,15) else [92,62,36]
save(img(post,34),'trough_post')
def band2(x,y):
    if y in (3,12) and x%5==2: return (190,192,200)
    return [92+random.uniform(-8,8)]*3 if y not in (0,15) else [66,68,74]
save(img(band2,35),'trough_band')
def water(x,y):
    w=math.sin((x+y*0.6)*0.9)*10
    if (x*7+y*3)%17==0: return (210,236,255)
    return [56+w,128+w,206+w]
save(img(water,36),'trough_water')
for name,col in [('berry_red',(214,52,66)),('berry_blue',(70,96,214)),('berry_yellow',(246,198,64))]:
    save(img(lambda x,y,c=col: [v*(1.25 if (x<6 and y<6) else 0.85 if (x>10 or y>10) else 1)+random.uniform(-6,6) for v in c],37),name)
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

# ---- beds: a round plush bed with rolled bolsters, piping, a puffy cushion and a pillow; low front to step in
def bed(n):
    W=16*n; x0=0 if n<3 else -16; z0=x0; x1=x0+W; z1=z0+W
    t=[3,5,6][n-1]; hr=[6,8,10][n-1]; hc=[3,4,5][n-1]; c=t; drop=[2,3,4][n-1]
    el=[]
    el+=box([x0+1,0,z0+1],[x1-1,1,z1-1],'#base',faces=['north','south','east','west','down'])
    el+=box([x0+t,1,z0+t],[x1-t,hc,z1-t],'#fleece')                                   # cushion
    el+=box([x0+t+1,hc,z0+t+1],[x1-t-1,hc+0.5,z1-t-1],'#paw',faces=['up','north','south','east','west'])  # puffy middle
    def bolster(f,t_,h):
        out=[]
        out+=box([f[0],0,f[2]],[t_[0],h-1,t_[2]],'#rim',top='#rimtop')
        out+=box([f[0]+0.5,h-1,f[2]+0.5],[t_[0]-0.5,h,t_[2]-0.5],'#rimtop')          # rolled top
        return out
    el+=bolster([x0+c,0,z1-t],[x1-c,0,z1],hr)                                          # back (south)
    el+=bolster([x0+c,0,z0],[x1-c,0,z0+t],hr-drop)                                     # front (north, the way in)
    el+=bolster([x0,0,z0+c],[x0+t,0,z1-c],hr)                                          # left
    el+=bolster([x1-t,0,z0+c],[x1,0,z1-c],hr)                                          # right
    for (ax,az) in [(x0+1,z0+1),(x1-1-c,z0+1),(x0+1,z1-1-c),(x1-1-c,z1-1-c)]:          # rounded corners
        h=hr-1 if az>z0+W/2 else hr-drop
        el+=box([ax,0,az],[ax+c,h-1,az+c],'#rim',top='#rimtop')
        el+=box([ax+0.5,h-1,az+0.5],[ax+c-0.5,h-0.5,az+c-0.5],'#rimtop')
    # piping: a cream band round the bottom of the bolsters
    for f,t_ in [([x0+c,0,z0-0.05],[x1-c,0.75,z0]),([x0+c,0,z1],[x1-c,0.75,z1+0.05]),([x0-0.05,0,z0+c],[x0,0.75,z1-c]),([x1,0,z0+c],[x1+0.05,0.75,z1-c])]:
        el+=box(f,t_,'#trim',faces=['north','south','east','west'])
    # a little pillow against the back
    pw=[3,5,7][n-1]; mid=x0+W/2
    el+=box([mid-pw,hc,z1-t-[3,4,5][n-1]],[mid+pw,hc+[2,2.5,3][n-1],z1-t],'#pillow')
    return el
tx={'particle':'cobblemon_sanctuary:block/bed_rim','rim':'cobblemon_sanctuary:block/bed_rim','rimtop':'cobblemon_sanctuary:block/bed_rim_top',
    'fleece':'cobblemon_sanctuary:block/bed_fleece','base':'cobblemon_sanctuary:block/bed_base','paw':'cobblemon_sanctuary:block/bed_fleece_paw',
    'trim':'cobblemon_sanctuary:block/bed_trim','pillow':'cobblemon_sanctuary:block/bed_pillow'}
for n,name in [(1,'pokemon_bed'),(2,'large_pokemon_bed'),(3,'huge_pokemon_bed')]: write(name,tx,bed(n))
# ---- trough: a wooden tub on legs with braces, iron bands, a lip, and two sides: food and water
def trough(level):
    el=[]
    for (ax,az) in [(1.5,3.5),(13,3.5),(1.5,11),(13,11)]: el+=box([ax,0,az],[ax+1.5,3.5,az+1.5],'#post',top='#wood')
    el+=box([3,1,4],[13,2,5],'#post'); el+=box([3,1,11],[13,2,12],'#post')                 # braces
    el+=box([2,3,4],[14,4,12],'#wood')                                                       # floor
    el+=box([2,4,3.5],[14,9.5,4.5],'#wood'); el+=box([2,4,11.5],[14,9.5,12.5],'#wood')      # long sides
    el+=box([1.5,4,4.5],[2.5,9.5,11.5],'#wood'); el+=box([13.5,4,4.5],[14.5,9.5,11.5],'#wood')  # ends
    for f,t_ in [([1.5,9.5,3],[14.5,10.25,4.5]),([1.5,9.5,11.5],[14.5,10.25,13]),([1,9.5,4.5],[2.5,10.25,11.5]),([13.5,9.5,4.5],[15,10.25,11.5])]:
        el+=box(f,t_,'#post',top='#wood')                                                   # lip
    for bx in (4,11.5):                                                                      # iron bands
        el+=box([bx,3.5,3.4],[bx+1,9.4,12.6],'#band',faces=['north','south','down'])
    el+=box([9.5,4,4.5],[10.5,8.5,11.5],'#wood')                                            # divider
    el+=box([10.5,4,4.5],[13.5,7.5,11.5],'#water',faces=['up'])                             # water side
    if level>=1:
        el+=box([2.5,4,4.5],[9.5,6 if level==1 else 7.5,11.5],'#food',faces=['up'])
    if level>=2:
        el+=box([3.5,7.5,5.5],[8.5,8.5,10.5],'#food',faces=['up','north','south','east','west'])
        for (x,z,c) in [(4,6,'#red'),(6.5,8.5,'#blue'),(5,9,'#yellow'),(7,6,'#red'),(4.5,7.75,'#blue')]:
            el+=box([x,8.5,z],[x+1.25,9.75,z+1.25],c)
    return el
ttx={'particle':'cobblemon_sanctuary:block/trough_wood','wood':'cobblemon_sanctuary:block/trough_wood','post':'cobblemon_sanctuary:block/trough_post',
     'band':'cobblemon_sanctuary:block/trough_band','food':'cobblemon_sanctuary:block/trough_food','water':'cobblemon_sanctuary:block/trough_water',
     'red':'cobblemon_sanctuary:block/berry_red','blue':'cobblemon_sanctuary:block/berry_blue','yellow':'cobblemon_sanctuary:block/berry_yellow'}
write('feeding_trough',ttx,trough(0)); write('feeding_trough_low',ttx,trough(1)); write('feeding_trough_full',ttx,trough(2))
json.dump({'textures':{'particle':'cobblemon_sanctuary:block/bed_rim'}},open(f'{A}/models/block/bed_part.json','w'))
print('done')
