import json
import sys
from pathlib import Path

try:
    import qrcode
except ImportError:
    raise SystemExit("Missing dependency. Run: python -m pip install qrcode[pil]")

if len(sys.argv) != 3:
    raise SystemExit("Usage: python provisioning/make_qr.py input.json output.png")

source = Path(sys.argv[1])
target = Path(sys.argv[2])
payload = json.loads(source.read_text(encoding="utf-8"))
text = json.dumps(payload, separators=(",", ":"))

qr = qrcode.QRCode(version=None, error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=10, border=4)
qr.add_data(text)
qr.make(fit=True)
image = qr.make_image(fill_color="black", back_color="white")
target.parent.mkdir(parents=True, exist_ok=True)
image.save(target)
print(target)
