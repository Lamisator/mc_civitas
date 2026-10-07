#!/usr/bin/env python3
"""Generates Civitas' citizen skins and outfits, item and block art, models, blockstates, loot, recipe and mod icon.

Skins use the player layout (64x64). Base skins carry face, hair and skin; outfits are a separate cutout texture drawn
over them, one per job and condition: 0 fine, 1 plain, 2 worn and patched, 3 in rags (holes show the skin underneath).
"""
import colorsys
import json
import os
import random
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
A = os.path.join(ROOT, "src/main/resources/assets/civitas")
D = os.path.join(ROOT, "src/main/resources/data/civitas")
T = os.path.join(A, "textures")

# part: (u, v, w, h, d), overlay part
PARTS = {
    "head": ((0, 0, 8, 8, 8), (32, 0, 8, 8, 8)),
    "body": ((16, 16, 8, 12, 4), (16, 32, 8, 12, 4)),
    "rarm": ((40, 16, 4, 12, 4), (40, 32, 4, 12, 4)),
    "larm": ((32, 48, 4, 12, 4), (48, 48, 4, 12, 4)),
    "rleg": ((0, 16, 4, 12, 4), (0, 32, 4, 12, 4)),
    "lleg": ((16, 48, 4, 12, 4), (0, 48, 4, 12, 4)),
}


def faces(u, v, w, h, d):
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + d + w + d, v + d, w, h),
    }


def paint(img, part, overlay, fn):
    """fn(face, x, y, fw, fh) -> RGBA tuple or None for every pixel of a part."""
    box = PARTS[part][1 if overlay else 0]
    for face, (fx, fy, fw, fh) in faces(*box).items():
        for y in range(fh):
            for x in range(fw):
                c = fn(face, x, y, fw, fh)
                if c is not None:
                    img.putpixel((fx + x, fy + y), c if len(c) == 4 else c + (255,))


def mul(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3])


def mix(a, b, t):
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def sat(c, f):
    h, s, v = colorsys.rgb_to_hsv(*(x / 255 for x in c[:3]))
    r, g, b = colorsys.hsv_to_rgb(h, max(0, min(1, s * f)), v)
    return (int(r * 255), int(g * 255), int(b * 255))


# ------------------------------------------------------------------ base skins
TONES = [(250, 214, 184), (236, 192, 158), (222, 168, 130), (196, 138, 98), (156, 102, 68), (104, 68, 46)]
HAIRS = [(28, 24, 22), (66, 42, 28), (112, 74, 44), (214, 184, 112), (166, 72, 36), (150, 148, 146)]


def base_skin(i, female):
    tone = TONES[i % 6]
    hair = HAIRS[(i * 5 + (2 if female else 1)) % 6]
    rnd = random.Random(i * 31 + (7 if female else 3))
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    shade = mul(tone, 0.88)

    def head(face, x, y, fw, fh):
        c = tone
        if face == "top":
            return hair
        if face == "bottom":
            return shade
        long_hair = female
        if face == "back":
            return hair if y < (8 if long_hair else 4) else c
        if face in ("left", "right"):
            if y < (2 if not long_hair else 8):
                return hair
            if not long_hair and y < 4 and ((face == "right" and x == fw - 1) or (face == "left" and x == 0)):
                return hair  # sideburns
            return c
        # front: the face
        if y == 0 or (long_hair and y < 2):
            return hair
        if long_hair and (x == 0 or x == 7) and y < 6:
            return hair
        if y == 3 and x in (1, 2, 5, 6):
            return mul(hair, 0.8)  # brows
        if y == 4:
            if x in (1, 6):
                return (238, 238, 238)
            if x in (2, 5):
                return (60, 90, 140) if i % 3 == 0 else (80, 56, 38) if i % 3 == 1 else (70, 110, 80)
        if y == 5 and x in (3, 4):
            return mul(tone, 0.85)
        if y == 6 and x in (3, 4):
            return (170, 90, 80) if female else mul(tone, 0.7)
        if not female and i % 4 == 2 and y >= 6:
            return mix(tone, hair, 0.55)  # beard
        return c

    paint(img, "head", False, head)
    if female:
        paint(img, "head", True, lambda f, x, y, fw, fh: hair + (255,) if f == "back" and y < 7 or (f in ("left", "right") and y < 7 and rnd.random() < 0.6) else None)

    def limb(face, x, y, fw, fh):
        if face == "bottom":
            return shade
        return tone if y < fh else tone

    for p in ("body", "rarm", "larm"):
        paint(img, p, False, limb)
    # simple undergarments (show through torn clothes)
    under = (200, 196, 186)
    for p in ("rleg", "lleg"):
        paint(img, p, False, lambda f, x, y, fw, fh: under if (y < 4 and f != "bottom") else (shade if f == "bottom" else tone))
    if female:
        paint(img, "body", False, lambda f, x, y, fw, fh: under if f in ("front", "back", "left", "right") and 2 <= y <= 4 else None)
    return img


