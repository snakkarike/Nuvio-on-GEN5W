import os
from PIL import Image

icon_src = Image.open('tools/nuvio_icon_1024.png')
logo_src = Image.open('tools/nuvio_logo_preview.png')

res_dir = 'app/src/main/res'

# Directories to ensure
dirs = [
    os.path.join(res_dir, 'drawable-nodpi'),
    os.path.join(res_dir, 'drawable'),
    os.path.join(res_dir, 'mipmap-mdpi'),
    os.path.join(res_dir, 'mipmap-hdpi'),
    os.path.join(res_dir, 'mipmap-xhdpi'),
    os.path.join(res_dir, 'mipmap-xxhdpi'),
    os.path.join(res_dir, 'mipmap-xxxhdpi')
]

for d in dirs:
    os.makedirs(d, exist_ok=True)

# 1. nuvio_auto_icon.png in drawable-nodpi (384x384 - matching Stremio's exact dimensions)
icon_384 = icon_src.resize((384, 384), Image.Resampling.LANCZOS)
icon_384.save(os.path.join(res_dir, 'drawable-nodpi', 'nuvio_auto_icon.png'))
print("Saved drawable-nodpi/nuvio_auto_icon.png (384x384)")

# Also in drawable/
icon_192 = icon_src.resize((192, 192), Image.Resampling.LANCZOS)
icon_192.save(os.path.join(res_dir, 'drawable', 'nuvio_auto_icon.png'))
print("Saved drawable/nuvio_auto_icon.png (192x192)")

# 2. nuvio_logo.png in drawable-nodpi and drawable/
logo_src.save(os.path.join(res_dir, 'drawable-nodpi', 'nuvio_logo.png'))
logo_src.save(os.path.join(res_dir, 'drawable', 'nuvio_logo.png'))
print("Saved nuvio_logo.png in drawable-nodpi and drawable")

# 3. Standard mipmap launcher icons
densities = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192
}

for folder, size in densities.items():
    icon_resized = icon_src.resize((size, size), Image.Resampling.LANCZOS)
    icon_resized.save(os.path.join(res_dir, folder, 'ic_launcher.png'))
    icon_resized.save(os.path.join(res_dir, folder, 'ic_launcher_round.png'))
    print(f"Saved {folder}/ic_launcher.png ({size}x{size})")

print("All icons successfully generated.")
