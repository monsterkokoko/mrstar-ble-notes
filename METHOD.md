# How it was measured

Everything was measured with an old Android phone (Xiaomi, Android 8.1) on a stand facing the garland. The phone also
runs [BLE Bridge](bridge), which drives the garland, so one script can set an effect and film it.

## The camera

A phone camera left to itself is useless for colours: auto white balance "corrects" a room lit red back towards grey,
and auto exposure blows the beads out to white. So BLE Bridge's camera endpoints (Camera2, so they work from the
background service):

- set white balance to **daylight** and lock it;
- let exposure settle, then **lock** it, at **−2 EV** so the beads don't clip;
- `GET /photo?w=1600&ev=-2` returns one JPEG;
- `GET /burst?w=320&ms=8000&ev=-2` returns a run of preview frames as JPEGs back to back, with their offsets and
  timestamps in the `X-Shot` header. At 320×240 that's a steady **30 frames a second** on this phone; at 640 it
  managed 10. [tools/burst.py](../tools/burst.py) splits it.

Our phone's camera reports `legacy` hardware level, so there is no manual exposure, only compensation and lock.

## Reading the beads

1. **Find the beads.** With the garland white, average a burst and take the bright blobs.
2. **Put them in wire order.** In our view that meant up the vertical run from the controller, then along the cornice,
   then the loops. A loop of ~14 beads was out of frame. Its size came from the phase of the moving patterns: for each
   effect with a known step (7, 13, 16, 24 beads), we found which gap lines the loops up with the model, and they all
   agreed on 13–15. Watch out for beads of one run hanging close to another (loop hooks next to the cornice): they
   sit in the wrong place in the order and look like "the first bead to light".
3. **Track each bead over time.** Sample a few pixels at each bead in every frame and register frames against the
   reference (the phone slid a little on its stand). That gives a space-time image per effect: beads across, time
   down, as in the README.
4. **Classify colours against the strip's own.** Camera colours are skewed, so compare each bead with how the camera
   saw the strip's seven colours in the plain "jumps" effect, not with names.
5. **Fit.** Read off step, speed and cycle, write a model, render the same space-time image from the model and compare
   side by side until they match.

Slow effects (opening, brushing, symphony) got three 13 s bursts in a row, since 8 s doesn't hold a whole cycle.

## Colour calibration

For [COLOR.md](COLOR.md): send hue 0, 30, … 330 at full saturation, take `/photo` of each, and average the light on the
wall around the beads (pixels near a bead, not clipped). Average in linear light, then take the hue. Then send the
corrected hues and repeat to check.
