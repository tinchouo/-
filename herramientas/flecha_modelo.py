# Genera el modelo 3D de la flecha de gravedad (verde y roja) y sus texturas.
import json, math, os, sys
from PIL import Image
R = sys.argv[1]  # src/client/resources/assets/tod5
TEX = os.path.join(R, 'textures/item'); MOD = os.path.join(R, 'models/item'); ITM = os.path.join(R, 'items')
for d in (TEX, MOD, ITM): os.makedirs(d, exist_ok=True)

def tex(nombre, base, luz, sombra):
    im = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            t = y / 15
            c = [int(luz[i] * (1 - t) + sombra[i] * t) for i in range(3)]
            if x in (0, 15) or y in (0, 15): c = [int(v * 0.8) for v in c]
            if 3 <= x <= 5 and 2 <= y <= 12: c = [min(255, int(v * 1.12)) for v in c]   # brillo
            im.putpixel((x, y), (*c, 255))
    im.save(os.path.join(TEX, nombre + '.png'))

COLORES = {
    'verde': ((70, 220, 40), (150, 250, 110), (40, 150, 25)),
    'roja':  ((235, 40, 100), (255, 120, 160), (170, 20, 70)),
}
for k, (b, l, s) in COLORES.items(): tex('flecha_' + k, b, l, s)
tex('flecha_metal', (60, 60, 64), (95, 95, 100), (35, 35, 38))
tex('flecha_disco', (120, 122, 126), (165, 167, 170), (85, 86, 90))
tex('flecha_hueco', (25, 25, 28), (40, 40, 44), (15, 15, 18))

Z0, Z1 = 6.5, 9.5   # grosor
def caja(x0, y0, x1, y1, t, z0=Z0, z1=Z1, rot=None):
    caras = {f: {"uv": [0, 0, 16, 16], "texture": t} for f in ("north", "south", "east", "west", "up", "down")}
    e = {"from": [x0, y0, z0], "to": [x1, y1, z1], "faces": caras}
    if rot: e["rotation"] = rot
    return e

el = []
# punta: un triangulo hecho con franjas finas (abajo la punta, arriba lo ancho)
ALTO, ANCHO = 6.0, 10.0
N = 24
for i in range(N):
    y0 = ALTO * i / N; y1 = ALTO * (i + 1) / N
    w = ANCHO * (i + 1) / N
    el.append(caja(8 - w / 2, y0, 8 + w / 2, y1, "#color"))
# cuerpo
el.append(caja(6.4, ALTO - 0.01, 9.6, 8.6, "#color"))
# collar oscuro
el.append(caja(6.0, 8.6, 10.0, 9.8, "#metal", Z0 - 0.3, Z1 + 0.3))
# aro: segmentos alrededor del centro
CX, CY, RE, RI = 8.0, 12.6, 3.4, 2.3
SEG = 20
for i in range(SEG):
    a = 360 * i / SEG
    largo = 2 * math.pi * RE / SEG + 0.35
    # segmento horizontal a la derecha del centro, rotado alrededor del centro
    # (las rotaciones de los modelos admiten angulos libres desde 1.21.6 en pasos chicos; uso multiplos de 22.5 y 0 por las dudas)
    pass
# el aro con angulos de 22.5: 16 segmentos
for i in range(16):
    a = -45 + 22.5 * (i % 4)
    cuad = i // 4
    # 4 cuadrantes: cada uno es un segmento en el borde, rotado
    ang = math.radians(22.5 * i)
    mx, my = CX + (RE + RI) / 2 * math.cos(ang), CY + (RE + RI) / 2 * math.sin(ang)
    largo = 2 * math.pi * RE / 16 + 0.4
    grosor = RE - RI
    rot_deg = (math.degrees(ang) + 90) % 180
    if rot_deg > 90: rot_deg -= 180
    # Minecraft solo deja -45..45: si el angulo se pasa, giro el segmento 90 (cambio largo por grosor)
    w, h = largo, grosor
    if rot_deg > 45: rot_deg -= 90; w, h = h, w
    elif rot_deg < -45: rot_deg += 90; w, h = h, w
    el.append(caja(mx - w / 2, my - h / 2, mx + w / 2, my + h / 2, "#color", Z0 - 0.2, Z1 + 0.2,
                   {"origin": [mx, my, 8], "axis": "z", "angle": round(rot_deg / 22.5) * 22.5}))
# disco gris, hueco oscuro y punto del color
el.append(caja(CX - 2.4, CY - 2.4, CX + 2.4, CY + 2.4, "#disco", Z0 + 0.1, Z1 - 0.1, {"origin": [CX, CY, 8], "axis": "z", "angle": 0}))
el.append(caja(CX - 2.4, CY - 2.4, CX + 2.4, CY + 2.4, "#disco", Z0 + 0.1, Z1 - 0.1, {"origin": [CX, CY, 8], "axis": "z", "angle": 45}))
for ang in (0, 45): el.append(caja(CX - 1.25, CY - 1.25, CX + 1.25, CY + 1.25, "#hueco", Z0 - 0.05, Z1 + 0.05, {"origin": [CX, CY, 8], "axis": "z", "angle": ang}))
el.append(caja(CX - 0.45, CY - 0.45, CX + 0.45, CY + 0.45, "#color", Z0 - 0.15, Z1 + 0.15))

base = {"textures": {"metal": "tod5:item/flecha_metal", "disco": "tod5:item/flecha_disco", "hueco": "tod5:item/flecha_hueco"},
        "elements": el,
        "display": {"gui": {"rotation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]}, "fixed": {"scale": [1, 1, 1]},
                    "ground": {"scale": [0.5, 0.5, 0.5]}, "thirdperson_righthand": {"scale": [0.4, 0.4, 0.4], "translation": [0, 2, 1]},
                    "firstperson_righthand": {"scale": [0.5, 0.5, 0.5], "rotation": [0, -20, 0]}}}
json.dump(base, open(os.path.join(MOD, 'flecha_gravedad_base.json'), 'w'), indent=1)
for k in COLORES:
    m = {"parent": "tod5:item/flecha_gravedad_base", "textures": {"color": "tod5:item/flecha_" + k, "particle": "tod5:item/flecha_" + k}}
    json.dump(m, open(os.path.join(MOD, 'flecha_' + k + '.json'), 'w'), indent=1)
    json.dump({"model": {"type": "minecraft:model", "model": "tod5:item/flecha_" + k}}, open(os.path.join(ITM, 'flecha_' + k + '.json'), 'w'))
print(len(el), 'elementos')
