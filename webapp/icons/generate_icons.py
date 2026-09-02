"""Renders the Pay & Plan icon set (same drawing as the Android adaptive icon)."""
from PIL import Image, ImageDraw, ImageFont
import os

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, '..', 'fonts', 'luckiestguy_regular.ttf')

INK = (23, 22, 26, 255)
BLUE = (77, 150, 255, 255)
BLUE_HI = (102, 169, 255, 255)
CORAL = (255, 107, 107, 255)
YELLOW = (255, 217, 61, 255)


def draw_icon(size, inset=0.0, background=True):
    """inset 0 = art fills the tile, 0.18 = art pulled into the maskable safe zone."""
    S = size
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    if background:
        d.rectangle([0, 0, S, S], fill=BLUE)
        d.polygon([(0, 0), (S, S), (S, 0)], fill=BLUE_HI)

    # everything below is expressed on a 108 unit grid, like the Android vector
    scale = (S / 108.0) * (1.0 - inset * 2)
    off = S * inset

    def px(v):
        return off + v * scale

    stroke = max(2, int(3.5 * scale))

    # calendar body
    d.rounded_rectangle([px(22), px(34), px(86), px(86)], radius=px(6) - off,
                        fill=CORAL, outline=INK, width=stroke)
    # header strip
    d.rounded_rectangle([px(22), px(34), px(86), px(46)], radius=px(6) - off, fill=INK)
    d.rectangle([px(22), px(42), px(86), px(46)], fill=INK)

    # rings
    for x in (38, 70):
        d.line([(px(x), px(20)), (px(x), px(36))], fill=INK, width=max(2, int(4 * scale)))
        d.ellipse([px(x - 4), px(20 - 4), px(x + 4), px(20 + 4)],
                  fill=YELLOW, outline=INK, width=max(2, int(3 * scale)))

    # coin
    d.ellipse([px(54 - 17), px(64 - 17), px(54 + 17), px(64 + 17)],
              fill=YELLOW, outline=INK, width=stroke)

    try:
        font = ImageFont.truetype(FONT, int(26 * scale))
    except OSError:
        font = ImageFont.load_default()
    box = d.textbbox((0, 0), '€', font=font)
    w, h = box[2] - box[0], box[3] - box[1]
    d.text((px(54) - w / 2 - box[0], px(64) - h / 2 - box[1]), '€', font=font, fill=INK)

    return img


def main():
    draw_icon(512).save(os.path.join(HERE, 'icon-512.png'))
    draw_icon(192).save(os.path.join(HERE, 'icon-192.png'))
    draw_icon(180).save(os.path.join(HERE, 'apple-touch-icon.png'))
    draw_icon(512, inset=0.14).save(os.path.join(HERE, 'icon-maskable-512.png'))
    print('icons written')


if __name__ == '__main__':
    main()
