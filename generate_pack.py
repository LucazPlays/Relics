import os
import json
import zipfile
from PIL import Image, ImageDraw

PACK_DIR = '/root/coding/relics/resourcepack'
ZIP_PATH = '/root/coding/relics/Relics-ResourcePack.zip'

# 1. Base folders
os.makedirs(f'{PACK_DIR}/assets/minecraft/models/item', exist_ok=True)
os.makedirs(f'{PACK_DIR}/assets/minecraft/items', exist_ok=True)
os.makedirs(f'{PACK_DIR}/assets/relics/models/item', exist_ok=True)
os.makedirs(f'{PACK_DIR}/assets/relics/textures/item', exist_ok=True)

# 2. pack.mcmeta
pack_mcmeta = {
    pack: {
        pack_format: 46,
        supported_formats: {
            min_inclusive: 15,
            max_inclusive: 60
        },
        description: §6§lRelics SMP§r - Dummy Resource Packn§7Version 26.2 / 1.21.x
    }
}

with open(f'{PACK_DIR}/pack.mcmeta', 'w') as f:
    json.dump(pack_mcmeta, f, indent=2)

print(Created pack.mcmeta)
