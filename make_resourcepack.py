import os
import json
import zipfile
from PIL import Image, ImageDraw

PACK_DIR = '/root/coding/relics/resourcepack'
ZIP_PATH = '/root/coding/relics/Relics-ResourcePack.zip'

os.makedirs(f'{PACK_DIR}/assets/minecraft/models/item', exist_ok=True)
os.makedirs(f'{PACK_DIR}/assets/minecraft/items', exist_ok=True)
os.makedirs(f'{PACK_DIR}/assets/relics/models/item', exist_ok=True)
os.makedirs(f'{PACK_DIR}/assets/relics/textures/item', exist_ok=True)

# 1. pack.mcmeta
pack_mcmeta = {
    "pack": {
        "pack_format": 46,
        "supported_formats": {
            "min_inclusive": 15,
            "max_inclusive": 60
        },
        "description": "\u00a76\u00a7lRelics SMP\u00a7r - Offizielles Dummy Pack\n\u00a77Minecraft 26.2 / 1.21.x"
    }
}

with open(f'{PACK_DIR}/pack.mcmeta', 'w', encoding='utf-8') as f:
    json.dump(pack_mcmeta, f, indent=2, ensure_ascii=False)

# 2. Helpers for strictly binary alpha textures
def create_empty_16x16():
    return Image.new('RGBA', (16, 16), (0, 0, 0, 0))

def sanitize_and_save(img: Image.Image, filepath: str):
    img = img.convert('RGBA')
    width, height = img.size
    pixels = img.load()
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            if a < 128:
                pixels[x, y] = (0, 0, 0, 0)
            else:
                pixels[x, y] = (r, g, b, 255)
    img.save(filepath, 'PNG')
    print(f"Saved: {filepath}")

# 3. Create dummy textures
# A. sword_of_fire.png (16x16)
sword_img = create_empty_16x16()
spx = sword_img.load()
spx[1, 14] = (100, 50, 20, 255)
spx[2, 13] = (140, 70, 30, 255)
spx[3, 12] = (100, 50, 20, 255)
spx[2, 11] = (255, 170, 0, 255)
spx[3, 11] = (255, 220, 0, 255)
spx[4, 11] = (255, 120, 0, 255)
spx[4, 12] = (255, 170, 0, 255)
spx[4, 13] = (200, 80, 0, 255)
blade_coords = [
    (4, 10, (255, 100, 0)), (5, 9, (255, 140, 0)), (6, 8, (255, 180, 0)),
    (7, 7, (255, 220, 0)), (8, 6, (255, 240, 50)), (9, 5, (255, 240, 100)),
    (10, 4, (255, 200, 0)), (11, 3, (255, 150, 0)), (12, 2, (255, 80, 0)),
    (13, 1, (255, 50, 0)), (14, 0, (255, 255, 180)),
    (5, 10, (200, 40, 0)), (6, 9, (255, 80, 0)), (7, 8, (255, 120, 0)),
    (8, 7, (255, 160, 0)), (9, 6, (255, 180, 0)), (10, 5, (255, 140, 0)),
    (11, 4, (255, 90, 0)), (12, 3, (200, 40, 0)), (13, 2, (180, 20, 0)),
    (4, 9, (255, 220, 100)), (5, 8, (255, 255, 150)), (6, 7, (255, 255, 200)),
    (7, 6, (255, 255, 220)), (8, 5, (255, 255, 180)), (9, 4, (255, 220, 100)),
    (10, 3, (255, 180, 50)), (11, 2, (255, 120, 0)), (12, 1, (255, 80, 0))
]
for x, y, col in blade_coords:
    spx[x, y] = (col[0], col[1], col[2], 255)
sanitize_and_save(sword_img, f'{PACK_DIR}/assets/relics/textures/item/sword_of_fire.png')

# B. pickaxe_of_thor.png (16x16)
pick_img = create_empty_16x16()
ppx = pick_img.load()
for i in range(1, 11):
    ppx[i, 15 - i] = (80, 55, 35, 255)
    if i % 2 == 0:
        ppx[i, 15 - i] = (120, 85, 55, 255)
