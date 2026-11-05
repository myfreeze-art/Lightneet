#!/usr/bin/env python3
"""
Builds the README artwork (banner, phone mock-ups, diagram) in Russian and English.

1. Record screenshots:
   ./gradlew :app:testDebugUnitTest --tests '*ReadmeScreenshotsTest*' -Proborazzi.test.record=true
2. Run:  python3 docs/art/make_readme_art.py
Output goes to docs/art/. Needs Pillow and the Inter font.
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(ROOT, "..", "..", "app", "build", "outputs", "roborazzi", "readme")
FONT_DIR = "/usr/share/fonts/opentype/inter"

BG = (14, 15, 18)
SURFACE = (22, 24, 29)
OUTLINE = (42, 46, 55)
TEXT = (232, 233, 236)
MUTED = (144, 151, 163)
ACCENT = (242, 193, 78)

TEXTS = {
    "ru": {
        "tagline": "Зашифрованное хранилище файлов,\nкоторое ничего о себе не рассказывает",
        "chips": ["Без интернета", "Argon2id · XChaCha20", "Скрытый сейф", "Чип телефона"],
        "screens": [
            ("2_browser", "Ваши файлы", "имена и даты тоже зашифрованы"),
            ("4_viewer", "Просмотр в памяти", "фото, видео, аудио, текст, PDF"),
            ("6_add_menu", "Своя камера", "снимки не попадают в галерею"),
            ("7_unlock_keyboard", "Своя клавиатура", "пароль не видит чужая клавиатура"),
        ],
        "screens2": [
            ("1_setup", "Честно с первого экрана", "восстановления пароля нет"),
            ("3_list", "Список или сетка", "сортировка, папки, перенос"),
            ("5_settings", "Паника и скрытый сейф", "и маскировка под калькулятор"),
        ],
        "diagram": {
            "title": "Как пароль превращается в доступ к файлам",
            "password": "Пароль",
            "argon": "Argon2id\n≈1 c, до 256 МиБ",
            "chip": "Чип телефона\nHMAC в StrongBox/TEE",
            "kek": "Ключ пароля (KEK)",
            "slots": "4 слота\nосновной · скрытый · паника · шум",
            "master": "Мастер-ключ сейфа",
            "index": "Индекс\nимена, папки, ключи файлов",
            "blocks": "Блоки по 256 КиБ\nXChaCha20-Poly1305",
            "pool": "пул: одинаковые блоки, неотличимые от шума",
            "note": "Каждая попытка требует этого телефона  ·  ключи живут в mlock-памяти libsodium",
        },
    },
    "en": {
        "tagline": "An encrypted file vault\nthat tells nothing about itself",
        "chips": ["No internet", "Argon2id · XChaCha20", "Hidden vault", "Hardware-bound"],
        "screens": [
            ("2_browser", "Your files", "names and dates are encrypted too"),
            ("4_viewer", "In-memory viewers", "photo, video, audio, text, PDF"),
            ("6_add_menu", "Built-in camera", "photos never reach the gallery"),
            ("7_unlock_keyboard", "Built-in keyboard", "no third-party keyboard sees it"),
        ],
        "screens2": [
            ("1_setup", "Honest from screen one", "there is no password recovery"),
            ("3_list", "List or grid", "sorting, folders, moving"),
            ("5_settings", "Panic & hidden vault", "plus a Calculator disguise"),
        ],
        "diagram": {
            "title": "How a password becomes access to your files",
            "password": "Password",
            "argon": "Argon2id\n≈1 s, up to 256 MiB",
            "chip": "Phone's secure chip\nHMAC in StrongBox/TEE",
            "kek": "Password key (KEK)",
            "slots": "4 slots\nmain · hidden · panic · noise",
            "master": "Vault master key",
            "index": "Index\nnames, folders, file keys",
            "blocks": "256 KiB blocks\nXChaCha20-Poly1305",
            "pool": "pool: identical blocks, indistinguishable from noise",
            "note": "Every guess needs this very phone  ·  keys live in libsodium's mlock'd memory",
        },
    },
}


def font(weight, size):
    name = {"bold": "Inter-Bold.otf", "semi": "Inter-SemiBold.otf", "medium": "Inter-Medium.otf", "regular": "Inter-Regular.otf"}[weight]
    return ImageFont.truetype(os.path.join(FONT_DIR, name), size)


def logo(size):
    """The keyhole ring from the launcher icon."""
    s = size * 4
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    k = s / 108 * 2.0
    c = s / 2
    d.ellipse([c - 24 * k, c - 24 * k, c + 24 * k, c + 24 * k], fill=ACCENT)
    d.ellipse([c - 18 * k, c - 18 * k, c + 18 * k, c + 18 * k], fill=(0, 0, 0, 0))
    d.ellipse([c - 6 * k, c - 10 * k, c + 6 * k, c + 2 * k], fill=ACCENT)
    d.polygon([(c - 3 * k, c), (c + 3 * k, c), (c + 5 * k, c + 12 * k), (c - 5 * k, c + 12 * k)], fill=ACCENT)
    return im.resize((size, size), Image.LANCZOS)


def glow(canvas, center, radius, color, strength=90):
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse([center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius], fill=color + (strength,))
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(radius / 2.2)))


def phone(path, width):
    """A screenshot inside a simple dark phone frame with rounded corners and a soft shadow."""
    shot = Image.open(path).convert("RGBA")
    h = int(shot.height * width / shot.width)
    shot = shot.resize((width, h), Image.LANCZOS)
    bezel = max(8, width // 32)
    r = width // 9
    W, H = width + 2 * bezel, h + 2 * bezel
    pad = width // 8
    out = Image.new("RGBA", (W + 2 * pad, H + 2 * pad), (0, 0, 0, 0))
    shadow = Image.new("RGBA", out.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([pad, pad + pad // 3, pad + W, pad + H + pad // 3], r + bezel, fill=(0, 0, 0, 170))
    out.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(pad / 2.5)))
    d = ImageDraw.Draw(out)
    d.rounded_rectangle([pad, pad, pad + W, pad + H], r + bezel, fill=(5, 5, 7), outline=(58, 62, 72), width=max(2, bezel // 4))
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, width, h], r, fill=255)
    out.paste(shot, (pad + bezel, pad + bezel), mask)
    return out


def wrap(d, text, f, width):
    words, lines, cur = text.split(), [], ""
    for w in words:
        nxt = (cur + " " + w).strip()
        if d.textlength(nxt, font=f) <= width or not cur:
            cur = nxt
        else:
            lines.append(cur)
            cur = w
    return "\n".join(lines + [cur])


def center_text(d, xy, text, f, fill, spacing=6):
    d.multiline_text(xy, text, font=f, fill=fill, anchor="ma", align="center", spacing=spacing)


def banner(lang):
    t = TEXTS[lang]
    W, H = 2000, 860
    im = Image.new("RGBA", (W, H), BG + (255,))
    glow(im, (420, 300), 420, ACCENT, 46)
    glow(im, (1560, 700), 520, (91, 192, 190), 28)
    # Phones on the right, slightly overlapping.
    shots = ["4_viewer", "2_browser", "7_unlock_keyboard"]
    widths = [300, 360, 300]
    xs = [1090, 1300, 1560]
    ys = [150, 70, 150]
    for name, w, x, y in [(shots[0], widths[0], xs[0], ys[0]), (shots[2], widths[2], xs[2], ys[2]), (shots[1], widths[1], xs[1], ys[1])]:
        p = phone(os.path.join(SHOTS, f"{lang}_{name}.png"), w)
        im.alpha_composite(p, (x - p.width // 2 + w // 2, y - w // 8))
    # Fade the phones into the background at the bottom.
    fade = Image.new("RGBA", (W, 260), (0, 0, 0, 0))
    fd = ImageDraw.Draw(fade)
    for i in range(260):
        fd.line([(0, i), (W, i)], fill=BG + (int(255 * (i / 260) ** 1.6),))
    im.alpha_composite(fade, (0, H - 260))

    d = ImageDraw.Draw(im)
    im.alpha_composite(logo(150), (120, 150))
    d.text((300, 170), "Lightneet", font=font("bold", 120), fill=TEXT)
    d.multiline_text((124, 360), t["tagline"], font=font("medium", 46), fill=MUTED, spacing=14)
    x, y = 124, 540
    for chip in t["chips"]:
        f = font("semi", 30)
        tw = d.textlength(chip, font=f)
        d.rounded_rectangle([x, y, x + tw + 48, y + 62], 31, fill=SURFACE, outline=OUTLINE, width=2)
        d.ellipse([x + 20, y + 26, x + 30, y + 36], fill=ACCENT)
        d.text((x + 38, y + 14), chip, font=f, fill=TEXT)
        x += tw + 64
        if x > 760:
            x, y = 124, y + 80
    im.convert("RGB").save(os.path.join(ROOT, f"banner_{lang}.png"), optimize=True)


def gallery(lang, key, out_name, phone_w=360):
    items = TEXTS[lang][key]
    gap = 40
    phones = [phone(os.path.join(SHOTS, f"{lang}_{n}.png"), phone_w) for n, _, _ in items]
    pw, ph = phones[0].size
    step = pw - pw // 6
    W = step * (len(items) - 1) + pw + 80
    H = ph + 160
    im = Image.new("RGBA", (W, H), BG + (255,))
    glow(im, (W // 2, H // 2), W // 3, ACCENT, 18)
    d = ImageDraw.Draw(im)
    x = 40
    for p, (_, title, sub) in zip(phones, items):
        im.alpha_composite(p, (x, 0))
        cx = x + pw // 2
        size = 36
        while d.textlength(title, font=font("semi", size)) > step - 24:
            size -= 1
        center_text(d, (cx, ph - 10), title, font("semi", size), TEXT)
        center_text(d, (cx, ph + 42), wrap(d, sub, font("regular", 27), step - 30), font("regular", 27), MUTED)
        x += step
    im.convert("RGB").save(os.path.join(ROOT, out_name), optimize=True)


def diagram(lang):
    t = TEXTS[lang]["diagram"]
    W, H = 2000, 1060
    im = Image.new("RGBA", (W, H), BG + (255,))
    glow(im, (1000, 520), 600, ACCENT, 14)
    d = ImageDraw.Draw(im)
    center_text(d, (W // 2, 50), t["title"], font("bold", 54), TEXT)

    def box(cx, cy, text, w=420, h=150, accent=False, chip=False):
        x0, y0 = cx - w // 2, cy - h // 2
        fill = (58, 47, 18) if accent else SURFACE
        outline = ACCENT if accent or chip else OUTLINE
        d.rounded_rectangle([x0, y0, x0 + w, y0 + h], 28, fill=fill, outline=outline, width=3 if (accent or chip) else 2)
        lines = text.split("\n")
        f1, f2 = font("semi", 36), font("regular", 27)
        total = 44 + 36 * (len(lines) - 1)
        y = cy - total // 2
        d.text((cx, y), lines[0], font=f1, fill=ACCENT if accent else TEXT, anchor="ma")
        for i, line in enumerate(lines[1:]):
            d.text((cx, y + 50 + 34 * i), line, font=f2, fill=MUTED, anchor="ma")
        return (x0, y0, x0 + w, y0 + h)

    def arrow(a, b):
        d.line([a, b], fill=MUTED, width=4)
        import math
        ang = math.atan2(b[1] - a[1], b[0] - a[0])
        L = 20
        p1 = (b[0] - L * math.cos(ang - 0.45), b[1] - L * math.sin(ang - 0.45))
        p2 = (b[0] - L * math.cos(ang + 0.45), b[1] - L * math.sin(ang + 0.45))
        d.polygon([b, p1, p2], fill=MUTED)

    r1 = 260
    pw = box(260, r1, t["password"], w=320, h=120)
    ar = box(760, r1, t["argon"])
    ch = box(1300, r1, t["chip"], w=460, chip=True)
    kk = box(1760, r1, t["kek"], w=380, h=120, accent=True)
    arrow((pw[2], r1), (ar[0] - 6, r1))
    arrow((ar[2], r1), (ch[0] - 6, r1))
    arrow((ch[2], r1), (kk[0] - 6, r1))

    r2 = 560
    sl = box(1700, r2, t["slots"], w=560)
    ms = box(1180, r2, t["master"], w=420, h=120, accent=True)
    arrow((1760, kk[3]), (1760, sl[1] - 6))  # KEK opens one slot
    arrow((sl[0], r2), (ms[2] + 6, r2))

    r3 = 840
    ix = box(1180, r3, t["index"], w=460)
    bl = box(560, r3, t["blocks"], w=460)
    arrow((1180, ms[3]), (1180, ix[1] - 6))
    arrow((ix[0], r3), (bl[2] + 6, r3))

    # Pool of identical noise blocks on the left middle.
    for i in range(7):
        for j in range(3):
            x, y = 345 + i * 62, 450 + j * 62
            shade = 34 + ((i * 7 + j * 13) % 5) * 6
            d.rounded_rectangle([x, y, x + 50, y + 50], 8, fill=(shade, shade + 2, shade + 6))
    center_text(d, (560, 400), t["pool"], font("medium", 28), MUTED)
    arrow((560, bl[1]), (560, 650))

    center_text(d, (W // 2, H - 62), t["note"], font("medium", 30), MUTED)
    im.convert("RGB").save(os.path.join(ROOT, f"how_it_works_{lang}.png"), optimize=True)


if __name__ == "__main__":
    for lang in TEXTS:
        banner(lang)
        gallery(lang, "screens", f"screens_{lang}.png")
        gallery(lang, "screens2", f"screens2_{lang}.png")
        diagram(lang)
    print("done:", sorted(f for f in os.listdir(ROOT) if f.endswith(".png")))
