"""MR Star (FINDN) BLE LED garland: command bytes and the colour correction we measured.

Every command is written to characteristic FFF3 (service FFF0), write without response. The controller never answers.
The bytes come from github.com/hirotasoshu/ha-findn-led-ble; speed from github.com/nukemberg/cheap-leds and
github.com/jeanpfs/lumen. All of them were checked on our garland.
"""

import colorsys


def power(on: bool) -> bytes:
    return bytes([0xBC, 0x01, 0x01, 0x01 if on else 0x00, 0x55])


def raw_color(hue: int, saturation: int) -> bytes:
    """hue 0..359, saturation 0..1000, sent as they are."""
    return bytes([0xBC, 0x04, 0x06, hue >> 8, hue & 0xFF, saturation >> 8, saturation & 0xFF, 0x00, 0x00, 0x55])


def brightness(percent: float) -> bytes:
    v = max(1, min(1000, round(percent * 10)))  # 0 would look like off while the strip is on
    return bytes([0xBC, 0x05, 0x06, v >> 8, v & 0xFF, 0x00, 0x00, 0x00, 0x00, 0x55])


def effect(number: int) -> bytes:
    """See EFFECTS.md for the 58 numbers and what each really does. A colour command stops an effect."""
    return bytes([0xBC, 0x06, 0x02, 0x00, number, 0x55])


def direction(away_from_controller: bool) -> bytes:
    return bytes([0xBC, 0x07, 0x01, 0x01 if away_from_controller else 0x00, 0x55])


def speed(value: int) -> bytes:
    """1..100, higher is faster."""
    return bytes([0xBC, 0x08, 0x01, max(1, min(100, round(value))), 0x55])


# ---- colour correction ---------------------------------------------------------------------------------------------
# The strip's green and blue are strong for its red. Sent hue 30 (orange) it shows yellow, sent 300 (purple) it shows
# violet. HUE_SENT -> HUE_SEEN was measured with a phone camera (daylight white balance, exposure locked), reading the
# hue of the light on a white wall; we send the inverse. Checked with a second series: every corrected hue lands within
# 6 degrees of the one asked for (uncorrected it was off by up to 30).
HUE_SENT = (0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330, 360)
HUE_SEEN = (0, 52, 82, 116, 123, 175, 200, 215, 237, 253, 275, 300, 360)
# Saturation 0 is a cold cyan white; hue 0 at saturation 270 (red full, green and blue at 0.73) looks neutral.
WHITE_GAINS = (1.0, 0.73, 0.73)


def strip_hue(want: float) -> float:
    """The hue (degrees) to send so the garland shows `want`."""
    want %= 360
    for i in range(len(HUE_SEEN) - 1):
        a, b = HUE_SEEN[i], HUE_SEEN[i + 1]
        if a <= want <= b:
            return (HUE_SENT[i] + (want - a) / (b - a) * (HUE_SENT[i + 1] - HUE_SENT[i])) % 360
    return want


def strip_saturation(s: float) -> float:
    """The strip lights its weaker channels at 1 - s, linearly; a screen colour's channels are gamma-encoded. Sent as
    is, #ff2d2d comes out pink and #ffe13a nearly white. So the weaker channels go out at (1 - s) ** 2.2."""
    return 1 - (1 - s) ** 2.2


def color(rgb) -> bytes:
    """A screen colour (0..255 each) as the garland should show it."""
    h, s, _ = colorsys.rgb_to_hsv(*(max(0, min(255, c)) / 255 for c in rgb))
    s = strip_saturation(s)
    led = colorsys.hsv_to_rgb(strip_hue(h * 360) / 360, s, 1)
    led = [c * (1 - (1 - g) * (1 - s)) for c, g in zip(led, WHITE_GAINS)]
    h, s, _ = colorsys.rgb_to_hsv(*led)
    return raw_color(round(h * 360) % 360, round(s * 1000))


if __name__ == "__main__":
    assert color([255, 0, 0]).hex() == "bc0406000003e8000055"
    assert color([0, 255, 0]).hex() == "bc0406006b03e8000055"      # green goes out as 107
    assert color([0, 0, 255]).hex() == "bc040600f603e8000055"      # blue as 246
    assert color([255, 255, 255]).hex() == "bc04060000010e000055"  # white as a touch of red
    print("ok")
