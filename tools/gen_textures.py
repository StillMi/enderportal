"""生成末影传送石模组的两张 16x16 贴图。"""
import math
import os
import random

from PIL import Image

ROOT = os.path.join(
    r"F:\文档\deepseek-harness\default-workspace",
    "enderportal", "src", "main", "resources", "assets", "enderportal", "textures",
)

SIZE = 16


def make_item() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()

    # 石头轮廓：一个略微不规则的椭圆
    mask = [[False] * SIZE for _ in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            dx = (x - 7.5) / 6.3
            dy = (y - 8.2) / 5.4
            if dx * dx + dy * dy <= 1.0:
                mask[y][x] = True
    for (x, y) in ((2, 5), (3, 4), (12, 12), (11, 13), (1, 10), (14, 6)):
        mask[y][x] = False

    def inside(x: int, y: int) -> bool:
        return 0 <= x < SIZE and 0 <= y < SIZE and mask[y][x]

    palette = {
        0: (0, 0, 0, 0),
        1: (44, 39, 58, 255),
        2: (60, 54, 78, 255),
        3: (80, 72, 104, 255),
        4: (105, 95, 133, 255),
    }

    random.seed(11)
    for y in range(SIZE):
        for x in range(SIZE):
            if not mask[y][x]:
                continue
            edge = any(not inside(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                px[x, y] = (24, 20, 33, 255)
                continue
            t = (y - 3) / 11.0
            idx = 4 - int(t * 2.6) + random.choice([0, 0, 0, 1, -1])
            idx = max(1, min(4, idx))
            px[x, y] = palette[idx]

    # 中间嵌一颗末影珍珠
    cx, cy = 8.0, 8.0
    for y in range(SIZE):
        for x in range(SIZE):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if d <= 2.9:
                if d <= 1.1:
                    color = (245, 226, 255)
                elif d <= 2.1:
                    color = (186, 120, 255)
                else:
                    color = (124, 54, 200)
                px[x, y] = (color[0], color[1], color[2], 255)
            elif d <= 4.3 and mask[y][x]:
                r, g, b, a = px[x, y]
                px[x, y] = (min(255, r + 20), max(0, g - 2), min(255, b + 24), a)

    # 一点高光
    px[7, 6] = (255, 250, 255, 255)
    px[9, 10] = (150, 92, 220, 255)
    return img


def make_portal() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()

    for y in range(SIZE):
        for x in range(SIZE):
            v = 0.5 + 0.5 * math.sin(x * 0.85 + y * 0.55)
            w = 0.5 + 0.5 * math.sin(x * 0.30 - y * 1.10 + 1.7)
            m = v * 0.6 + w * 0.4
            r = int(58 + 52 * m)
            g = int(14 + 26 * m)
            b = int(96 + 96 * m)
            a = int(150 + 80 * m)
            px[x, y] = (r, g, b, a)

    random.seed(5)
    for _ in range(30):
        x, y = random.randrange(SIZE), random.randrange(SIZE)
        px[x, y] = (214, 176, 255, 235)
    for _ in range(14):
        x, y = random.randrange(SIZE), random.randrange(SIZE)
        px[x, y] = (30, 8, 52, 235)
    return img


def main() -> None:
    item_dir = os.path.join(ROOT, "item")
    block_dir = os.path.join(ROOT, "block")
    os.makedirs(item_dir, exist_ok=True)
    os.makedirs(block_dir, exist_ok=True)

    item_path = os.path.join(item_dir, "portal_stone.png")
    portal_path = os.path.join(block_dir, "remote_portal.png")

    make_item().save(item_path)
    make_portal().save(portal_path)

    for path in (item_path, portal_path):
        with Image.open(path) as im:
            print(f"{path} -> {im.size} {im.mode}")


if __name__ == "__main__":
    main()
