import os
import json
import zipfile
from PIL import Image

PACK_DIR = '/root/coding/relics/resourcepack'
ZIP_PATH = '/root/coding/relics/Relics-ResourcePack.zip'

def create_empty(size=32):
    return Image.new('RGBA', (size, size), (0, 0, 0, 0))

def sanitize_and_save(img: Image.Image, filepath: str):
    img = img.convert('RGBA')
    w, h = img.size
    pixels = img.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = pixels[x, y]
            if a < 128:
                pixels[x, y] = (0, 0, 0, 0)
            else:
                pixels[x, y] = (r, g, b, 255)
    img.save(filepath, 'PNG')
    
    # Verification check
    test_img = Image.open(filepath).convert('RGBA')
    t_pixels = test_img.load()
    semi = 0
    for y in range(h):
        for x in range(w):
            if t_pixels[x, y][3] not in (0, 255):
                semi += 1
    if semi > 0:
        print(f"[FAIL] {filepath} has {semi} invalid alpha pixels!")
    else:
        print(f"[SUCCESS] {filepath} ({w}x{h}): 100% binary alpha verified.")

# Build 32x32 Runan's Hurricane Bow Sprites
def draw_runans_bow(pull_state=0):
    img = create_empty(32)
    px = img.load()

    # Color Palette
    DARK_STEEL = (25, 28, 48, 255)
    MID_STEEL  = (45, 55, 85, 255)
    LIGHT_STEEL= (85, 105, 150, 255)
    
    CYAN_DEEP  = (0, 150, 220, 255)
    CYAN_BRIGHT= (0, 240, 255, 255)
    CYAN_LIGHT = (180, 255, 255, 255)
    WHITE      = (255, 255, 255, 255)
    
    MAGENTA_DEEP = (160, 20, 160, 255)
    MAGENTA_MID  = (220, 50, 210, 255)
    MAGENTA_HOT  = (255, 100, 240, 255)
    
    CRYSTAL_TIP  = (100, 240, 255, 255)
    CRYSTAL_CORE = (230, 255, 255, 255)

    # 1. Main Curved Bow Limbs (Diagonal from top-right (27, 4) to bottom-left (4, 27))
    # Upper Limb
    upper_limb = [
        (16, 15, MID_STEEL), (17, 14, MID_STEEL), (18, 13, DARK_STEEL),
        (19, 12, DARK_STEEL), (20, 11, DARK_STEEL), (21, 10, MID_STEEL),
        (22, 9, MID_STEEL), (23, 8, DARK_STEEL), (24, 7, DARK_STEEL),
        (25, 6, MID_STEEL), (26, 5, LIGHT_STEEL), (27, 4, CRYSTAL_TIP),
        (28, 3, CRYSTAL_CORE), (29, 2, WHITE), (28, 4, CYAN_BRIGHT), (27, 5, CYAN_DEEP)
    ]
    # Lower Limb
    lower_limb = [
        (15, 16, MID_STEEL), (14, 17, MID_STEEL), (13, 18, DARK_STEEL),
        (12, 19, DARK_STEEL), (11, 20, DARK_STEEL), (10, 21, MID_STEEL),
        (9, 22, MID_STEEL), (8, 23, DARK_STEEL), (7, 24, DARK_STEEL),
        (6, 25, MID_STEEL), (5, 26, LIGHT_STEEL), (4, 27, CRYSTAL_TIP),
        (3, 28, CRYSTAL_CORE), (2, 29, WHITE), (4, 28, CYAN_BRIGHT), (5, 27, CYAN_DEEP)
    ]

    for x, y, col in upper_limb + lower_limb:
        px[x, y] = col

    # 2. Inner Glowing Cyan Runes on the Bow Body
    runes = [
        (18, 14, CYAN_BRIGHT), (19, 13, CYAN_LIGHT), (22, 10, CYAN_BRIGHT), (23, 9, CYAN_LIGHT), (25, 7, WHITE),
        (14, 18, CYAN_BRIGHT), (13, 19, CYAN_LIGHT), (10, 22, CYAN_BRIGHT), (9, 23, CYAN_LIGHT), (7, 25, WHITE)
    ]
    for x, y, col in runes:
        px[x, y] = col

    # 3. Outer Tempest Wing Feathers (Astral Cyan / Magenta flare)
    # Upper Wings
    upper_wings = [
        # Cyan plume
        (21, 8, CYAN_DEEP), (22, 7, CYAN_BRIGHT), (23, 6, CYAN_LIGHT), (24, 5, WHITE),
        (20, 7, CYAN_DEEP), (21, 6, CYAN_BRIGHT), (22, 5, CYAN_LIGHT), (23, 4, CYAN_BRIGHT), (24, 3, WHITE),
        (19, 6, MAGENTA_DEEP), (20, 5, MAGENTA_MID), (21, 4, MAGENTA_HOT), (22, 3, CYAN_LIGHT),
        # Secondary flare
        (23, 11, CYAN_DEEP), (24, 10, CYAN_BRIGHT), (25, 9, CYAN_LIGHT),
        (25, 10, MAGENTA_MID), (26, 9, MAGENTA_HOT), (27, 8, WHITE),
        (24, 12, CYAN_DEEP), (25, 11, CYAN_BRIGHT)
    ]
    # Lower Wings
    lower_wings = [
        # Cyan plume
        (8, 21, CYAN_DEEP), (7, 22, CYAN_BRIGHT), (6, 23, CYAN_LIGHT), (5, 24, WHITE),
        (7, 20, CYAN_DEEP), (6, 21, CYAN_BRIGHT), (5, 22, CYAN_LIGHT), (4, 23, CYAN_BRIGHT), (3, 24, WHITE),
        (6, 19, MAGENTA_DEEP), (5, 20, MAGENTA_MID), (4, 21, MAGENTA_HOT), (3, 22, CYAN_LIGHT),
        # Secondary flare
        (11, 23, CYAN_DEEP), (10, 24, CYAN_BRIGHT), (9, 25, CYAN_LIGHT),
        (10, 25, MAGENTA_MID), (9, 26, MAGENTA_HOT), (8, 27, WHITE),
        (12, 24, CYAN_DEEP), (11, 25, CYAN_BRIGHT)
    ]

    for x, y, col in upper_wings + lower_wings:
        px[x, y] = col

    # 4. Handle & Center Vortex Core
    handle = [
        (15, 14, DARK_STEEL), (16, 14, MID_STEEL), (14, 15, DARK_STEEL), (15, 15, WHITE),
        (16, 16, CYAN_LIGHT), (17, 15, MID_STEEL), (15, 17, MID_STEEL), (16, 17, DARK_STEEL),
        (14, 16, MID_STEEL), (17, 16, DARK_STEEL)
    ]
    for x, y, col in handle:
        px[x, y] = col

    # 5. Bowstring & Pulling States
    STRING_COLOR = (255, 130, 255, 255)
    STRING_GLOW  = (255, 230, 255, 255)

    if pull_state == 0:
        # Straight string between (27,4) and (4,27)
        # Line coordinates
        string_pts = [
            (26, 6), (25, 7), (24, 8), (23, 9), (22, 10), (21, 11), (20, 12),
            (19, 13), (18, 14), (17, 15), (16, 16), (15, 17), (14, 18), (13, 19),
            (12, 20), (11, 21), (10, 22), (9, 23), (8, 24), (7, 25), (6, 26)
        ]
        for x, y in string_pts:
            if px[x, y][3] == 0 or px[x, y] in (MID_STEEL, DARK_STEEL):
                px[x, y] = STRING_COLOR

    elif pull_state == 1:
        # String pulled back towards (20, 20)
        top_half = [(26, 6), (25, 8), (24, 10), (23, 12), (22, 14), (21, 16), (20, 18), (19, 19)]
        bot_half = [(6, 26), (8, 25), (10, 24), (12, 23), (14, 22), (16, 21), (18, 20), (19, 19)]
        for x, y in top_half + bot_half:
            px[x, y] = STRING_GLOW
        # Small energy arrow
        px[19, 19] = WHITE
        px[18, 18] = CYAN_BRIGHT
        px[17, 17] = CYAN_LIGHT
        px[16, 16] = WHITE

    elif pull_state == 2:
        # Full Pull: String pulled far back to (23, 23)
        top_half = [(26, 6), (26, 9), (25, 12), (25, 15), (24, 18), (24, 21), (23, 23)]
        bot_half = [(6, 26), (9, 26), (12, 25), (15, 25), (18, 24), (21, 24), (23, 23)]
        for x, y in top_half + bot_half:
            px[x, y] = STRING_GLOW
        
        # Runaan's Triple Arrow Energy Burst
        # Main center arrow
        for i in range(7):
            px[23 - i, 23 - i] = WHITE if i == 6 else (CYAN_LIGHT if i % 2 == 0 else CYAN_BRIGHT)
        # Arrowhead
        px[16, 17] = WHITE
        px[17, 16] = WHITE
        px[15, 15] = WHITE
        px[14, 14] = (200, 255, 255, 255)
        # Side energy split bolts (Hurricane passive!)
        px[18, 20] = MAGENTA_HOT
        px[17, 21] = MAGENTA_MID
        px[20, 18] = MAGENTA_HOT
        px[21, 17] = MAGENTA_MID

    return img

# Generate textures
for p in [0, 1, 2]:
    img = draw_runans_bow(pull_state=p)
    if p == 0:
        sanitize_and_save(img, f'{PACK_DIR}/assets/relics/textures/item/runans_hurricane.png')
    sanitize_and_save(img, f'{PACK_DIR}/assets/relics/textures/item/runans_hurricane_pulling_{p}.png')

# Update ZIP Archive
print("\n=== UPDATING ZIP ARCHIVE ===")
with zipfile.ZipFile(ZIP_PATH, 'w', zipfile.ZIP_DEFLATED) as zipf:
    for root, dirs, files in os.walk(PACK_DIR):
        for file in files:
            full_path = os.path.join(root, file)
            rel_path = os.path.relpath(full_path, PACK_DIR)
            zipf.write(full_path, rel_path)

print(f"Resource Pack ZIP successfully updated: {ZIP_PATH} ({os.path.getsize(ZIP_PATH)} bytes)")