pick_head = [
    (8, 2, (0, 220, 255)), (9, 2, (0, 255, 255)), (10, 2, (150, 255, 255)), (11, 3, (0, 200, 255)), (12, 4, (0, 150, 255)), (13, 5, (0, 100, 220)),
    (7, 3, (0, 200, 255)), (8, 3, (255, 255, 100)), (9, 3, (255, 255, 255)), (10, 3, (0, 220, 255)), (11, 4, (0, 160, 255)),
    (6, 4, (0, 160, 255)), (7, 4, (0, 220, 255)), (8, 4, (255, 220, 50)), (9, 4, (0, 200, 255)),
    (5, 5, (0, 120, 220)), (6, 5, (0, 180, 255)), (7, 5, (0, 150, 255)),
    (4, 6, (0, 80, 180)), (5, 6, (0, 120, 220)),
    (13, 2, (255, 255, 0)), (14, 1, (255, 255, 200)), (3, 7, (255, 255, 0)), (2, 8, (255, 255, 200))
]
for x, y, col in pick_head:
    ppx[x, y] = (col[0], col[1], col[2], 255)
sanitize_and_save(pick_img, f'{PACK_DIR}/assets/relics/textures/item/pickaxe_of_thor.png')

# C. shield_of_atlas.png (32x32)
shield_img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
shpx = shield_img.load()
for x in range(6, 26):
    for y in range(4, 28):
        if (x in (6, 25) and y in (4, 5, 26, 27)) or (x in (7, 24) and y in (4, 27)):
            continue
        if x in (6, 7, 24, 25) or y in (4, 5, 26, 27):
            shpx[x, y] = (255, 200, 50, 255)
        else:
            if 12 <= x <= 19 and 10 <= y <= 21:
                shpx[x, y] = (50, 140, 220, 255)
            else:
                shpx[x, y] = (160, 90, 40, 255)
for x in range(14, 18):
    for y in range(14, 18):
        shpx[x, y] = (255, 255, 200, 255)
sanitize_and_save(shield_img, f'{PACK_DIR}/assets/relics/textures/item/shield_of_atlas.png')

# D. bows
def make_bow(bow_color, string_color, pull_state=0):
    b = create_empty_16x16()
    bx = b.load()
    curve = [
        (1, 13), (2, 11), (3, 9), (4, 7), (6, 5), (8, 4), (10, 3), (12, 2), (14, 1)
    ]
    for x, y in curve:
        bx[x, y] = bow_color
        if x+1 < 16 and y+1 < 16:
            bx[x+1, y] = (bow_color[0]//2, bow_color[1]//2, bow_color[2]//2, 255)
    if pull_state == 0:
        string_pixels = [(2, 13), (4, 11), (7, 8), (10, 5), (13, 2)]
    elif pull_state == 1:
        string_pixels = [(2, 13), (4, 12), (6, 10), (7, 9), (9, 7), (11, 5), (13, 2)]
    else:
        string_pixels = [(2, 13), (3, 13), (5, 11), (6, 11), (8, 9), (10, 7), (12, 4), (13, 2)]
    for x, y in string_pixels:
        if bx[x, y][3] == 0:
            bx[x, y] = string_color
    return b

artemis_wood = (40, 200, 100, 255)
artemis_string = (255, 255, 120, 255)
sanitize_and_save(make_bow(artemis_wood, artemis_string, 0), f'{PACK_DIR}/assets/relics/textures/item/bow_of_artemis.png')
sanitize_and_save(make_bow(artemis_wood, artemis_string, 0), f'{PACK_DIR}/assets/relics/textures/item/bow_of_artemis_pulling_0.png')
sanitize_and_save(make_bow(artemis_wood, artemis_string, 1), f'{PACK_DIR}/assets/relics/textures/item/bow_of_artemis_pulling_1.png')
sanitize_and_save(make_bow(artemis_wood, artemis_string, 2), f'{PACK_DIR}/assets/relics/textures/item/bow_of_artemis_pulling_2.png')

runan_wood = (0, 230, 255, 255)
runan_string = (255, 100, 255, 255)
sanitize_and_save(make_bow(runan_wood, runan_string, 0), f'{PACK_DIR}/assets/relics/textures/item/runans_hurricane.png')
sanitize_and_save(make_bow(runan_wood, runan_string, 0), f'{PACK_DIR}/assets/relics/textures/item/runans_hurricane_pulling_0.png')
sanitize_and_save(make_bow(runan_wood, runan_string, 1), f'{PACK_DIR}/assets/relics/textures/item/runans_hurricane_pulling_1.png')
sanitize_and_save(make_bow(runan_wood, runan_string, 2), f'{PACK_DIR}/assets/relics/textures/item/runans_hurricane_pulling_2.png')

# E. pack.png (64x64)
pack_icon = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
draw = ImageDraw.Draw(pack_icon)
draw.rounded_rectangle([4, 4, 60, 60], radius=8, fill=(20, 25, 35, 255), outline=(255, 180, 0, 255), width=3)
draw.polygon([(32, 14), (37, 26), (50, 28), (40, 37), (43, 50), (32, 43), (21, 50), (24, 37), (14, 28), (27, 26)], fill=(255, 215, 0, 255), outline=(255, 255, 150, 255))
sanitize_and_save(pack_icon, f'{PACK_DIR}/pack.png')

# 4. Write Model JSONs for relics
def write_json(path, data):
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2)

