# Marca do Comunicador

O símbolo é a **luz de tally** (vermelha, "no ar") com duas **ondas de voz** (âmbar). As cores são as mesmas do app.

| Cor | Código | Uso |
|---|---|---|
| Grafite | `#22262B` | fundo |
| Tally | `#E5323F` | você está no ar |
| Âmbar | `#F2A516` | outra pessoa falando |
| Claro | `#EEF1F3` | texto |

Arquivos:
- `logo-marca.svg`: símbolo quadrado (também usado como favicon).
- `logo-horizontal.svg`: símbolo + nome. O texto usa a fonte Archivo; se ela não estiver instalada, o navegador usa uma parecida.
- `icone-512.png`: ícone para a Google Play. `icone-192.png` e `favicon-32.png`: web.
- `og-imagem.png`: prévia de link (1200 × 630), usada pela página de convite no WhatsApp.

Tudo sai do script `gerar_marca.py`, a partir da mesma geometria, inclusive os ícones do app Android (`app/src/main/res`: ícone adaptável, versão monocromática para o Android 13+ e ícone da notificação). Para mudar algo (cores, proporções), edite o começo do script e rode:

```
pip install pillow
python3 gerar_marca.py caminho/para/Archivo.ttf
```

A fonte Archivo é livre (Google Fonts). Depois de gerar, copie `og-imagem.png`, `icone-192.png` e `favicon-32.png` para `servidor/publico/` e `cloudflare/src/publico/`.
