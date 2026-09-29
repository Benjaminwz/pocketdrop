# -*- coding: utf-8 -*-
"""畫 README 用的圖：頂部橫幅（docs/banner.png、docs/banner-en.png）和下載按鈕（docs/btn-*.png）。
用法：python tools/make_readme_art.py（要 Pillow，Windows 內建的微軟正黑體和 Segoe UI）
截圖先用電腦版拍好放在 docs/screenshot-pc.png、docs/screenshot-pc-en.png。"""
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs")
FONTS = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")
NAVY, NAVY_DEEP, BLUE, BLUE_DARK = (26, 38, 86), (14, 21, 52), (53, 88, 212), (37, 66, 173)
SOFT, MUTED = (193, 203, 238), (143, 157, 214)


def font(name, size, index=0):
    return ImageFont.truetype(os.path.join(FONTS, name), size, index=index)


def zh(size, bold=False):
    return font("msjhbd.ttc" if bold else "msjh.ttc", size)


def en(size, weight="regular"):
    return font({"regular": "segoeui.ttf", "semibold": "seguisb.ttf", "light": "segoeuil.ttf", "bold": "segoeuib.ttf"}[weight], size)


def gradient(w, h, top_left, bottom_right):
    """斜的漸層（左上 → 右下）。"""
    base = Image.new("RGB", (w, h))
    px = base.load()
    for y in range(h):
        for x in range(0, w, 2):
            t = min(1.0, (x / w) * 0.65 + (y / h) * 0.35)
            c = tuple(round(a + (b - a) * t) for a, b in zip(top_left, bottom_right))
            px[x, y] = c
            if x + 1 < w:
                px[x + 1, y] = c
    return base