write_json(f'{PACK_DIR}/assets/relics/models/item/sword_of_fire.json', {
    "parent": "minecraft:item/handheld",
    "textures": { "layer0": "relics:item/sword_of_fire" }
})

write_json(f'{PACK_DIR}/assets/relics/models/item/pickaxe_of_thor.json', {
    "parent": "minecraft:item/handheld",
    "textures": { "layer0": "relics:item/pickaxe_of_thor" }
})

write_json(f'{PACK_DIR}/assets/relics/models/item/shield_of_atlas.json', {
    "parent": "minecraft:item/shield",
    "textures": { "layer0": "relics:item/shield_of_atlas" }
})

write_json(f'{PACK_DIR}/assets/relics/models/item/shield_of_atlas_blocking.json', {
    "parent": "minecraft:item/shield_blocking",
    "textures": { "layer0": "relics:item/shield_of_atlas" }
})

for name in ['bow_of_artemis', 'runans_hurricane']:
    write_json(f'{PACK_DIR}/assets/relics/models/item/{name}.json', {
        "parent": "minecraft:item/bow",
        "textures": { "layer0": f"relics:item/{name}" }
    })
    for p in [0, 1, 2]:
        write_json(f'{PACK_DIR}/assets/relics/models/item/{name}_pulling_{p}.json', {
            "parent": f"relics:item/{name}",
            "textures": { "layer0": f"relics:item/{name}_pulling_{p}" }
        })

# 5. Overrides in minecraft/models/item/
write_json(f'{PACK_DIR}/assets/minecraft/models/item/netherite_sword.json', {
    "parent": "minecraft:item/handheld",
    "textures": { "layer0": "minecraft:item/netherite_sword" },
    "overrides": [
        { "predicate": { "custom_model_data": 1001 }, "model": "relics:item/sword_of_fire" }
    ]
})

write_json(f'{PACK_DIR}/assets/minecraft/models/item/netherite_pickaxe.json', {
    "parent": "minecraft:item/handheld",
    "textures": { "layer0": "minecraft:item/netherite_pickaxe" },
    "overrides": [
        { "predicate": { "custom_model_data": 1002 }, "model": "relics:item/pickaxe_of_thor" }
    ]
})

write_json(f'{PACK_DIR}/assets/minecraft/models/item/shield.json', {
    "parent": "minecraft:item/shield",
    "textures": { "layer0": "minecraft:item/shield" },
    "overrides": [
        { "predicate": { "custom_model_data": 1003, "blocking": 1 }, "model": "relics:item/shield_of_atlas_blocking" },
        { "predicate": { "custom_model_data": 1003 }, "model": "relics:item/shield_of_atlas" }
    ]
})

write_json(f'{PACK_DIR}/assets/minecraft/models/item/shield_blocking.json', {
    "parent": "minecraft:item/shield_blocking",
    "textures": { "layer0": "minecraft:item/shield" },
    "overrides": [
        { "predicate": { "custom_model_data": 1003 }, "model": "relics:item/shield_of_atlas_blocking" }
    ]
})

