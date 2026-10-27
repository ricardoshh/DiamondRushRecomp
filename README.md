# DiamondRushRecomp

Port nativo para PC do Diamond Rush (Gameloft, 2006, J2ME). O bytecode original do jogo roda sem
alterações numa JVM desktop, sobre uma reimplementação própria das APIs `javax.microedition`
(MIDP 2.0 / CLDC 1.1 / MMAPI / RMS).

**Este repositório não contém nenhum código nem asset da Gameloft.** Você precisa do seu próprio
`.jar` do jogo. A versão testada é `DiamondRush_s40v3a-N6233.jar` (Nokia S40, 240x320, v1.0.3).

## Download

Baixe o zip do Windows ou o tar.gz do Linux em **Releases**, extraia e coloque o `.jar` do jogo ao
lado do executável (ou escolha o arquivo quando o programa pedir). O Java já vem incluído.

## Como compilar e rodar

Requer JDK 21+. Pode ser o do PATH ou um JDK portátil em `tools\jdk`. As bibliotecas (ASM e
LWJGL/GLFW) são baixadas do Maven Central para `lib/` na primeira compilação (ver `deps.txt`).

```bash
powershell -ExecutionPolicy Bypass -File build.ps1
```

```bash
powershell -ExecutionPolicy Bypass -File run.ps1
```

No Linux, use `./build.sh` e depois `java -jar build/diamondrush-recomp.jar`.

O launcher usa o jar salvo em `settings.properties`; se não houver, procura um `.jar` J2ME na pasta.
Se ainda não achar, abre uma janela para você escolher o arquivo. Também dá para passar o caminho:
`run.ps1 C:\caminho\jogo.jar`.

### Gerar a release (.exe)

```bash
powershell -ExecutionPolicy Bypass -File package.ps1 -Version 0.1.0
```

Gera `dist\DiamondRushRecomp\DiamondRushRecomp.exe` com um Java reduzido embutido (~48 MB) e o zip
`dist\DiamondRushRecomp-0.1.0-windows-x64.zip`. No Linux: `./package.sh 0.1.0`.

O GitHub Actions (`.github/workflows/release.yml`) compila Windows e Linux a cada push. Para
publicar uma release, crie e envie uma tag:

```bash
git tag v0.1.0
```

```bash
git push origin v0.1.0
```

## Controles

| Teclado | Controle | Celular |
|---|---|---|
| Setas / WASD | D-pad / analógico esquerdo | Direcional |
| Enter / Espaço | A | OK (5) |
| F1 / Q | Start | Soft key esquerda |
| F2 / E / Esc | B / Back | Soft key direita |
| 0–9 (teclado ou numpad) | X = 7, Y = 9, LT = 0, RT = 5 | Teclas numéricas |
| numpad `*` / `/` | LB / RB | `*` e `#` |

| Tecla | Função |
|---|---|
| F10 / Shift+F10 | Trocar filtro de imagem |
| F11 / Alt+Enter / duplo clique | Tela cheia (sem bordas) |

O controle é lido via GLFW, que já reconhece Xbox, PlayStation, Switch Pro e muitos genéricos.
Para outros controles, coloque um `gamecontrollerdb.txt` (formato SDL) ao lado do launcher.

### Filtros de imagem

| Filtro | Resultado |
|---|---|
| `pixel` | Escala inteira, pixels perfeitos, com bordas pretas |
| `sharp` (padrão) | Preenche a tela mantendo os pixels nítidos (prescale inteiro + bilinear) |
| `smooth` | Bilinear, mais suave |
| `scale2x`, `scale3x`, `scale4x` | Suavizam diagonais e contornos de pixel art (AdvMAME) |

## Configurações

O `settings.properties` é criado na primeira execução e guarda o filtro, a tela cheia, o caminho
do jar e o mapeamento dos botões do controle.

Os saves (RMS) ficam em `saves\`. Na versão empacotada, os erros vão para `log.txt`.
## Como funciona

| Parte | Arquivos |
|---|---|
| Launcher: lê o manifest, carrega o jar e inicia o MIDlet | `src/drpc/Launcher.java` |
| Carregamento + patches de bytecode (ASM) | `src/drpc/GameClassLoader.java` |
| Janela, tela cheia e teclado | `src/drpc/Screen.java` |
| Filtros de upscaling | `src/drpc/Upscaler.java` |
| Controle (GLFW) e junção das entradas | `src/drpc/Gamepad.java`, `src/drpc/Input.java` |
| Configurações | `src/drpc/Settings.java` |
| Gráficos (Canvas, GameCanvas, Graphics, Image) sobre Java2D | `src/javax/microedition/lcdui/` |
| Música MIDI via `javax.sound.midi` | `src/drpc/MidiPlayer.java`, `src/javax/microedition/media/` |
| Saves em arquivo | `src/javax/microedition/rms/RecordStore.java` |

### Patches de bytecode atuais

- `Class.getResourceAsStream` → `drpc.Runtime.classResource`. O jogo carrega recursos via
  `"string".getClass()`, que no celular lê do jar do jogo, mas no desktop retornaria `null`.

## Depuração

```bash
tools\jdk\bin\java -Ddrpc.dump=frames -Ddrpc.keys=6000:-5,2500:-5 -jar build\diamondrush-recomp.jar
```

- `drpc.dump=<pasta>` salva um PNG do frame por segundo.
- `drpc.keys=<atraso ms>:<keycode>,...` envia teclas automaticamente. Os códigos Nokia são:
  -1 cima, -2 baixo, -3 esq, -4 dir, -5 OK, -6/-7 soft keys, 48–57 dígitos.

Para estudar o código do jogo, use o Vineflower (`tools\vineflower.jar`) e gere a saída **fora** do
repositório.
