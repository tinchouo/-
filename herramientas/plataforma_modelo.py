# Genera las piezas de las plataformas que se mueven (estilo Mario Galaxy 2):
#  - cada bloque de la plataforma es una pieza de medio bloque de alto; segun cuales lados son "de afuera" (mascara N=1, E=2, S=4, W=8)
#    lleva el borde oscuro y queda un poquito metida para adentro; las esquinas de afuera son redondeadas
#  - todo en gris: el color lo pone el server con el tinte (custom_model_data, colores de la ruleta)
#  - 8 niveles de transparencia (para desvanecerse): plataforma_<mascara>_<0..7> (7 = entera)
#  - el marco de la salida (cuadrado brillante en el piso): plataforma_marco_<mascara>
import json, math, os, sys
from PIL import Image, ImageDraw
R = sys.argv[1]
TEX = os.path.join(R, 'textures/item/plataforma'); MOD = os.path.join(R, 'models/item/plataforma'); ITM = os.path.join(R, 'items')
for d in (TEX, MOD, ITM): os.makedirs(d, exist_ok=True)
N, E, S, W = 1, 2, 4, 8
P = 32            # pixeles de la textura por bloque
MET = 0.5         # cuanto se mete para adentro en los lados de afuera (en dieciseisavos)
RAD = 2.5         # radio de las esquinas redondeadas
BORDE = 1.6       # ancho del borde oscuro arriba
ALFAS = [round(255 * (i + 1) / 8) for i in range(8)]

def arriba(m, a):
    k = P / 16; SS = 4; T = P * SS; kk = k * SS
    im = Image.new('RGBA', (T, T), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    x0 = MET if m & W else 0; x1 = 16 - (MET if m & E else 0); z0 = MET if m & N else 0; z1 = 16 - (MET if m & S else 0)
    def caja(x0, z0, x1, z1, col, rad):
        d.rounded_rectangle([x0 * kk, z0 * kk, x1 * kk - 1, z1 * kk - 1], radius=rad * kk, fill=col,
                            corners=(bool(m & N and m & W), bool(m & N and m & E), bool(m & S and m & E), bool(m & S and m & W)))
    caja(x0, z0, x1, z1, (110, 110, 110, ALFAS[a]), RAD)
    b = BORDE
    caja(x0 + (b if m & W else 0), z0 + (b if m & N else 0), x1 - (b if m & E else 0), z1 - (b if m & S else 0), (238, 238, 238, ALFAS[a]), max(0.1, RAD - b))
    return im.resize((P, P), Image.LANCZOS)
def solido(gris, a): return Image.new('RGBA', (16, 16), (gris, gris, gris, ALFAS[a]))

def cubo(a, b, caras, rot=None):
    e = {"from": [round(v, 3) for v in a], "to": [round(v, 3) for v in b], "faces": caras}
    if rot: e["rotation"] = rot
    return e
for a in range(8):
    solido(150, a).save(os.path.join(TEX, f'lado_{a}.png')); solido(95, a).save(os.path.join(TEX, f'abajo_{a}.png'))
for m in range(16):
    for a in range(8):
        arriba(m, a).save(os.path.join(TEX, f'arriba_{m}_{a}.png'))
        x0 = MET if m & W else 0; x1 = 16 - (MET if m & E else 0); z0 = MET if m & N else 0; z1 = 16 - (MET if m & S else 0)
        def caras(): return {"up": {"texture": "#arriba", "tintindex": 0}, "down": {"texture": "#abajo", "tintindex": 0},
                             "north": {"texture": "#lado", "tintindex": 0}, "south": {"texture": "#lado", "tintindex": 0},
                             "east": {"texture": "#lado", "tintindex": 0}, "west": {"texture": "#lado", "tintindex": 0}}
        # el cuerpo: una sola caja (las esquinas redondeadas van en el dibujo de arriba)
        el = [cubo([x0, 0, z0], [x1, 8, z1], caras())]
        mod = {"textures": {"arriba": f"tod5:item/plataforma/arriba_{m}_{a}", "lado": f"tod5:item/plataforma/lado_{a}", "abajo": f"tod5:item/plataforma/abajo_{a}",
                            "particle": f"tod5:item/plataforma/lado_{a}"},
               "elements": el, "display": {"fixed": {"scale": [1, 1, 1]}, "gui": {"rotation": [30, 45, 0], "scale": [0.6, 0.6, 0.6]}}}
        json.dump(mod, open(os.path.join(MOD, f'pieza_{m}_{a}.json'), 'w'))
        json.dump({"model": {"type": "minecraft:model", "model": f"tod5:item/plataforma/pieza_{m}_{a}",
                             "tints": [{"type": "minecraft:custom_model_data", "index": 0, "default": 16777215}]}},
                  open(os.path.join(ITM, f'plataforma_{m}_{a}.json'), 'w'))
    # el marco de la salida: tiras finitas en los lados de afuera
    Image.new('RGBA', (16, 16), (255, 255, 255, 255)).save(os.path.join(TEX, 'marco.png'))
    el = []
    t, h = 1.6, 0.5
    def tira(a, b): el.append(cubo(a, b, {f: {"texture": "#m", "tintindex": 0} for f in ("north", "south", "east", "west", "up", "down")}))
    if m & N: tira([0, 0, 0], [16, h, t])
    if m & S: tira([0, 0, 16 - t], [16, h, 16])
    if m & W: tira([0, 0, 0], [t, h, 16])
    if m & E: tira([16 - t, 0, 0], [16, h, 16])
    if not el: el.append(cubo([7.9, 0, 7.9], [8.1, 0.01, 8.1], {"up": {"texture": "#m", "tintindex": 0}}))
    json.dump({"textures": {"m": "tod5:item/plataforma/marco", "particle": "tod5:item/plataforma/marco"}, "elements": el,
               "display": {"fixed": {"scale": [1, 1, 1]}}}, open(os.path.join(MOD, f'marco_{m}.json'), 'w'))
    json.dump({"model": {"type": "minecraft:model", "model": f"tod5:item/plataforma/marco_{m}",
                         "tints": [{"type": "minecraft:custom_model_data", "index": 0, "default": 16777215}]}},
              open(os.path.join(ITM, f'plataforma_marco_{m}.json'), 'w'))
print('listo')