write_json(f'{PACK_DIR}/assets/minecraft/models/item/bow.json', {
    "parent": "minecraft:item/generated",
    "textures": { "layer0": "minecraft:item/bow" },
    "display": {
        "thirdperson_righthand": { "rotation": [ -80, 260, -40 ], "translation": [ -1, -2, 2.5 ], "scale": [ 0.9, 0.9, 0.9 ] },
        "thirdperson_lefthand": { "rotation": [ -80, -280, 40 ], "translation": [ -1, -2, 2.5 ], "scale": [ 0.9, 0.9, 0.9 ] },
        "firstperson_righthand": { "rotation": [ 0, -90, 25 ], "translation": [ 1.13, 3.2, 1.13 ], "scale": [ 0.68, 0.68, 0.68 ] },
        "firstperson_lefthand": { "rotation": [ 0, 90, -25 ], "translation": [ 1.13, 3.2, 1.13 ], "scale": [ 0.68, 0.68, 0.68 ] }
    },
    "overrides": [
        { "predicate": { "pulling": 1 }, "model": "minecraft:item/bow_pulling_0" },
        { "predicate": { "pulling": 1, "pull": 0.65 }, "model": "minecraft:item/bow_pulling_1" },
        { "predicate": { "pulling": 1, "pull": 0.9 }, "model": "minecraft:item/bow_pulling_2" },
        { "predicate": { "custom_model_data": 1004 }, "model": "relics:item/bow_of_artemis" },
        { "predicate": { "custom_model_data": 1004, "pulling": 1 }, "model": "relics:item/bow_of_artemis_pulling_0" },
        { "predicate": { "custom_model_data": 1004, "pulling": 1, "pull": 0.65 }, "model": "relics:item/bow_of_artemis_pulling_1" },
        { "predicate": { "custom_model_data": 1004, "pulling": 1, "pull": 0.9 }, "model": "relics:item/bow_of_artemis_pulling_2" },
        { "predicate": { "custom_model_data": 1005 }, "model": "relics:item/runans_hurricane" },
        { "predicate": { "custom_model_data": 1005, "pulling": 1 }, "model": "relics:item/runans_hurricane_pulling_0" },
        { "predicate": { "custom_model_data": 1005, "pulling": 1, "pull": 0.65 }, "model": "relics:item/runans_hurricane_pulling_1" },
        { "predicate": { "custom_model_data": 1005, "pulling": 1, "pull": 0.9 }, "model": "relics:item/runans_hurricane_pulling_2" }
    ]
})

# 6. Modern 1.21.4+ / 26.2 items definition JSONs
write_json(f'{PACK_DIR}/assets/minecraft/items/netherite_sword.json', {
    "model": {
        "type": "minecraft:range_dispatch",
        "property": "minecraft:custom_model_data",
        "fallback": { "type": "minecraft:model", "model": "minecraft:item/netherite_sword" },
        "entries": [
            { "threshold": 1001, "model": { "type": "minecraft:model", "model": "relics:item/sword_of_fire" } }
        ]
    }
})

write_json(f'{PACK_DIR}/assets/minecraft/items/netherite_pickaxe.json', {
    "model": {
        "type": "minecraft:range_dispatch",
        "property": "minecraft:custom_model_data",
        "fallback": { "type": "minecraft:model", "model": "minecraft:item/netherite_pickaxe" },
        "entries": [
            { "threshold": 1002, "model": { "type": "minecraft:model", "model": "relics:item/pickaxe_of_thor" } }
        ]
    }
})

# 7. Strict Alpha Verification Scan on all PNGs in the pack
print("\n=== RUNNING STRICT ALPHA VERIFICATION ===")
total_pngs = 0
for root, dirs, files in os.walk(PACK_DIR):
    for f in files:
        if f.endswith('.png'):
            total_pngs += 1
            path = os.path.join(root, f)
            img = Image.open(path).convert('RGBA')
            w, h = img.size
            px = img.load()
            semi_count = 0
            for y in range(h):
                for x in range(w):
                    r, g, b, a = px[x, y]
                    if a not in (0, 255):
                        semi_count += 1
            if semi_count > 0:
                print(f"[FAIL] {f} has {semi_count} semi-transparent pixels!")
            else:
                print(f"[OK] {f} ({w}x{h}): 100% clean binary alpha (0 semi-transparent pixels)")

# 8. Create ZIP archive
print("\n=== CREATING RESOURCE PACK ZIP ===")
with zipfile.ZipFile(ZIP_PATH, 'w', zipfile.ZIP_DEFLATED) as zipf:
    for root, dirs, files in os.walk(PACK_DIR):
        for file in files:
            full_path = os.path.join(root, file)
            rel_path = os.path.relpath(full_path, PACK_DIR)
            zipf.write(full_path, rel_path)

print(f"Successfully generated resource pack: {ZIP_PATH} ({os.path.getsize(ZIP_PATH)} bytes)")