os.makedirs(os.path.join(T, "entity/citizen/base"), exist_ok=True)
os.makedirs(os.path.join(T, "entity/citizen/outfit"), exist_ok=True)
for i in range(12):
    base_skin(i, False).save(os.path.join(T, f"entity/citizen/base/m{i}.png"))
    base_skin(i, True).save(os.path.join(T, f"entity/citizen/base/f{i}.png"))

# ------------------------------------------------------------------ outfits
# job: shirt, sleeves ('long'/'short'), trousers, shoes, extras
JOBS = {
    "unemployed": dict(shirt=(122, 104, 84), sleeves="long", pants=(92, 92, 96), shoes=(70, 52, 38)),
    "builder": dict(shirt=(70, 110, 170), sleeves="short", pants=(48, 70, 120), shoes=(80, 60, 40), vest=(255, 138, 20), hat="hard", hatc=(250, 210, 30)),
    "farmer": dict(shirt=(178, 54, 48), check=(120, 30, 30), sleeves="long", pants=(70, 96, 150), shoes=(92, 64, 40), dungarees=(70, 96, 150), hat="straw", hatc=(226, 196, 112)),
    "baker": dict(shirt=(242, 242, 236), sleeves="long", pants=(196, 196, 190), shoes=(60, 60, 60), apron=(250, 250, 248), hat="toque", hatc=(252, 252, 252)),
    "butcher": dict(shirt=(238, 238, 232), sleeves="short", pants=(72, 72, 80), shoes=(50, 50, 50), apron=(200, 52, 52), stripes=(250, 250, 250), hat="cap", hatc=(240, 240, 240), blood=True),
    "blacksmith": dict(shirt=(64, 60, 58), sleeves="short", pants=(56, 50, 44), shoes=(40, 34, 30), apron=(118, 78, 46), soot=True),
    "miner": dict(shirt=(84, 84, 74), sleeves="long", pants=(70, 66, 58), shoes=(40, 36, 30), dungarees=(78, 70, 54), hat="helmet", hatc=(220, 180, 40), soot=True),
    "lumberjack": dict(shirt=(170, 34, 34), check=(30, 24, 24), sleeves="long", pants=(56, 78, 118), shoes=(96, 66, 40), hat="beanie", hatc=(40, 90, 60)),
    "banker": dict(shirt=(40, 42, 56), sleeves="long", pants=(36, 38, 50), shoes=(20, 20, 22), tie=(170, 30, 40), collar=(245, 245, 245)),
    "sheriff": dict(shirt=(170, 140, 96), sleeves="long", pants=(92, 74, 52), shoes=(70, 44, 26), badge=True, hat="brim", hatc=(110, 78, 48)),
    "factory_worker": dict(shirt=(96, 110, 90), sleeves="long", pants=(90, 104, 86), shoes=(40, 40, 40), dungarees=(96, 110, 90), goggles=True),
    "tavern_keeper": dict(shirt=(236, 232, 220), sleeves="long", pants=(70, 60, 50), shoes=(70, 50, 36), vest=(40, 100, 60), apron=(230, 226, 214)),
    "clerk": dict(shirt=(118, 92, 66), sleeves="long", pants=(80, 70, 60), shoes=(50, 36, 26), collar=(240, 240, 240), glasses=True),
    "prisoner": dict(shirt=(240, 240, 240), stripes=(30, 30, 30), sleeves="long", pants=(240, 240, 240), pantstripes=True, shoes=(60, 60, 60)),
}


