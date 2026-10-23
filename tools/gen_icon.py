"""生成 300x300 的末影传送石图标。

风格与 16x16 的物品贴图一致：深紫灰色不规则石头 + 中心一颗发光的末影珍珠。
做法是先在 4 倍尺寸下用 float 计算（超采样抗锯齿），再盒式降采样到 300x300。

用法：
    python tools/gen_icon.py
输出：
    tools/portal_stone_300.png
"""
from __future__ import annotations

import os

import numpy as np
from PIL import Image

# ---------------------------------------------------------------- 基本参数

OUT_SIZE = 300
SS = 4                      # 超采样倍数
S = OUT_SIZE * SS           # 渲染分辨率 1200

OUT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "portal_stone_300.png")

# 石头（椭圆基准 + 边缘扰动），坐标是 300 空间
STONE_CX, STONE_CY = 150.0, 152.0
STONE_RX, STONE_RY = 116.0, 100.0

# 末影珍珠
PEARL_CX, PEARL_CY = 150.0, 147.0
PEARL_R = 50.0

# 配色（取自 16x16 原贴图的调色板）
C_STONE_DARK = np.array([44, 39, 58], dtype=np.float64) / 255.0
C_STONE_LIGHT = np.array([105, 95, 133], dtype=np.float64) / 255.0
C_OUTLINE = np.array([24, 20, 33], dtype=np.float64) / 255.0
C_GLOW = np.array([123, 47, 214], dtype=np.float64) / 255.0

# 珍珠的径向色阶（t, rgb）
PEARL_STOPS = [
    (0.00, np.array([252, 240, 255], dtype=np.float64) / 255.0),
    (0.28, np.array([238, 214, 255], dtype=np.float64) / 255.0),
    (0.50, np.array([196, 138, 255], dtype=np.float64) / 255.0),
    (0.72, np.array([140, 68, 214], dtype=np.float64) / 255.0),
    (0.90, np.array([92, 38, 158], dtype=np.float64) / 255.0),
    (1.00, np.array([58, 22, 104], dtype=np.float64) / 255.0),
]


# ---------------------------------------------------------------- 工具函数

