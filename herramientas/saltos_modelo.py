# Genera las orbes, los pads (amarillo y rojo) y la varita.
#  - orbe: un dibujo plano liso (bola con brillo + aro) que siempre mira a la camara (en el server: billboard CENTER), asi es un circulo desde cualquier lado
#  - pad: un disco 3D bajito (16 lados, en dos escalones), del ancho de un bloque, en el piso
#  - varita: verde, en la mano como un palo
import json, math, os, sys
from PIL import Image, ImageDraw
R = sys.argv[1]
TEX = os.path.join(R, 'textures/item'); MOD = os.path.join(R, 'models/item'); ITM = os.path.join(R, 'items')
COL = {'amarillo': ((255, 214, 40), (255, 245, 170), (200, 140, 0)), 'rojo': ((240, 50, 80), (255, 170, 185), (150, 10, 40))}
N, SS = 128, 4
def orbe(nombre, base, luz, sombra, bola, aro_in, aro_out):
    S = N * SS; im = Image.new('RGBA', (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(im); c = S / 2
    # aro
    d.ellipse([c - aro_out * SS, c - aro_out * SS, c + aro_out * SS, c + aro_out * SS], fill=luz + (255,))
    d.ellipse([c - aro_in * SS, c - aro_in * SS, c + aro_in * SS, c + aro_in * SS], fill=(0, 0, 0, 0))
    # bola con degrade (de la sombra al centro claro, corrido arriba a la izquierda)
    pasos = 40
    for i in range(pasos):
        t = i / (pasos - 1); r = bola * SS * (1 - t * 0.85)
        ox, oy = -t * bola * SS * 0.25, -t * bola * SS * 0.25
        col = tuple(int(sombra[k] * (1 - t) + base[k] * t if t < 0.6 else base[k] * (1 - (t - 0.6) / 0.4) + luz[k] * ((t - 0.6) / 0.4)) for k in range(3))
        d.ellipse([c + ox - r, c + oy - r, c + ox + r, c + oy + r], fill=col + (255,))
    br = bola * SS * 0.18; bx, by = c - bola * SS * 0.38, c - bola * SS * 0.38
    d.ellipse([bx - br, by - br, bx + br, by + br], fill=(255, 255, 255, 230))
    im.resize((N, N), Image.LANCZOS).save(os.path.join(TEX, nombre + '.png'))
def solido(nombre, rgb): Image.new('RGBA', (16, 16), tuple(rgb) + (255,)).save(os.path.join(TEX, nombre + '.png'))

for k, (base, luz, sombra) in COL.items():
    if k == 'amarillo': orbe('orbe_' + k, base, luz, sombra, 36, 52, 62)
    else: orbe('orbe_' + k, base, luz, sombra, 38, 45, 63)
    solido('pad_' + k, base); solido('pad_' + k + '_claro', luz)

def item(nombre, modelo): json.dump({"model": {"type": "minecraft:model", "model": modelo}}, open(os.path.join(ITM, nombre + '.json'), 'w'))
# orbe: una lamina
for k in COL:
    m = {"textures": {"t": "tod5:item/orbe_" + k, "particle": "tod5:item/orbe_" + k},
         "elements": [{"from": [0, 0, 7.99], "to": [16, 16, 8.01], "shade": False,
                       "faces": {"north": {"uv": [16, 0, 0, 16], "texture": "#t"}, "south": {"uv": [0, 0, 16, 16], "texture": "#t"}}}],
         "display": {"fixed": {"scale": [1, 1, 1]}, "gui": {"scale": [1, 1, 1]}}}
    json.dump(m, open(os.path.join(MOD, 'orbe_' + k + '.json'), 'w'), indent=1)
    item('orbe_' + k, 'tod5:item/orbe_' + k)
# pad: discos de 16 lados (4 cuadrados girados 0, 22.5, 45, -22.5 alrededor del eje Y)
def disco(a, y0, y1, tex):
    el = []
    for ang in (0, 22.5, 45, -22.5):
        e = {"from": [8 - a, y0, 8 - a], "to": [8 + a, y1, 8 + a], "faces": {f: {"uv": [0, 0, 16, 16], "texture": tex} for f in ("north", "south", "east", "west", "up", "down")}}
        if ang: e["rotation"] = {"angle": ang, "axis": "y", "origin": [8, 8, 8]}
        el.append(e)
    return el
for k in COL:
    m = {"textures": {"c": "tod5:item/pad_" + k, "l": "tod5:item/pad_" + k + "_claro", "particle": "tod5:item/pad_" + k},
         "elements": disco(6.6, 0, 1.5, "#c") + disco(4.6, 1.5, 2.6, "#c") + disco(2.8, 2.6, 3.2, "#l"),
         "display": {"fixed": {"scale": [1, 1, 1]}, "gui": {"rotation": [30, 45, 0], "scale": [0.9, 0.9, 0.9], "translation": [0, 3, 0]}}}
    json.dump(m, open(os.path.join(MOD, 'pad_' + k + '.json'), 'w'), indent=1)
    item('pad_' + k, 'tod5:item/pad_' + k)
# varita (pixel art 16x16)
im = Image.new('RGBA', (16, 16), (0, 0, 0, 0)); px = im.load()
oscuro, medio, claro = (20, 90, 40, 255), (40, 160, 70, 255), (120, 230, 140, 255)
for i in range(10):
    x, y = 2 + i, 13 - i
    px[x, y] = medio; px[x + 1, y] = oscuro if i % 2 else medio; px[x, y - 1] = claro if i > 6 else medio
for (x, y) in [(12, 2), (13, 2), (12, 3), (13, 3), (11, 3), (14, 2), (12, 1), (13, 4)]: px[x, y] = (90, 255, 120, 255)
px[12, 2] = (230, 255, 235, 255)
im.save(os.path.join(TEX, 'varita.png'))
json.dump({"parent": "minecraft:item/handheld", "textures": {"layer0": "tod5:item/varita"}}, open(os.path.join(MOD, 'varita.json'), 'w'), indent=1)
item('varita', 'tod5:item/varita')
print('listo')
