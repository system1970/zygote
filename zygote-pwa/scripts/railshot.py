from PIL import Image
im = Image.open(r"C:\Users\guhan\orkestrate_ai\zygote-pwa\zygote-sidebar-open.png").convert("RGB")
sb = im.crop((0, 0, 265, im.height))
sb2 = sb.resize((sb.width * 2, sb.height * 2), Image.LANCZOS)
out = r"C:\Users\guhan\orkestrate_ai\zygote-pwa\zygote-sidebar.png"
sb2.save(out)
print("saved", out, sb2.size)
