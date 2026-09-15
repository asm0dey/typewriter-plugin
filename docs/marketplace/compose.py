from PIL import Image, ImageDraw, ImageFont, ImageFilter
import pathlib, sys

SRC = pathlib.Path("docs/marketplace")
OUT = SRC / "store"
OUT.mkdir(exist_ok=True)

BOLD = "/usr/share/fonts/noto/NotoSans-Bold.ttf"
REG  = "/home/finkel/.local/share/fonts/i/InterVariable.ttf"

# IDE-dark palette so the frame belongs to the screenshot rather than fighting it.
BG_TOP, BG_BOT = (28, 30, 34), (18, 19, 22)
FG, MUTED, ACCENT = (236, 238, 242), (150, 156, 168), (88, 140, 255)

W = 1600
PAD = 72
SHOT_MAX_W = W - PAD * 2

def gradient(w, h):
    img = Image.new("RGB", (w, h), BG_TOP)
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(h - 1, 1)
        d.line([(0, y), (w, y)],
               fill=tuple(int(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOT)))
    return img

def rounded(img, r):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], r, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out

def wrap(draw, text, font, max_w):
    words, lines, cur = text.split(), [], ""
    for w_ in words:
        trial = (cur + " " + w_).strip()
        if draw.textlength(trial, font=font) <= max_w:
            cur = trial
        else:
            if cur: lines.append(cur)
            cur = w_
    if cur: lines.append(cur)
    return lines

def compose(src_name, headline, sub, out_name):
    shot = Image.open(SRC / src_name).convert("RGB")
    scale = min(1.0, SHOT_MAX_W / shot.width)
    shot = shot.resize((int(shot.width * scale), int(shot.height * scale)), Image.LANCZOS)
    shot = rounded(shot, 14)

    f_head = ImageFont.truetype(BOLD, 58)
    f_sub = ImageFont.truetype(REG, 30)

    probe = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    head_lines = wrap(probe, headline, f_head, SHOT_MAX_W)
    sub_lines = wrap(probe, sub, f_sub, SHOT_MAX_W)

    head_h = len(head_lines) * 70
    sub_h = len(sub_lines) * 42
    top_block = PAD + head_h + 14 + sub_h + 48
    H = top_block + shot.height + PAD

    canvas = gradient(W, H)
    d = ImageDraw.Draw(canvas)

    y = PAD
    for ln in head_lines:
        d.text((PAD, y), ln, font=f_head, fill=FG); y += 70
    y += 14
    for ln in sub_lines:
        d.text((PAD, y), ln, font=f_sub, fill=MUTED); y += 42

    # Accent rule tying the caption to the image below it.
    d.rounded_rectangle([PAD, y + 10, PAD + 84, y + 16], 3, fill=ACCENT)

    # Soft drop shadow + hairline border, so the dialog reads as a card instead of
    # dissolving into a background of nearly the same colour.
    sx, sy = (W - shot.width) // 2, top_block
    shadow = Image.new("RGBA", (shot.width + 48, shot.height + 48), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        [24, 24, 24 + shot.width, 24 + shot.height], 16, fill=(0, 0, 0, 120))
    shadow = shadow.filter(ImageFilter.GaussianBlur(14))
    canvas.paste(Image.alpha_composite(
        Image.new("RGBA", shadow.size, (0, 0, 0, 0)), shadow).convert("RGB"),
        (sx - 24, sy - 24 + 8), shadow)
    canvas.paste(shot, (sx, sy), shot)
    ImageDraw.Draw(canvas).rounded_rectangle(
        [sx, sy, sx + shot.width - 1, sy + shot.height - 1], 14, outline=(62, 66, 74), width=2)
    canvas.save(OUT / out_name)
    print(f"{out_name}  {canvas.width}x{canvas.height}")

compose("01-edit-snippet-dialog.png",
        "Snippets are real files",
        "Edit them with the IDE's own highlighting, completion and formatting — then press Play and watch them type.",
        "01-snippets-are-files.png")

compose("02-new-snippet-dialog.png",
        "Write it. Don't name it.",
        "New Snippet opens straight into an editor. Pick a language; the file gets a non-colliding name when you save.",
        "02-write-dont-name.png")

compose("03-settings.png",
        "Typing you can tune",
        "Base delay, jitter and newline pause — set globally, or per snippet with a tw: comment.",
        "03-timing.png")
