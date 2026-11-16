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
def suave(nombre, dibujar):
    S = N * SS; im = Image.new('RGBA', (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(im); dibujar(d, S / 2)
    im.resize((N, N), Image.LANCZOS).save(os.path.join(TEX, nombre + '.png'))
def circ(d, c, r, col): d.ellipse([c - r * SS, c - r * SS, c + r * SS, c + r * SS], fill=col)
def orbe(nombre, centro, borde, bola):
    # como en Geometry Dash: aro blanco afuera, un espacio vacio, borde blanco y la bola con degrade (centro claro -> borde del color)
    def dib(d, c):
        circ(d, c, 63, (255, 255, 255, 255)); circ(d, c, 57, (0, 0, 0, 0))
        circ(d, c, bola + 4, (255, 255, 255, 255))
        pasos = 48
        for i in range(pasos):
            t = i / (pasos - 1)
            col = tuple(int(borde[k] * (1 - t) + centro[k] * t) for k in range(3))
            circ(d, c, bola * (1 - t * 0.97), col + (255,))
    suave(nombre, dib)
def solido(nombre, rgb): Image.new('RGBA', (16, 16), tuple(rgb) + (255,)).save(os.path.join(TEX, nombre + '.png'))

orbe('orbe_amarillo', (255, 252, 225), (250, 232, 110), 44)
orbe('orbe_rojo', (255, 190, 40), (240, 40, 30), 44)
# el aro del "golpe" (blanco, se agranda y se desvanece) y la chispa (puntito suave)
suave('orbe_aro', lambda d, c: (circ(d, c, 62, (255, 255, 255, 255)), circ(d, c, 55, (0, 0, 0, 0))))
im = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
for y in range(32):
    for x in range(32):
        r = math.hypot(x - 15.5, y - 15.5) / 15.5
        if r < 1: im.putpixel((x, y), (255, 255, 255, int(255 * max(0, 1 - r) ** 1.4)))
im.save(os.path.join(TEX, 'chispa.png'))
PAD = {'amarillo': ((250, 222, 60), (255, 246, 170)), 'rojo': ((235, 50, 40), (255, 150, 110))}
for k, (base, luz) in PAD.items():
    solido('pad_' + k, base); solido('pad_' + k + '_claro', luz)
solido('pad_borde', (255, 255, 255))

def item(nombre, modelo): json.dump({"model": {"type": "minecraft:model", "model": modelo}}, open(os.path.join(ITM, nombre + '.json'), 'w'))
# orbe: una lamina
for k in COL:
    m = {"textures": {"t": "tod5:item/orbe_" + k, "particle": "tod5:item/orbe_" + k},
         "elements": [{"from": [0, 0, 7.99], "to": [16, 16, 8.01], "shade": False,
                       "faces": {"north": {"uv": [16, 0, 0, 16], "texture": "#t"}, "south": {"uv": [0, 0, 16, 16], "texture": "#t"}}}],
         "display": {"fixed": {"scale": [1, 1, 1]}, "gui": {"scale": [1, 1, 1]}}}
    json.dump(m, open(os.path.join(MOD, 'orbe_' + k + '.json'), 'w'), indent=1)
    item('orbe_' + k, 'tod5:item/orbe_' + k)
# pad: una cupula baja y redonda (media lente, como en Geometry Dash) con el borde blanco abajo.
# 16 lados (4 cuadrados girados 0, 22.5, 45, -22.5 alrededor del eje Y) y escalones finitos (no se notan) siguiendo una elipse.
def disco(a, y0, y1, tex):
    el = []
    for ang in (0, 22.5, 45, -22.5):
        e = {"from": [round(8 - a, 3), round(y0, 3), round(8 - a, 3)], "to": [round(8 + a, 3), round(y1, 3), round(8 + a, 3)],
             "faces": {f: {"uv": [0, 0, 16, 16], "texture": tex} for f in ("north", "south", "east", "west", "up", "down")}}
        if ang: e["rotation"] = {"angle": ang, "axis": "y", "origin": [8, 8, 8]}
        el.append(e)
    return el
for k in PAD:
    el = disco(7.1, 0, 0.55, "#b")   # borde blanco
    capas, alto, radio = 8, 3.2, 6.7
    for i in range(capas):
        y0 = 0.5 + alto * i / capas; y1 = 0.5 + alto * (i + 1) / capas
        a = radio * math.sqrt(max(0.0, 1 - ((y1 - 0.5) / (alto + 0.05)) ** 2)) + 0.25
        el += disco(a, y0, y1, "#l" if i >= capas - 3 else "#c")
    m = {"textures": {"c": "tod5:item/pad_" + k, "l": "tod5:item/pad_" + k + "_claro", "b": "tod5:item/pad_borde", "particle": "tod5:item/pad_" + k},
         "elements": el,
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
