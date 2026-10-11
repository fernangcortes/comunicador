"""
Gera a marca do Comunicador a partir de uma única geometria:
a luz de tally (vermelha, "no ar") com duas ondas de voz (âmbar).

Saídas (nesta pasta):
  logo-marca.svg       símbolo quadrado, fundo grafite
  logo-horizontal.svg  símbolo + nome
  favicon.svg          símbolo para abas do navegador
  icone-512.png, icone-192.png, favicon-32.png, og-imagem.png (1200x630, prévia do WhatsApp)
E os ícones do app Android em app/src/main/res/.

Uso: python3 gerar_marca.py [caminho/da/fonte/Archivo.ttf]
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

AQUI = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(AQUI, "..", "app", "src", "main", "res")
FONTE = sys.argv[1] if len(sys.argv) > 1 else "/tmp/fonts/archivo.ttf"

GRAFITE = "#22262B"
TALLY = "#E5323F"
CUE = "#F2A516"
TEXTO = "#EEF1F3"
MUDO = "#98A2AD"

# Geometria no quadro de 512 x 512
S = 512
RAIO_CANTO = 112
LAMPADA = (174, 256, 78)          # cx, cy, raio
ONDAS = [(150, 40), (222, 40)]     # raio, espessura
ANGULO = 45                        # cada onda vai de -45° a +45°


def arco(cx, cy, r, ang=ANGULO):
    a = math.radians(ang)
    x = cx + r * math.cos(a)
    y1 = cy - r * math.sin(a)
    y2 = cy + r * math.sin(a)
    return f"M{x:.2f},{y1:.2f} A{r},{r} 0 0 1 {x:.2f},{y2:.2f}"


def simbolo_svg(escala=1.0, dx=0.0, dy=0.0, cor_lampada=TALLY, cor_onda=CUE):
    cx, cy, r = LAMPADA
    t = lambda v: v * escala
    partes = [f'<circle cx="{dx + t(cx):.2f}" cy="{dy + t(cy):.2f}" r="{t(r):.2f}" fill="{cor_lampada}"/>']
    for raio, esp in ONDAS:
        d = arco(dx + t(cx), dy + t(cy), t(raio))
        partes.append(f'<path d="{d}" fill="none" stroke="{cor_onda}" stroke-width="{t(esp):.2f}" stroke-linecap="round"/>')
    return "\n  ".join(partes)


def escrever(nome, conteudo):
    caminho = os.path.join(AQUI, nome)
    with open(caminho, "w", encoding="utf-8") as f:
        f.write(conteudo)
    return caminho


def svgs():
    marca = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {S} {S}" role="img" aria-label="Comunicador">
  <rect width="{S}" height="{S}" rx="{RAIO_CANTO}" fill="{GRAFITE}"/>
  {simbolo_svg()}
</svg>
'''
    escrever("logo-marca.svg", marca)
    escrever("favicon.svg", marca)

    # Horizontal: símbolo 160 px + nome. O texto usa Archivo (com alternativas do sistema).
    h = 160
    k = h / S
    horizontal = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 760 {h}" role="img" aria-label="Comunicador de set">
  <rect width="{h}" height="{h}" rx="{RAIO_CANTO * k:.2f}" fill="{GRAFITE}"/>
  {simbolo_svg(k)}
  <text x="190" y="98" font-family="Archivo, 'Archivo Narrow', system-ui, sans-serif" font-stretch="72%" font-weight="850" font-size="86" fill="currentColor">Comunicador</text>
  <text x="194" y="138" font-family="Archivo, system-ui, sans-serif" font-weight="600" font-size="26" letter-spacing="2" fill="{MUDO}">DE SET</text>
</svg>
'''
    escrever("logo-horizontal.svg", horizontal)


# ---------------------------------------------------------------------------
# PNGs desenhados direto (sem depender de conversor de SVG), com supersampling
# ---------------------------------------------------------------------------

def desenhar_simbolo(img, x0, y0, tamanho, fundo=True):
    ss = 4
    big = Image.new("RGBA", (tamanho * ss, tamanho * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    k = tamanho * ss / S
    if fundo:
        d.rounded_rectangle([0, 0, tamanho * ss - 1, tamanho * ss - 1], radius=RAIO_CANTO * k, fill=GRAFITE)
    cx, cy, r = LAMPADA
    d.ellipse([(cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k], fill=TALLY)
    for raio, esp in ONDAS:
        # o traço do PIL cresce para dentro da caixa: a caixa vai meio traço para fora
        rb = raio + esp / 2
        caixa = [(cx - rb) * k, (cy - rb) * k, (cx + rb) * k, (cy + rb) * k]
        d.arc(caixa, -ANGULO, ANGULO, fill=CUE, width=int(esp * k))
        # pontas arredondadas
        for ang in (-ANGULO, ANGULO):
            a = math.radians(ang)
            px = (cx + raio * math.cos(a)) * k
            py = (cy + raio * math.sin(a)) * k
            e = esp * k / 2
            d.ellipse([px - e, py - e, px + e, py + e], fill=CUE)
    pequeno = big.resize((tamanho, tamanho), Image.LANCZOS)
    img.alpha_composite(pequeno, (x0, y0))


def fonte(tam, peso=850, largura=72):
    f = ImageFont.truetype(FONTE, tam)
    try:
        f.set_variation_by_axes([peso, largura])
    except Exception:
        pass
    return f


def pngs():
    for tam, nome in [(512, "icone-512.png"), (192, "icone-192.png"), (32, "favicon-32.png")]:
        img = Image.new("RGBA", (tam, tam), (0, 0, 0, 0))
        desenhar_simbolo(img, 0, 0, tam)
        img.save(os.path.join(AQUI, nome))

    # Prévia de link (WhatsApp, etc.): 1200 x 630
    W, H = 1200, 630
    img = Image.new("RGBA", (W, H), GRAFITE)
    d = ImageDraw.Draw(img)
    desenhar_simbolo(img, 96, 175, 280, fundo=False)
    d.text((420, 190), "Comunicador", font=fonte(118), fill=TEXTO)
    d.text((424, 330), "Intercom do set no celular.", font=fonte(44, 500, 100), fill=MUDO)
    d.text((424, 392), "Tela travada, com ou sem internet.", font=fonte(44, 500, 100), fill=MUDO)
    d.rectangle([0, H - 14, W, H], fill=TALLY)
    img.convert("RGB").save(os.path.join(AQUI, "og-imagem.png"), optimize=True)


# ---------------------------------------------------------------------------
# Ícones do Android (vetoriais; o app pede Android 8+, que já usa ícone adaptável)
# ---------------------------------------------------------------------------

def android():
    os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
    os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)

    # Ícone adaptável: quadro de 108 dp, área segura de 66 dp no centro.
    # O símbolo (lâmpada + ondas) ocupa ~ 320 x 320 do quadro de 512; cabe na área segura.
    caixa = (118, 99, 458, 413)  # extremos do símbolo no quadro de 512 (com espessura)
    larg = caixa[2] - caixa[0]
    alt = caixa[3] - caixa[1]
    k = 60 / max(larg, alt)                      # 60 dp de símbolo, folga dentro dos 66
    dx = 54 - (caixa[0] + larg / 2) * k          # centraliza no quadro de 108
    dy = 54 - (caixa[1] + alt / 2) * k

    def paths(cor_lampada, cor_onda):
        cx, cy, r = LAMPADA
        x, y, rr = dx + cx * k, dy + cy * k, r * k
        circulo = f"M{x - rr:.3f},{y:.3f} a{rr:.3f},{rr:.3f} 0 1,0 {2 * rr:.3f},0 a{rr:.3f},{rr:.3f} 0 1,0 {-2 * rr:.3f},0"
        out = [f'    <path android:fillColor="{cor_lampada}" android:pathData="{circulo}"/>']
        for raio, esp in ONDAS:
            out.append(
                f'    <path android:strokeColor="{cor_onda}" android:strokeWidth="{esp * k:.3f}" '
                f'android:strokeLineCap="round" android:pathData="{arco(x, y, raio * k)}"/>'
            )
        return "\n".join(out)

    def vetor(cor_lampada, cor_onda, tamanho=108):
        return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Gerado por marca/gerar_marca.py -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{tamanho}dp" android:height="{tamanho}dp"
    android:viewportWidth="108" android:viewportHeight="108">
{paths(cor_lampada, cor_onda)}
</vector>
'''

    with open(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), "w") as f:
        f.write(vetor(TALLY, CUE))
    with open(os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"), "w") as f:
        f.write(vetor("#FFFFFF", "#FFFFFF"))
    with open(os.path.join(RES, "drawable", "ic_launcher_background.xml"), "w") as f:
        f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="{GRAFITE}"/>
</shape>
''')
    adaptavel = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
'''
    for nome in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(RES, "mipmap-anydpi-v26", nome), "w") as f:
            f.write(adaptavel)

    # Ícone da notificação: 24 dp, só a silhueta (o Android pinta de branco).
    k2 = 20 / max(larg, alt)
    dx2 = 12 - (caixa[0] + larg / 2) * k2
    dy2 = 12 - (caixa[1] + alt / 2) * k2
    cx, cy, r = LAMPADA
    x, y, rr = dx2 + cx * k2, dy2 + cy * k2, r * k2
    circulo = f"M{x - rr:.3f},{y:.3f} a{rr:.3f},{rr:.3f} 0 1,0 {2 * rr:.3f},0 a{rr:.3f},{rr:.3f} 0 1,0 {-2 * rr:.3f},0"
    ondas = "\n".join(
        f'    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="{esp * k2:.3f}" '
        f'android:strokeLineCap="round" android:pathData="{arco(x, y, raio * k2)}"/>'
        for raio, esp in ONDAS
    )
    with open(os.path.join(RES, "drawable", "ic_stat_comunicador.xml"), "w") as f:
        f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Gerado por marca/gerar_marca.py -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="{circulo}"/>
{ondas}
</vector>
''')


if __name__ == "__main__":
    svgs()
    pngs()
    android()
    print("Marca gerada.")