def value_noise(size: int, cells: int, rng: np.random.Generator) -> np.ndarray:
    """双线性插值的 value noise，返回 [0,1] 的 size x size 数组。"""
    grid = rng.random((cells + 1, cells + 1))
    coord = np.linspace(0.0, cells, size, endpoint=False)
    i0 = coord.astype(np.int64)
    frac = coord - i0
    frac = frac * frac * (3.0 - 2.0 * frac)          # smoothstep

    a = grid[i0][:, i0]
    b = grid[i0][:, i0 + 1]
    c = grid[i0 + 1][:, i0]
    d = grid[i0 + 1][:, i0 + 1]

    fx = frac[None, :]
    fy = frac[:, None]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def smoothstep(edge0: float, edge1: float, x: np.ndarray) -> np.ndarray:
    t = np.clip((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def composite(dst_rgb, dst_a, src_rgb, src_a):
    """标准 over 合成（未预乘）。"""
    out_a = src_a + dst_a * (1.0 - src_a)
    safe = np.where(out_a > 1e-6, out_a, 1.0)
    out_rgb = (src_rgb * src_a[..., None] + dst_rgb * (dst_a * (1.0 - src_a))[..., None]) / safe[..., None]
    return out_rgb, np.where(out_a > 1e-6, out_a, 0.0)


def lerp_stops(t: np.ndarray, stops) -> np.ndarray:
    """按 t（0..1）在色阶表里插值，返回 (H,W,3)。"""
    out = np.zeros(t.shape + (3,), dtype=np.float64)
    for i in range(len(stops) - 1):
        t0, c0 = stops[i]
        t1, c1 = stops[i + 1]
        m = (t >= t0) & (t <= t1)
        if not m.any():
            continue
        local = (t[m] - t0) / max(t1 - t0, 1e-9)
        out[m] = c0[None, :] * (1 - local)[:, None] + c1[None, :] * local[:, None]
    # 超出范围的两端夹紧
    out[t < stops[0][0]] = stops[0][1]
    out[t > stops[-1][0]] = stops[-1][1]
    return out


# ---------------------------------------------------------------- 绘制

def render() -> np.ndarray:
    rng = np.random.default_rng(20261023)

    # 像素中心坐标，换算回 300 空间
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float64)
    x = (xx + 0.5) / SS
    y = (yy + 0.5) / SS

    # ---------- 石头轮廓 ----------
    nx = (x - STONE_CX) / STONE_RX
    ny = (y - STONE_CY) / STONE_RY
    theta = np.arctan2(ny, nx)
    # 低频扰动，让边缘不那么规整
    wobble = (1.0
              + 0.045 * np.sin(3 * theta + 1.1)
              + 0.030 * np.sin(5 * theta + 2.6)
              + 0.018 * np.sin(7 * theta + 0.4))

    rr = np.sqrt(nx * nx + ny * ny)
    u = rr / wobble                      # 0 = 中心, 1 = 边缘
    inside = u <= 1.0

    # 边缘 1px 抗锯齿
    edge_soft = smoothstep(1.0, 0.985, u)
    stone_a = np.where(inside, edge_soft, 0.0)

    # ---------- 石头表面光照 ----------
    # 把石头近似成半个椭球，用表面法线做漫反射
    nz = np.sqrt(np.clip(1.0 - np.clip(u, 0.0, 1.0) ** 2, 0.0, 1.0))
    n = np.stack([nx / np.maximum(wobble, 1e-6), ny / np.maximum(wobble, 1e-6), nz], axis=-1)
    n /= np.maximum(np.linalg.norm(n, axis=-1, keepdims=True), 1e-9)

    light = np.array([-0.48, -0.74, 0.47])
    light /= np.linalg.norm(light)
    diffuse = np.clip((n * light).sum(axis=-1), 0.0, 1.0)

    shade = 0.22 + 0.78 * diffuse ** 1.15
    stone_rgb = C_STONE_DARK[None, None, :] + (C_STONE_LIGHT - C_STONE_DARK)[None, None, :] * shade[..., None]

    # 岩石纹理：两个八度的噪声
    n1 = value_noise(S, 26, rng)
    n2 = value_noise(S, 74, rng)
    grain = 0.62 * n1 + 0.38 * n2
    stone_rgb *= (1.0 + 0.16 * (grain[..., None] - 0.5))

    # 珍珠附近的环境紫光：只在珍珠外缘一圈，衰减要快，否则整块石头会被染成亮紫
    pd = np.hypot(x - PEARL_CX, y - PEARL_CY)
    pearl_glow = np.exp(-((pd - PEARL_R) / (PEARL_R * 0.30)) ** 2)
    stone_rgb += pearl_glow[..., None] * np.array([0.20, 0.05, 0.30])

    # 左上缘高光 + 外缘压暗
    rim_light = smoothstep(0.62, 0.97, u) * smoothstep(0.0, 0.55, diffuse)
    stone_rgb += rim_light[..., None] * np.array([0.30, 0.26, 0.42]) * 0.55
    edge_dark = smoothstep(0.80, 1.0, u)
    stone_rgb = stone_rgb * (1.0 - 0.72 * edge_dark[..., None]) + C_OUTLINE * (0.72 * edge_dark[..., None])

    # ---------- 末影珍珠 ----------
    t = np.clip(pd / PEARL_R, 0.0, 1.0)
    pearl_rgb = lerp_stops(t, PEARL_STOPS)
    pearl_a = smoothstep(1.0, 0.965, t)

    # 镜面高光（偏左上）
    hl = np.hypot((x - (PEARL_CX - 0.30 * PEARL_R)) / (0.30 * PEARL_R),
                  (y - (PEARL_CY - 0.34 * PEARL_R)) / (0.24 * PEARL_R))
    spec = np.clip(1.0 - hl, 0.0, 1.0) ** 2.2
    pearl_rgb = pearl_rgb + spec[..., None] * np.array([0.95, 0.88, 1.0]) * 0.95

    # 珍珠下缘一点冷色反光
    bounce = np.clip(1.0 - np.hypot((x - (PEARL_CX + 0.22 * PEARL_R)) / (0.34 * PEARL_R),
                                    (y - (PEARL_CY + 0.40 * PEARL_R)) / (0.20 * PEARL_R)), 0.0, 1.0) ** 2.0
    pearl_rgb = pearl_rgb + bounce[..., None] * np.array([0.35, 0.55, 0.95]) * 0.55

    pearl_a = pearl_a * stone_a        # 珍珠只在石头内部显示

    # 石头 + 珍珠
    body_rgb, body_a = composite(stone_rgb, stone_a, pearl_rgb, pearl_a)
    body_rgb = np.clip(body_rgb, 0.0, 1.0)

    # ---------- 背景辉光 ----------
    gd = np.hypot((x - 150.0) / 150.0, (y - 150.0) / 150.0)
    glow_a = np.clip(1.0 - gd, 0.0, 1.0) ** 2.6 * 0.42
    glow_rgb = np.broadcast_to(C_GLOW, (S, S, 3)).copy()

    out_rgb, out_a = composite(glow_rgb, glow_a, body_rgb, body_a)

    # ---------- 几个小星芒（放在石头轮廓之外的空白处） ----------
    for (sx, sy, rad, strength) in ((255.0, 72.0, 9.0, 0.80),
                                    (52.0, 236.0, 8.0, 0.62),
                                    (243.0, 232.0, 6.0, 0.48)):
        dx = np.abs(x - sx) / rad
        dy = np.abs(y - sy) / rad
        star = np.clip(1.0 - (dx * dy * 2.4 + (dx ** 2 + dy ** 2) * 0.55), 0.0, 1.0) ** 1.6
        star_a = np.clip(star * strength, 0.0, 1.0)
        star_rgb = np.broadcast_to(np.array([0.86, 0.74, 1.0]), (S, S, 3)).copy()
        out_rgb, out_a = composite(out_rgb, out_a, star_rgb, star_a)

    out_rgb = np.clip(out_rgb, 0.0, 1.0)

    # ---------- 降采样 ----------
    rgba = np.concatenate([out_rgb, out_a[..., None]], axis=-1)
    rgba = rgba.reshape(OUT_SIZE, SS, OUT_SIZE, SS, 4).mean(axis=(1, 3))
    return (np.clip(rgba, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)


def make_pixel_variant() -> Image.Image:
    """像素风版本：把真正的 16x16 物品贴图用最近邻放大到 300x300。

    16 x 19 = 304，先放大到 304 再居中裁掉 2px，保证每个像素方块是正方形。
    """
    src = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "src", "main", "resources", "assets", "enderportal", "textures", "item", "portal_stone.png",
    )
    with Image.open(src) as im:
        im = im.convert("RGBA")
        block = OUT_SIZE // im.width + 1          # 19
        big = im.resize((im.width * block, im.height * block), Image.NEAREST)
    pad = (big.width - OUT_SIZE) // 2
    return big.crop((pad, pad, pad + OUT_SIZE, pad + OUT_SIZE))


def main() -> None:
    rgba = render()
    img = Image.fromarray(rgba, mode="RGBA")
    img.save(OUT_PATH, optimize=True)
    print(f"saved: {OUT_PATH}")
    print(f"  size={img.size} mode={img.mode}")

    pixel_path = OUT_PATH.replace("_300.png", "_300_pixel.png")
    pixel = make_pixel_variant()
    pixel.save(pixel_path, optimize=True)
    print(f"saved: {pixel_path}")
    print(f"  size={pixel.size} mode={pixel.mode}")


if __name__ == "__main__":
    main()