def add_glow(img, center, color, radius, alpha):
    """一團柔和的光（放在背景增加層次）。畫在跟底圖一樣大的圖層上，模糊的邊才不會被切出一條線。"""
    g = Image.new("RGBA", img.size, (0, 0, 0, 0))
    cx, cy = center
    ImageDraw.Draw(g).ellipse([cx - radius, cy - radius, cx + radius, cy + radius], fill=color + (alpha,))
    img.alpha_composite(g.filter(ImageFilter.GaussianBlur(radius * 0.5)))


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.width - 1, img.height - 1], radius=radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def banner(shot_path, out_path, lang):
    W, H = 2400, 1080
    img = gradient(W, H, NAVY_DEEP, BLUE_DARK).convert("RGBA")
    add_glow(img, (1950, 400), (80, 120, 255), 520, 70)
    add_glow(img, (200, 1000), (40, 60, 160), 380, 90)
    d = ImageDraw.Draw(img)
    # 左邊：圖示、名稱、重點
    icon = Image.open(os.path.join(DOCS, "icon.png")).convert("RGBA").resize((176, 176), Image.LANCZOS)
    x0 = 170
    img.alpha_composite(icon, (x0, 250))
    if lang == "zh":
        d.text((x0 + 214, 244), "口袋快傳", font=zh(128, True), fill="white")
        d.text((x0 + 220, 400), "PocketDrop", font=en(54, "semibold"), fill=SOFT)
        d.text((x0, 540), "手機和電腦互傳照片、影片、檔案", font=zh(66, True), fill="white")
        d.text((x0, 632), "原檔不壓縮，一條傳輸線或 Wi-Fi 就能傳。", font=zh(46), fill=SOFT)
        points = ["原檔畫質", "大檔案也快", "整個資料夾", "免帳號"]
        pf = zh(38)
    else:
        d.text((x0 + 214, 262), "PocketDrop", font=en(122, "semibold"), fill="white")
        d.text((x0 + 220, 410), "口袋快傳", font=zh(50, True), fill=SOFT)
        d.text((x0, 540), "Phone to PC, in original quality", font=en(70, "semibold"), fill="white")
        d.text((x0, 640), "Over your Wi-Fi or a single USB cable. No account.", font=en(46), fill=SOFT)
        points = ["Original quality", "Fast for big files", "Whole folders", "No account"]
        pf = en(38)
    # 重點：細線隔開的四個小標
    x, y = x0, 790
    for i, p in enumerate(points):
        if i:
            d.line([(x, y + 8), (x, y + 44)], fill=(95, 115, 190), width=3)
            x += 34
        d.text((x, y), p, font=pf, fill=MUTED)
        x += d.textlength(p, font=pf) + 34
    # 右邊：電腦版截圖（圓角、陰影、稍微超出底部）
    shot = Image.open(shot_path).convert("RGB")
    # 裁掉 Windows 的標題列：從上面往下找，找到口袋快傳深藍色的頂部為止
    top = next((y for y in range(min(200, shot.height)) if sum(abs(a - b) for a, b in zip(shot.getpixel((shot.width // 2, y)), NAVY)) < 30), 0)
    edge = round(shot.width * 0.012)  # 左右兩邊 Windows 視窗邊框的深色細線也裁掉
    shot = shot.crop((edge, top, shot.width - edge, shot.height))
    sh = 1180
    shot = shot.resize((round(shot.width * sh / shot.height), sh), Image.LANCZOS)
    shot = rounded(shot, 34)
    sx, sy = W - shot.width - 190, 120
    shadow = Image.new("RGBA", (shot.width + 160, shot.height + 160), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([80, 80, 80 + shot.width, 80 + shot.height], radius=40, fill=(0, 0, 20, 150))
    shadow = shadow.filter(ImageFilter.GaussianBlur(36))
    img.alpha_composite(shadow, (sx - 80, sy - 50))
    img.alpha_composite(shot, (sx, sy))
    # 整張圓角（在 GitHub 白底、深色模式都好看）
    rounded(img.convert("RGB"), 44).save(out_path, optimize=True)


def down_arrow(d, cx, cy, s, color, width):
    """自己畫的下載符號（箭頭加底線），不用 emoji。"""
    d.line([(cx, cy - s), (cx, cy + s * 0.45)], fill=color, width=width)
    d.line([(cx - s * 0.55, cy - s * 0.05), (cx, cy + s * 0.5), (cx + s * 0.55, cy - s * 0.05)], fill=color, width=width, joint="curve")
    d.line([(cx - s * 0.75, cy + s * 0.95), (cx + s * 0.75, cy + s * 0.95)], fill=color, width=width)


def button(text, sub, out_path, primary, arrow=True, f=None):
    """圓角按鈕：主要（寶藍底白字）或次要（白底藍框）。2 倍解析度，README 裡用一半的寬度顯示。"""
    f = f or zh(40, True)
    sf = zh(26)
    tmp = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    tw = tmp.textlength(text, font=f)
    sw = tmp.textlength(sub, font=sf) if sub else 0
    pad, icon_w = 52, (58 if arrow else 0)
    w, h = int(pad * 2 + icon_w + max(tw, sw)), 124
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if primary:
        d.rounded_rectangle([0, 0, w - 1, h - 1], radius=26, fill=BLUE)
        fg, sub_fg = "white", (205, 216, 255)
    else:
        d.rounded_rectangle([0, 0, w - 1, h - 1], radius=26, fill="white", outline=(201, 213, 251), width=3)
        fg, sub_fg = NAVY, (107, 118, 136)
    if arrow:
        down_arrow(d, pad + 18, h / 2 - 4, 22, fg, 5)
    tx = pad + icon_w
    if sub:
        d.text((tx, 18), text, font=f, fill=fg)
        d.text((tx, 74), sub, font=sf, fill=sub_fg)
    else:
        d.text((tx, (h - f.size) / 2 - 6), text, font=f, fill=fg)
    img.save(out_path, optimize=True)


if __name__ == "__main__":
    banner(os.path.join(DOCS, "screenshot-pc.png"), os.path.join(DOCS, "banner.png"), "zh")
    banner(os.path.join(DOCS, "screenshot-pc-en.png"), os.path.join(DOCS, "banner-en.png"), "en")
    button("下載 Windows 版", "Windows 10 / 11", os.path.join(DOCS, "btn-windows.png"), True)
    button("下載 Android App", "Android 10 以上", os.path.join(DOCS, "btn-android.png"), False)
    button("iPhone 免安裝", "掃 QR code 直接用", os.path.join(DOCS, "btn-iphone.png"), False, arrow=False)
    button("Download for Windows", "Windows 10 / 11", os.path.join(DOCS, "btn-windows-en.png"), True, f=en(40, "semibold"))
    button("Android app", "Android 10 or later", os.path.join(DOCS, "btn-android-en.png"), False, f=en(40, "semibold"))
    print("ok")