def outfit(job, spec, tier):
    rnd = random.Random(sum(map(ord, job)) * 7 + tier)
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    shirt = spec["shirt"]
    pants = spec["pants"]
    shoes = spec["shoes"]
    if tier == 0:
        shirt = sat(shirt, 1.2)
        pants = sat(pants, 1.15)
        shoes = (24, 22, 22)

    def cloth(c, face, x, y):
        if spec.get("check") and (x // 2 + y // 2) % 2 == 0:
            return spec["check"]
        if spec.get("stripes") and job in ("prisoner",) and y % 2 == 0:
            return spec["stripes"]
        return c

    # torso
    def body(face, x, y, fw, fh):
        if face == "top" or face == "bottom":
            return cloth(shirt, face, x, y)
        c = cloth(shirt, face, x, y)
        if spec.get("dungarees") and y >= 5:
            c = spec["dungarees"]
        if spec.get("dungarees") and face in ("front", "back") and y < 5 and x in (2, 5):
            c = spec["dungarees"]
        if spec.get("apron") and face == "front" and y >= 3:
            c = spec["apron"]
            if spec.get("stripes") and job == "butcher" and x % 2 == 0:
                c = spec["stripes"]
        if spec.get("apron") and face == "front" and y < 3 and x in (3, 4) and job != "tavern_keeper":
            c = spec["apron"]
        if spec.get("collar") and face == "front" and y < 2 and x in (3, 4):
            c = spec["collar"]
        if spec.get("tie") and face == "front" and 1 <= y <= 6 and x in (3, 4) and not (y == 1 and x == 3):
            c = spec["tie"] if x == 4 or y > 1 else c
        if spec.get("badge") and face == "front" and y in (2, 3) and x in (5, 6):
            c = (238, 200, 60) if (x + y) % 2 else (200, 160, 40)
        if job == "banker" and face == "front" and 0 <= y < 7 and x in (2, 5) and False:
            c = shirt
        if tier == 0 and face == "front" and x == 3 and y in (3, 6, 9) and not spec.get("apron"):
            c = (232, 192, 64)  # gold buttons
        if face == "front" and y == 11:
            c = mul(c, 0.8)  # belt line
        return c

    paint(img, "body", False, body)
    if spec.get("vest"):
        paint(img, "body", True, lambda f, x, y, fw, fh: (spec["vest"] if f in ("front", "back", "left", "right") and y < 9 and not (f == "front" and x in (3, 4) and job == "builder") else None))

    # arms
    def arm(face, x, y, fw, fh):
        if face == "bottom":
            return None
        long = spec["sleeves"] == "long"
        if y < (12 if long else 4) or face == "top":
            c = cloth(shirt, face, x, y)
            if long and y == 11:
                c = mul(c, 0.9)
            return c
        if spec.get("soot") and y >= 9:
            return (44, 40, 38)  # gloves
        return None

    paint(img, "rarm", False, arm)
    paint(img, "larm", False, arm)

    # legs
    def leg(face, x, y, fw, fh):
        if face == "top":
            return pants
        if face == "bottom":
            return shoes
        if y >= 10:
            return shoes
        c = pants
        if spec.get("pantstripes") and y % 2 == 0:
            c = (30, 30, 30)
        if spec.get("apron") and face == "front" and y < 5 and job in ("baker", "butcher", "blacksmith", "tavern_keeper"):
            c = spec["apron"]
        return c

    paint(img, "rleg", False, leg)
    paint(img, "lleg", False, leg)

    # head gear
    hat = spec.get("hat")
    hc = spec.get("hatc", (80, 80, 80))
    if hat:
        def hat_fn(face, x, y, fw, fh):
            if face == "bottom":
                return None
            if hat == "toque":
                return hc if face == "top" or y < 4 else None
            if hat in ("hard", "helmet"):
                if face == "top" or y < 3:
                    return mul(hc, 1.0 if y else 1.1)
                if hat == "helmet" and face == "front" and y == 3 and x in (3, 4):
                    return (255, 250, 200)  # lamp
                return None
            if hat == "straw":
                if face == "top":
                    return hc if (x + y) % 3 else mul(hc, 0.85)
                return mul(hc, 0.9) if y < 2 else None
            if hat == "brim":
                if face == "top":
                    return hc
                if y < 2:
                    return mul(hc, 0.85) if y == 1 else hc
                return None
            if hat == "beanie":
                return hc if face == "top" or y < 3 else None
            if hat == "cap":
                return hc if face == "top" or y < 2 else None
            return None

        paint(img, "head", True, hat_fn)
    if spec.get("goggles"):
        paint(img, "head", True, lambda f, x, y, fw, fh: ((40, 40, 40) if y == 1 else None) if f != "top" and f != "bottom" else None)
        paint(img, "head", True, lambda f, x, y, fw, fh: (120, 180, 200) if f == "front" and y == 1 and x in (1, 2, 5, 6) else None)
    if spec.get("glasses"):
        paint(img, "head", False, lambda f, x, y, fw, fh: (30, 30, 30) if f == "front" and y == 4 and x in (0, 3, 4, 7) else None)

    # wear and tear
    px = img.load()
    regions = []
    for part in PARTS:
        for overlay in (False, True):
            for face, (fx, fy, fw, fh) in faces(*PARTS[part][1 if overlay else 0]).items():
                regions.append((part, overlay, face, fx, fy, fw, fh))
    for part, overlay, face, fx, fy, fw, fh in regions:
        for y in range(fh):
            for x in range(fw):
                c = px[fx + x, fy + y]
                if c[3] == 0:
                    continue
                rgb = c[:3]
                if spec.get("blood") and tier <= 2 and part == "body" and face == "front" and rnd.random() < 0.08:
                    rgb = (140, 20, 20)
                if spec.get("soot") and rnd.random() < 0.06 + tier * 0.04:
                    rgb = mul(rgb, 0.6)
                if tier == 2:
                    rgb = mul(sat(rgb, 0.65), 0.9)
                    if rnd.random() < 0.07:
                        rgb = mul(rgb, 0.7)  # stains
                elif tier == 3:
                    rgb = mul(sat(rgb, 0.4), 0.72)
                    if rnd.random() < 0.15:
                        rgb = mix(rgb, (70, 56, 40), 0.6)  # dirt
                px[fx + x, fy + y] = rgb + (255,)
    if tier >= 2:
        # patches
        for _ in range(3 if tier == 2 else 2):
            part = rnd.choice(["body", "rleg", "lleg", "rarm", "larm"])
            face = rnd.choice(["front", "back", "left", "right"])
            fx, fy, fw, fh = faces(*PARTS[part][0])[face]
            x0 = fx + rnd.randrange(max(1, fw - 2))
            y0 = fy + rnd.randrange(max(1, fh - 5))
            col = rnd.choice([(120, 96, 64), (92, 110, 70), (130, 70, 60), (90, 90, 110)])
            for dx in range(2):
                for dy in range(2):
                    if px[x0 + dx, y0 + dy][3]:
                        px[x0 + dx, y0 + dy] = mul(col, 0.9 if tier == 3 else 1.0) + (255,)
        # frayed hems
        for part in ("rarm", "larm", "rleg", "lleg"):
            for face in ("front", "back", "left", "right"):
                fx, fy, fw, fh = faces(*PARTS[part][0])[face]
                for x in range(fw):
                    for y in range(fh - 1, -1, -1):
                        if px[fx + x, fy + y][3]:
                            if rnd.random() < (0.35 if tier == 2 else 0.6):
                                px[fx + x, fy + y] = (0, 0, 0, 0)
                            break
    if tier == 3:
        # holes and tears: the skin shows through
        for part, overlay, face, fx, fy, fw, fh in regions:
            if part == "head":
                continue
            for y in range(fh):
                for x in range(fw):
                    if px[fx + x, fy + y][3] and rnd.random() < 0.12:
                        px[fx + x, fy + y] = (0, 0, 0, 0)
        # a long rip across the shirt
        fx, fy, fw, fh = faces(*PARTS["body"][0])["front"]
        x = rnd.randrange(2, 5)
        for y in range(2, 10):
            px[fx + min(fw - 1, x), fy + y] = (0, 0, 0, 0)
            x += rnd.choice([0, 0, 1])
        # trousers torn off below the knee, no shoes, no hat
        for part in ("rleg", "lleg"):
            for face in ("front", "back", "left", "right", "bottom"):
                fx, fy, fw, fh = faces(*PARTS[part][0])[face]
                for y in range(fh):
                    for x in range(fw):
                        if face == "bottom" or y >= 7 + (1 if rnd.random() < 0.3 else 0):
                            px[fx + x, fy + y] = (0, 0, 0, 0)
        for face, (fx, fy, fw, fh) in faces(*PARTS["head"][1]).items():
            for y in range(fh):
                for x in range(fw):
                    if rnd.random() < 0.75:
                        px[fx + x, fy + y] = (0, 0, 0, 0)
        # grime on the face
        fx, fy, fw, fh = faces(*PARTS["head"][0])["front"]
        for (x, y) in [(1, 5), (6, 6), (2, 6), (5, 2)]:
            if rnd.random() < 0.8:
                px[fx + x, fy + y] = (92, 74, 58, 255)
    return img


for job, spec in JOBS.items():
    for tier in range(4):
        outfit(job, spec, tier).save(os.path.join(T, f"entity/citizen/outfit/{job}_{tier}.png"))

# ------------------------------------------------------------------ items and blocks


def item_charter():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([3, 2, 12, 13], fill=(236, 222, 186))
    d.rectangle([2, 1, 13, 2], fill=(196, 170, 120))
    d.rectangle([2, 13, 13, 14], fill=(196, 170, 120))
    for y in (4, 6, 8):
        d.line([(5, y), (10, y)], fill=(120, 100, 80))
    d.ellipse([8, 9, 12, 13], fill=(176, 30, 36))
    img.putpixel((10, 11), (220, 80, 80, 255))
    d.line([(10, 13), (9, 15)], fill=(176, 30, 36))
    d.line([(11, 13), (12, 15)], fill=(176, 30, 36))
    return img


def item_ale():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([4, 4, 10, 14], fill=(214, 150, 40))
    d.rectangle([4, 2, 10, 5], fill=(250, 246, 236))
    d.rectangle([11, 6, 13, 11], outline=(170, 170, 176))
    d.rectangle([4, 4, 10, 14], outline=(170, 170, 176))
    d.line([(5, 7), (5, 13)], fill=(240, 190, 80))
    return img


os.makedirs(os.path.join(T, "item"), exist_ok=True)
item_charter().save(os.path.join(T, "item/town_charter.png"))
item_ale().save(os.path.join(T, "item/ale.png"))

os.makedirs(os.path.join(T, "block"), exist_ok=True)
book = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(book)
d.rectangle([0, 0, 15, 15], fill=(110, 40, 30))
d.rectangle([1, 1, 7, 14], fill=(240, 232, 210))
d.rectangle([8, 1, 14, 14], fill=(236, 226, 202))
for y in range(3, 13, 2):
    d.line([(2, y), (6, y)], fill=(130, 120, 110))
    d.line([(9, y), (13, y)], fill=(130, 120, 110))
d.line([(7, 1), (7, 14)], fill=(150, 130, 110))
book.save(os.path.join(T, "block/town_ledger_top.png"))
wood = Image.new("RGBA", (16, 16))
rnd = random.Random(5)
for y in range(16):
    for x in range(16):
        v = rnd.randint(-6, 6)
        wood.putpixel((x, y), (92 + v, 62 + v, 38 + v, 255))
ImageDraw.Draw(wood).rectangle([0, 0, 15, 15], outline=(64, 42, 24))
wood.save(os.path.join(T, "block/town_ledger_side.png"))


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


write(os.path.join(A, "models/block/town_ledger.json"), {
    "parent": "minecraft:block/block",
    "textures": {"particle": "civitas:block/town_ledger_side", "side": "civitas:block/town_ledger_side", "top": "civitas:block/town_ledger_top"},
    "elements": [
        {"from": [4, 0, 4], "to": [12, 2, 12], "faces": {f: {"texture": "#side"} for f in ["north", "south", "east", "west", "up", "down"]}},
        {"from": [6, 2, 6], "to": [10, 9, 10], "faces": {f: {"texture": "#side"} for f in ["north", "south", "east", "west"]}},
        {"from": [1, 9, 2], "to": [15, 11, 14], "rotation": {"origin": [8, 10, 8], "axis": "x", "angle": -22.5},
         "faces": {"up": {"texture": "#top"}, "north": {"texture": "#side"}, "south": {"texture": "#side"}, "east": {"texture": "#side"},
                   "west": {"texture": "#side"}, "down": {"texture": "#side"}}},
    ]})
write(os.path.join(A, "blockstates/town_ledger.json"), {"variants": {
    f"facing={f}": ({"model": "civitas:block/town_ledger"} | ({"y": r} if r else {}))
    for f, r in [("north", 0), ("east", 90), ("south", 180), ("west", 270)]}})
write(os.path.join(A, "items/town_ledger.json"), {"model": {"type": "minecraft:model", "model": "civitas:block/town_ledger"}})
for it in ["town_charter", "ale"]:
    write(os.path.join(A, f"models/item/{it}.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"civitas:item/{it}"}})
    write(os.path.join(A, f"items/{it}.json"), {"model": {"type": "minecraft:model", "model": f"civitas:item/{it}"}})
write(os.path.join(D, "loot_table/blocks/town_ledger.json"), {
    "type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "civitas:town_ledger"}],
                                          "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
write(os.path.join(D, "recipe/town_charter.json"), {
    "type": "minecraft:crafting_shaped", "category": "misc", "pattern": ["PPP", "GBG", "PFP"],
    "key": {"P": "minecraft:paper", "G": "minecraft:gold_ingot", "B": "minecraft:writable_book", "F": "minecraft:feather"},
    "result": {"id": "civitas:town_charter", "count": 1}})
write(os.path.join(D, "recipe/town_ledger.json"), {
    "type": "minecraft:crafting_shapeless", "category": "misc", "ingredients": ["minecraft:lectern", "minecraft:writable_book"],
    "result": {"id": "civitas:town_ledger", "count": 1}})

# ------------------------------------------------------------------ icon: a little timber-framed house with a crowd
icon = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
d = ImageDraw.Draw(icon)
d.rounded_rectangle([4, 4, 123, 123], 18, fill=(30, 26, 22, 255), outline=(90, 70, 50, 255), width=3)
d.polygon([(24, 62), (64, 26), (104, 62)], fill=(150, 50, 40))
d.rectangle([30, 62, 98, 104], fill=(238, 232, 218))
for x in (30, 52, 76, 96):
    d.rectangle([x, 62, x + 2, 104], fill=(70, 46, 30))
d.rectangle([30, 80, 98, 82], fill=(70, 46, 30))
d.rectangle([58, 86, 70, 104], fill=(90, 60, 36))
d.rectangle([36, 68, 46, 76], fill=(140, 190, 230))
d.rectangle([82, 68, 92, 76], fill=(140, 190, 230))
for i, x in enumerate([22, 40, 88, 106]):
    col = [(200, 60, 50), (60, 110, 180), (230, 200, 60), (70, 140, 80)][i]
    d.ellipse([x - 5, 96, x + 5, 106], fill=(230, 190, 150))
    d.rectangle([x - 5, 106, x + 5, 118], fill=col)
icon.save(os.path.join(A, "icon.png"))
print("assets written")
