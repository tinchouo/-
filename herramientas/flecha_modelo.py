# Genera el modelo de la flecha de gravedad (verde y roja): una lamina finita con la forma dibujada en la textura (lisa, sin bloques).
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

Z0, Z1 = 7.6, 8.4   # grosor: una lamina
def cara(uv): return {"uv": uv, "texture": "#color"}
lado = [7.9, 7.4, 8.1, 7.6]   # un punto del cuerpo (color liso) para los costados
base = {"textures": {}, "elements": [{
    "from": [0, 0, Z0], "to": [16, 16, Z1],
    "faces": {"north": cara([0, 0, 16, 16]), "south": cara([0, 0, 16, 16]),
              "east": cara(lado), "west": cara(lado), "up": cara(lado), "down": cara(lado)}}],
    "display": {"gui": {"rotation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]}, "fixed": {"scale": [1, 1, 1]},
                "ground": {"scale": [0.5, 0.5, 0.5]}, "thirdperson_righthand": {"scale": [0.4, 0.4, 0.4], "translation": [0, 2, 1]},
                "firstperson_righthand": {"scale": [0.5, 0.5, 0.5], "rotation": [0, -20, 0]}}}
json.dump(base, open(os.path.join(MOD, 'flecha_gravedad_base.json'), 'w'), indent=1)
for k in COLORES:
    m = {"parent": "tod5:item/flecha_gravedad_base", "textures": {"color": "tod5:item/flecha_" + k, "particle": "tod5:item/flecha_" + k}}
    json.dump(m, open(os.path.join(MOD, 'flecha_' + k + '.json'), 'w'), indent=1)
    json.dump({"model": {"type": "minecraft:model", "model": "tod5:item/flecha_" + k}}, open(os.path.join(ITM, 'flecha_' + k + '.json'), 'w'))
print('listo')
