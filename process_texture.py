#!/usr/bin/env python3
"""
Relics Texture Sanitizer & Converter
Ensures 100% Minecraft-compatible textures:
- Strict binary alpha (0 or 255, no semi-transparency)
- Cleans background color bleed on alpha=0 pixels
- Optional resizing (nearest-neighbor to preserve crisp pixel art)
- Verifies output compliance
"""
import sys
import os
from PIL import Image

def process_texture(input_path: str, output_path: str, target_size: tuple = None, alpha_threshold: int = 128):
    if not os.path.exists(input_path):
        print(f"Error: Input file {input_path} does not exist.")
        sys.exit(1)

    img = Image.open(input_path).convert('RGBA')

    # Optional resize with NEAREST filtering to keep pixel art crisp
    if target_size:
        img = img.resize(target_size, Image.Resampling.NEAREST)

    width, height = img.size
    pixels = img.load()
    
    cleaned_pixels = 0
    opaque_pixels = 0

    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            if a < alpha_threshold:
                pixels[x, y] = (0, 0, 0, 0)
                cleaned_pixels += 1
            else:
                pixels[x, y] = (r, g, b, 255)
                opaque_pixels += 1

    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    img.save(output_path, 'PNG')

    # Verify
    verify_img = Image.open(output_path).convert('RGBA')
    v_pixels = verify_img.load()
    semi_count = 0
    for y in range(height):
        for x in range(width):
            _, _, _, a = v_pixels[x, y]
            if a not in (0, 255):
                semi_count += 1

    if semi_count == 0:
        print(f"[SUCCESS] Processed {output_path}: {width}x{height}px, {opaque_pixels} opaque pixels, {cleaned_pixels} transparent pixels (0 semi-transparent pixels).")
    else:
        print(f"[ERROR] Found {semi_count} invalid alpha pixels in {output_path}!")

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("Usage: python3 process_texture.py <input.png> <output.png> [size (e.g. 16 or 32)] [threshold (default 128)]")
        sys.exit(1)
    
    in_file = sys.argv[1]
    out_file = sys.argv[2]
    size = None
    if len(sys.argv) >= 4:
        s = int(sys.argv[3])
        size = (s, s)
    thresh = 128
    if len(sys.argv) >= 5:
        thresh = int(sys.argv[4])

    process_texture(in_file, out_file, size, thresh)
