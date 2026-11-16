# Genera la flecha de gravedad (verde y roja):
#  - el dibujo plano (para el inventario)
#  - el modelo 3D liso (en el mundo y en la mano): punta triangular de verdad (un cuadrado girado 45 grados, del que se ve solo la mitad de abajo),
#    cuerpo, collar gris, aro de 16 pedazos y el centro gris con el hueco y el punto de color.
import json, math, os, sys
from PIL import Image, ImageDraw
R = sys.argv[1]  # src/client/resources/assets/tod5
TEX = os.path.join(R, 'textures/item'); MOD = os.path.join(R, 'models/item'); ITM = os.path.join(R, 'items')
for d in (TEX, MOD, ITM): os.makedirs(d, exist_ok=True)
for viejo in ('flecha_metal', 'flecha_disco', 'flecha_hueco'):
    f = os.path.join(TEX, viejo + '.png')
    if os.path.exists(f): os.remove(f)

N, SS = 128, 4   # textura de 128x128, dibujada al cuádruple y reducida (bordes suaves)
S = N * SS / 16  # pixeles por unidad del modelo
def px(x, y): return (x * S, (16 - y) * S)   # coordenadas del modelo (y para arriba) a la imagen

def dibujar(nombre, luz, base, sombra):
    im = Image.new('RGBA', (N * SS, N * SS), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    def mezcla(t): return tuple(int(luz[i] * (1 - t) + sombra[i] * t) for i in range(3)) + (255,)
    oscuro = tuple(int(v * 0.55) for v in sombra) + (255,)
    # punta (triangulo) + cuerpo: abajo la punta
    d.polygon([px(8, 0), px(3, 6), px(13, 6)], fill=oscuro)
    d.polygon([px(8, 0.55), px(3.9, 5.7), px(12.1, 5.7)], fill=mezcla(0.5))
    d.rectangle([*px(6.4, 8.9), *px(9.6, 5.5)], fill=oscuro)
    d.rectangle([*px(6.9, 8.9), *px(9.1, 5.7)], fill=mezcla(0.45))
    # collar gris
    d.rounded_rectangle([*px(5.9, 9.9), *px(10.1, 8.5)], radius=S * 0.4, fill=(40, 40, 44, 255))
    d.rounded_rectangle([*px(6.2, 9.6), *px(9.8, 8.8)], radius=S * 0.3, fill=(110, 112, 116, 255))
    # aro
    cx, cy = 8, 12.6
    for r, col in ((3.45, oscuro), (3.15, mezcla(0.15)), (2.35, oscuro)):
        a, b = px(cx - r, cy + r), px(cx + r, cy - r); d.ellipse([*a, *b], fill=col)
    # centro: disco gris con hueco oscuro y punto del color
    for r, col in ((2.3, (60, 60, 64, 255)), (1.95, (150, 152, 156, 255)), (1.3, (20, 20, 24, 255)), (0.5, base + (255,))):
        a, b = px(cx - r, cy + r), px(cx + r, cy - r); d.ellipse([*a, *b], fill=col)
    # brillo
    a, b = px(cx - 2.9, cy + 2.9), px(cx + 0.4, cy - 0.4)
    d.arc([*a, *b], 150, 220, fill=(255, 255, 255, 150), width=int(S * 0.28))
    im = im.resize((N, N), Image.LANCZOS)
    im.save(os.path.join(TEX, nombre + '.png'))

COLORES = {'verde': ((70, 220, 40), (150, 250, 110), (40, 150, 25)), 'roja': ((235, 40, 100), (255, 120, 160), (170, 20, 70))}
for k, (b, l, s) in COLORES.items(): dibujar('flecha_' + k, l, b, s)


def solido(nombre, rgb):
    Image.new('RGBA', (16, 16), tuple(rgb) + (255,)).save(os.path.join(TEX, nombre + '.png'))
def punta(nombre, rgb):
    # la mitad de abajo-izquierda (del cuadrado sin girar) opaca: girado 45 grados queda el triangulo que apunta para abajo
    im = Image.new('RGBA', (64, 64), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.polygon([(0, 0), (0, 64), (64, 64)], fill=tuple(rgb) + (255,))
    im.save(os.path.join(TEX, nombre + '.png'))
solido('flecha_metal', (128, 130, 136)); solido('flecha_oscuro', (22, 22, 26))
for k, (b, l, s) in COLORES.items():
    solido('flecha_' + k + '_color', b); punta('flecha_' + k + '_punta', b)

Z0, Z1 = 7.25, 8.75
def cubo(a, b, tex, z0=Z0, z1=Z1, caras=('north', 'south', 'east', 'west', 'up', 'down'), rot=None, uvs=None):
    e = {"from": [round(a[0], 4), round(a[1], 4), z0], "to": [round(b[0], 4), round(b[1], 4), z1], "faces": {}}
    for c in caras:
        e["faces"][c] = {"uv": (uvs or {}).get(c, [0, 0, 16, 16]), "texture": tex}
    if rot is not None: e["rotation"] = rot
    return e
el = []
# punta: cuadrado girado 45 grados con el centro en la base (y=6); solo se dibujan las dos caras de abajo y el frente/atras recortados
r = 5.5 / math.sqrt(2)
el.append(cubo((8 - r, 6 - r), (8 + r, 6 + r), "#punta", caras=('north', 'south', 'down', 'west'),
               rot={"angle": 45, "axis": "z", "origin": [8, 6, 8]}, uvs={"north": [16, 0, 0, 16]}))
el[-1]["faces"]["down"]["texture"] = "#color"; el[-1]["faces"]["west"]["texture"] = "#color"
el.append(cubo((2.5, 5.95), (13.5, 6.05), "#color"))                 # la base de la punta (tapa)
el.append(cubo((6.4, 5.5), (9.6, 8.9), "#color"))                    # cuerpo
el.append(cubo((5.9, 8.5), (10.1, 9.9), "#metal", 7.0, 9.0))         # collar
# aro: 16 pedazos
cx, cy, rin, rout = 8.0, 12.6, 2.35, 3.45
mid, ancho = (rin + rout) / 2, rout - rin
largo = 2 * mid * math.tan(math.pi / 16) * 1.12
for k in range(16):
    th = k * 22.5
    px, py = cx + mid * math.cos(math.radians(th)), cy + mid * math.sin(math.radians(th))
    n = round(th / 90.0); resto = th - 90 * n          # giro entre -45 y 45; si n es impar, se cambian largo y ancho
    w, h = (ancho, largo) if n % 2 == 0 else (largo, ancho)
    el.append(cubo((px - w / 2, py - h / 2), (px + w / 2, py + h / 2), "#color", 7.1, 8.9,
                   rot=None if resto == 0 else {"angle": resto, "axis": "z", "origin": [round(px, 4), round(py, 4), 8]}))
# centro: disco gris (octogono), hueco oscuro (octogono) y el punto de color
for rr, tex, z0, z1 in ((2.0, "#metal", 7.4, 8.6), (1.25, "#oscuro", 7.3, 8.7), (0.45, "#color", 7.2, 8.8)):
    for ang in (0, 45):
        a = rr / math.sqrt(2) * (1.0 if ang == 0 else 1.0)
        el.append(cubo((cx - a, cy - a), (cx + a, cy + a), tex, z0, z1,
                       rot=None if ang == 0 else {"angle": 45, "axis": "z", "origin": [cx, cy, 8]}))
base = {"textures": {"metal": "tod5:item/flecha_metal", "oscuro": "tod5:item/flecha_oscuro"}, "elements": el,
    "display": {"gui": {"rotation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]}, "fixed": {"scale": [1, 1, 1]},
                "ground": {"scale": [0.5, 0.5, 0.5]}, "thirdperson_righthand": {"scale": [0.4, 0.4, 0.4], "translation": [0, 2, 1]},
                "firstperson_righthand": {"scale": [0.5, 0.5, 0.5], "rotation": [0, -20, 0]}}}
json.dump(base, open(os.path.join(MOD, 'flecha_gravedad_base.json'), 'w'), indent=1)
for k in COLORES:
    m = {"parent": "tod5:item/flecha_gravedad_base", "textures": {"color": "tod5:item/flecha_" + k + "_color", "punta": "tod5:item/flecha_" + k + "_punta", "particle": "tod5:item/flecha_" + k + "_color"}}
    json.dump(m, open(os.path.join(MOD, 'flecha_' + k + '.json'), 'w'), indent=1)
    json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "tod5:item/flecha_" + k}}, open(os.path.join(MOD, 'flecha_' + k + '_icono.json'), 'w'), indent=1)
    # en el inventario: el dibujo plano; en el mundo y en la mano: el modelo 3D
    json.dump({"model": {"type": "minecraft:select", "property": "minecraft:display_context",
                         "cases": [{"when": "gui", "model": {"type": "minecraft:model", "model": "tod5:item/flecha_" + k + "_icono"}}],
                         "fallback": {"type": "minecraft:model", "model": "tod5:item/flecha_" + k}}},
              open(os.path.join(ITM, 'flecha_' + k + '.json'), 'w'))
print('listo')
