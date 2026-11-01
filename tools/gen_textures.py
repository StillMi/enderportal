"""生成末影传送石模组的三张 16x16 贴图。

  * portal_stone.png          —— 灰紫色石头 + 末影珍珠
  * dungeon_portal_stone.png  —— 紫色宝石 + 一圈火焰（地牢传送石）
  * remote_portal.png         —— 传送门方块

用法：python tools/gen_textures.py
"""
import json
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


def make_dungeon_item() -> Image.Image:
    """紫色宝石 + 一圈火焰。

    构图分三层，从外到内：
      1. 火焰环  —— 距中心 4.9~7.5 像素，外缘随角度起伏（火舌），
                     颜色沿半径由亮黄渐变到暗红，形成"一圈火"的观感
      2. 暗色镶边 —— 宝石与火焰之间的一圈深紫，让宝石从火里"浮"出来
      3. 紫色宝石 —— 多面切割的椭圆，中心高光、边缘亮紫

    中心取 (8.0, 8.0)，也就是 16×16 的正中，四周各留 1 像素余量。
    """
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()

    cx, cy = 8.0, 8.0

    # ---- 1. 火焰环 ----
    # 火舌：几个正弦叠加出不规则的外缘，避免变成一个规整的甜甜圈
    random.seed(23)
    tongue = [0.0] * 360
    for i in range(360):
        a = math.radians(i)
        tongue[i] = (
            0.55 * math.sin(a * 5.0 + 0.7)
            + 0.32 * math.sin(a * 9.0 + 2.1)
            + 0.16 * math.sin(a * 15.0 + 4.3)
        )

    for y in range(SIZE):
        for x in range(SIZE):
            dx = x + 0.5 - cx
            dy = y + 0.5 - cy
            d = math.hypot(dx, dy)
            angle = math.degrees(math.atan2(dy, dx)) % 360.0
            wobble = tongue[int(angle) % 360]

            inner = 4.9
            outer = 6.9 + 0.55 * wobble
            if not (inner <= d <= outer):
                continue

            # 火焰从内到外：亮黄 → 橙 → 暗红
            t = (d - inner) / max(0.001, outer - inner)
            if t < 0.30:
                color = (255, 238, 158)
            elif t < 0.58:
                color = (255, 178, 56)
            elif t < 0.82:
                color = (234, 108, 26)
            else:
                color = (152, 48, 18)

            # 火舌尖上撒一点余烬，让边缘不那么整齐
            if t > 0.84 and random.random() < 0.4:
                color = (198, 64, 20)

            px[x, y] = (color[0], color[1], color[2], 255)

    # ---- 2. 暗色镶边 ----
    for y in range(SIZE):
        for x in range(SIZE):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if 4.2 <= d < 4.9:
                px[x, y] = (58, 26, 92, 255)

    # ---- 3. 紫色宝石 ----
    for y in range(SIZE):
        for x in range(SIZE):
            dx = x + 0.5 - cx
            dy = y + 0.5 - cy
            d = math.hypot(dx, dy)
            if d > 4.2:
                continue
            # 多面切割：两个不同频率的正弦制造明暗切面
            facet = math.sin(dx * 1.9 + dy * 1.1) * 0.5 + math.sin(dx * 0.8 - dy * 2.3) * 0.5
            shade = 0.5 + 0.5 * facet
            if d < 1.1:
                color = (250, 232, 255)
            elif d < 2.3:
                color = (216, 150, 255)
            elif d < 3.4:
                color = (168 + int(56 * shade), 92 + int(40 * shade), 238)
            else:
                color = (130 + int(40 * shade), 58 + int(30 * shade), 204)
            px[x, y] = (color[0], color[1], color[2], 255)

    # 宝石高光与暗面
    px[6, 5] = (255, 252, 255, 255)
    px[7, 5] = (236, 200, 255, 255)
    px[10, 10] = (128, 52, 200, 255)
    px[9, 11] = (92, 34, 160, 255)

    return img


PORTAL_FRAMES = 32
PORTAL_FRAME_TICKS = 2


def make_portal() -> Image.Image:
    """传送门方块：多帧竖向拼图 + 流动的紫色漩涡。

    下界门那种观感来自两件事，这里都照做：
      1. 贴图是**多帧动画**（竖着拼一列，配 .mcmeta），不是一张静态图；
      2. 每一帧的亮部沿某个方向平移，连起来看就是"在流动"。

    紫色调配合末影主题：暗紫底 + 亮紫流带 + 零星白紫颗粒。
    """
    width = SIZE
    height = SIZE * PORTAL_FRAMES
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    px = img.load()

    random.seed(5)
    # 颗粒位置在整段动画里固定，只跟着流向缓慢上移，避免每帧乱跳
    specks = [
        (random.randrange(width), random.uniform(0, SIZE),
         random.choice([(226, 196, 255), (180, 130, 255)]))
        for _ in range(26)
    ]

    for frame in range(PORTAL_FRAMES):
        phase = frame / PORTAL_FRAMES * math.tau
        for y in range(SIZE):
            for x in range(width):
                # 两条不同速度的波叠出漩涡感；phase 让它逐帧整体平移
                v = 0.5 + 0.5 * math.sin(x * 0.85 + y * 0.55 + phase)
                w = 0.5 + 0.5 * math.sin(x * 0.30 - y * 1.10 + 1.7 - phase * 1.6)
                m = v * 0.6 + w * 0.4

                r = int(52 + 58 * m)
                g = int(10 + 30 * m)
                b = int(92 + 104 * m)
                # 亮的地方更实、暗的地方更透，透出后面的方块
                a = int(140 + 90 * m)
                px[x, y + frame * SIZE] = (r, g, b, a)

        # 颗粒随相位缓慢上移，循环回到画面底部
        for (sx, sy0, color) in specks:
            sy = (sy0 - frame * (SIZE / PORTAL_FRAMES)) % SIZE
            px[sx, int(sy) + frame * SIZE] = (color[0], color[1], color[2], 235)

    return img


def portal_mcmeta() -> str:
    """动画描述：逐帧播放，每帧 PORTAL_FRAME_TICKS tick。

    显式写出 width / height。不写的话原版会取 min(宽, 高) 当帧尺寸，
    我们这张 16x512 恰好是 16 才没错；写出来就不依赖这个巧合了。
    """
    data = {
        "animation": {
            "frametime": PORTAL_FRAME_TICKS,
            "interpolate": True,
            "width": SIZE,
            "height": SIZE,
        }
    }
    return json.dumps(data, indent=2) + chr(10)


def main() -> None:
    item_dir = os.path.join(ROOT, "item")
    block_dir = os.path.join(ROOT, "block")
    os.makedirs(item_dir, exist_ok=True)
    os.makedirs(block_dir, exist_ok=True)

    targets = {
        os.path.join(item_dir, "portal_stone.png"): make_item(),
        os.path.join(item_dir, "dungeon_portal_stone.png"): make_dungeon_item(),
        os.path.join(block_dir, "remote_portal.png"): make_portal(),
    }

    for path, image in targets.items():
        image.save(path)
        with Image.open(path) as im:
            print(f"{path} -> {im.size} {im.mode}")

    # 传送门那张是多帧动画，必须配一个 .mcmeta 才会逐帧播放
    portal_png = os.path.join(block_dir, "remote_portal.png")
    meta_path = portal_png + ".mcmeta"
    with open(meta_path, "w", encoding="utf-8") as f:
        f.write(portal_mcmeta())
    print(f"{meta_path} -> {PORTAL_FRAMES} frames x {PORTAL_FRAME_TICKS} tick")


if __name__ == "__main__":
    main()
