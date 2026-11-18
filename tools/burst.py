"""Split a /burst body into frames: load(bin, header) -> [(t_ms, PIL image)]."""
import io, json, re
from PIL import Image
def load(bin_path, head_path):
    head = open(head_path, encoding="utf-8", errors="replace").read()
    meta = json.loads(re.search(r"^X-Shot: (.*)$", head, re.M | re.I).group(1))
    data = open(bin_path, "rb").read()
    return meta, [(t, Image.open(io.BytesIO(data[o:o + n])).convert("RGB")) for o, n, t in meta["frames"]]
if __name__ == "__main__":
    import sys
    meta, fr = load(sys.argv[1], sys.argv[2])
    print(meta["size"], len(fr), [t for t, _ in fr])
    fr[len(fr) // 2][1].save(sys.argv[3])
