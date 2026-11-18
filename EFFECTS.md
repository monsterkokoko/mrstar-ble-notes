# The 58 effects, as the garland really plays them

Measured on one garland (about 65 beads) at **speed 50**, direction **forward** (away from the controller). "Beads/s"
is how fast a pattern runs along the wire; every moving pattern runs away from the controller. Colours are listed in
the order the strip runs them, using the strip's own seven: **r**ed, **o**range, **y**ellow, **g**reen, **c**yan,
**b**lue, **p**urple (its "7 colours" are this rainbow), plus **w**hite.

On the garland its orange looks like a warm yellow and its yellow looks lime: the green LEDs are strong. Two "greens"
in a row in the jumps or the gradient are really yellow and green.

The name column is the one in [ha-findn-led-ble](https://github.com/hirotasoshu/ha-findn-led-ble).
**Bold** marks where our garland does something other than the name says.

`reference/effects.js` has a drawing model for each; the images in [docs/img](docs/img) put the filmed strip next to
the model (see the README).

## Whole strip one colour at a time

| Id | Name | What it does |
| --- | --- | --- |
| 0x01 | Automatic loop | The whole strip one colour, gliding smoothly round the hues, about 7.5 s a turn (r → y → g → c → b → p). Filmed for 40 s; it may switch to other modes over a longer run. |
| 0x04 | Colorful jumps | The 7 colours, 0.37 s each, hard cuts. |
| 0x07 | 7 colors strobe | A colour for 0.4 s, dark 0.4 s, then the next colour. |
| 0x0A | 7 colors gradient | The 7 colours, 0.66 s each, with a short dip to dark between them. |

## Rainbows along the strip

| Id | Name | What it does |
| --- | --- | --- |
| 0x02 | Symphony | A smooth rainbow longer than the strip (a full turn over ~105 beads), creeping along at ~14 beads/s; any one bead goes round the hues in ~7.5 s. |
| 0x03 | Colorful energy | Every bead one of the 7 colours in rainbow order, so the pattern repeats every 7 beads; runs at ~14 beads/s. |

## Flowing blocks ("running water")

Blocks of 8 beads of each colour, edges blending over about a bead, running at ~13 beads/s.

| Id | Name | Colours on ours |
| --- | --- | --- |
| 0x37 | 7 colors flowing water | r o y g c b p |
| 0x38 | Blue-green-red running water | b g r |
| 0x39 | Purple-green-yellow running water | **p c y** (cyan, not green) |
| 0x3A | Red-green running water | r g |
| 0x3B | Green-blue running water | g b |
| 0x3C | Yellow-blue running water | y b |
| 0x3D | Yellow-cyan running water | y c |
| 0x3E | Blue-purple running water | **c p** (cyan, not blue) |

## Comets ("tailing")

Not one comet down the strip: a comet every **13 beads**, running at ~13 beads/s. Each is a bright head, 6 beads of
tail at ~85 %, 4 beads fading (65, 45, 28, 12 %), 2 dark. In 0x4C each comet is the next colour.

| Id | Name | Colours |
| --- | --- | --- |
| 0x4C | 7 colors tailing | r o y g c b p, one per comet |
| 0x4D–0x53 | Red / Green / Blue / Yellow / Cyan / Purple / White tailing | that one colour |

## Running dots

One bright bead every **6 beads** on a dim strip (~16 %), running at ~10.5 beads/s; each dot the next colour.

| Id | Name | Colours on ours |
| --- | --- | --- |
| 0x5B | 7 colors running | r o y g c b p |
| 0x5C | Blue-green-red running | b g r |
| 0x5D | Purple-cyan-yellow running | **purple only**, same as 0x59 |
| 0x54–0x5A | Red / Green / Blue / Yellow / Cyan / Purple / White running | that one colour |

## Fluttering

| Id | Name | What it does |
| --- | --- | --- |
| 0x1A | Colorful fluttering | Like running, but a bright bead every **4** beads; 7 colours, one per dot. |
| 0x1B | Red-green-blue fluttering | The same in r g b. |

## Not what the name says: 0x2D–0x36

The library calls these "light-dark transition". On ours none of them fade the strip in and out.

| Id | Name | What ours does |
| --- | --- | --- |
| 0x2D | 7 colors light-dark transition | **Moving bands**: 13 beads of a colour, 4 dark, running at ~7.5 beads/s; colours r o y g c b p |
| 0x2E | Blue-red-green light-dark transition | **Moving bands**, b r g |
| 0x2F | Violet-green-yellow light-dark transition | **Moving bands**, p c y |
| 0x30–0x36 | Red / Green / Blue / Cyan / Yellow / Purple / White light-dark transition | **The rainbow running over that colour**: 7 beads of the next rainbow colour, 6 beads of the background colour, running at ~7 beads/s |

## Opening-closing (0x23–0x2C)

6.5 s per colour, then the next colour. On ours it does not open from the middle. Positions are beads from the
controller, on a ~65-bead garland:

1. 0–1.4 s: light spreads from bead ~18 away from the controller, and at the same time from the far end back
   towards it, until the two meet.
2. 1.4–3.2 s: holds.
3. 3.2–5.0 s: the stretch from bead 18 back to the controller fills.
4. All lit for ~0.3 s, then dark for ~1.3 s.

| Id | Name | Colours |
| --- | --- | --- |
| 0x23 | 7 colors opening-closing | r o y g c b p, one per cycle |
| 0x24 | Red-green-blue opening-closing | r g b |
| 0x25 | Yellow-cyan-purple opening-closing | y c p |
| 0x26–0x2C | Red / Green / Blue / Yellow / Cyan / Purple / White opening-closing | that one colour |

## Brushing (0x1D–0x20)

The next colour sweeps from the **far end back to the controller** at ~13 beads/s (about 5 s for the whole garland),
then holds until the next sweep. Started from off, the first colour sweeps over darkness.

| Id | Name | Cycle | Colours |
| --- | --- | --- | --- |
| 0x1D | Colorful brushing | 12.2 s | r o y g c b p |
| 0x1E | Red-green-blue color brushing | 12.2 s | r g b |
| 0x1F | Yellow-cyan-purple color brushing | 12.2 s | y c p |
| 0x20 | Colorful brush color brush closed-pull | **6.2 s** | r o y g c b p: the same sweep, twice as often |

## Not measured

- **Other speeds.** Speed is `BC 08 01 <1..100> 55`, higher is faster, but how the timings scale with it was not
  measured. Everything above is at speed 50.
- **Direction "backward"** (`BC 07 01 00 55`) was not filmed. It presumably runs the patterns towards the
  controller.
- **The ~14 beads the camera could not see** (one loop of our garland). Their place in the patterns follows from the
  beads on either side of them.
