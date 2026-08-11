#!/usr/bin/env python3
"""Regenerates the toolbar icons. Committed so the mark can be changed rather than redrawn.

    python3 extension/icons/make-icons.py

The mark is a check whose two strokes carry different colours: the short descending arm is
red, the long ascending one green. That is the whole product in one glyph — this tool records
the failures as well as the pass, which is the thing BaekjoonHub structurally cannot do
(README, "How it differs"). A plain green check would say what every other extension says.

Drawn at 8x and downsampled, because Pillow's line drawing does not antialias and a 16px
check with hard edges is a smear. The background is a dark rounded tile so the glyph holds
its contrast on a light toolbar and on a dark one.
"""

from pathlib import Path

from PIL import Image, ImageDraw

SIZES = (16, 32, 48, 128)
SUPERSAMPLE = 8

BACKGROUND = (27, 35, 48, 255)
FAILED = (239, 93, 82, 255)
PASSED = (53, 208, 127, 255)

# Normalised so every size is the same drawing. The elbow sits left of centre and low, which
# is what makes a check read as a check rather than as a tick.
ELBOW = (0.42, 0.70)
START = (0.23, 0.52)
END = (0.79, 0.30)
STROKE = 0.135
CORNER = 0.22


def icon(size: int) -> Image.Image:
    canvas = size * SUPERSAMPLE
    image = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle(
        (0, 0, canvas - 1, canvas - 1), radius=int(CORNER * canvas), fill=BACKGROUND
    )
    width = max(1, int(STROKE * canvas))
    # Red first, green over it: the elbow belongs to the arm that ends in a pass.
    stroke(draw, canvas, START, ELBOW, FAILED, width)
    stroke(draw, canvas, ELBOW, END, PASSED, width)
    return image.resize((size, size), Image.LANCZOS)


def stroke(draw, canvas, start, end, colour, width):
    a = (start[0] * canvas, start[1] * canvas)
    b = (end[0] * canvas, end[1] * canvas)
    draw.line([a, b], fill=colour, width=width)
    # Round caps by hand — Pillow's line has none, and square ends make the elbow a notch.
    for point in (a, b):
        draw.ellipse(
            (point[0] - width / 2, point[1] - width / 2, point[0] + width / 2, point[1] + width / 2),
            fill=colour,
        )


def main() -> None:
    here = Path(__file__).parent
    for size in SIZES:
        path = here / f"icon-{size}.png"
        icon(size).save(path)
        print(f"wrote {path.relative_to(here.parent.parent)}")


if __name__ == "__main__":
    main()
