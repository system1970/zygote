from PIL import Image
ref = Image.open(r"C:\Users\guhan\AppData\Roaming\Hermes\composer-images\composer_2026-08-13_16-47-26-314_0b743f.png").convert("RGB")
demo = Image.open(r"C:\Users\guhan\orkestrate_ai\zygote-pwa\zygote-pwa-demo.png").convert("RGB")
# scale both to same height, side by side
H = 900
w_ref = int(ref.width * H / ref.height)
w_demo = int(demo.width * H / demo.height)
ref2 = ref.resize((w_ref, H))
demo2 = demo.resize((w_demo, H))
gap = 24
canvas = Image.new("RGB", (w_ref + gap + w_demo, H), (40, 40, 44))
canvas.paste(ref2, (0, 0))
canvas.paste(demo2, (w_ref + gap, 0))
out = r"C:\Users\guhan\orkestrate_ai\zygote-pwa\zygote-side-by-side.png"
canvas.save(out)
print("saved", out, canvas.size)
