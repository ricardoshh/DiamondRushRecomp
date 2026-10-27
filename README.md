# DiamondRushRecomp

Port nativo para PC do Diamond Rush (Gameloft, 2006, J2ME). O bytecode original do jogo roda sem
alterações numa JVM desktop, sobre uma reimplementação própria das APIs `javax.microedition`
(MIDP 2.0 / CLDC 1.1 / MMAPI / RMS).

**Este repositório não contém nenhum código nem asset da Gameloft.** Você precisa do seu próprio
`.jar` do jogo. A versão testada é `DiamondRush_s40v3a-N6233.jar` (Nokia S40, 240x320, v1.0.3).

## Como compilar e rodar

Requer JDK 21+. Pode ser o do PATH ou um JDK portátil em `tools\jdk`.

```bash
powershell -ExecutionPolicy Bypass -File build.ps1
```

```bash
powershell -ExecutionPolicy Bypass -File run.ps1
```

O launcher procura um `.jar` J2ME na pasta atual. Se não achar, abre uma janela para você escolher
o arquivo. Também dá para passar o caminho: `run.ps1 C:\caminho\jogo.jar`.

## Controles

| PC | Celular |
|---|---|
| Setas / WASD | Direcional |
| Enter / Espaço | OK (5) |
| F1 / Q | Soft key esquerda |
| F2 / E / Esc | Soft key direita |
| 0–9 (teclado ou numpad) | Teclas numéricas |
| `*` / numpad `*`, `#` / numpad `/` | `*` e `#` |
| F11 / Alt+Enter | Tela cheia |

Os saves (RMS) ficam em `saves\`.

## Como funciona

| Parte | Arquivos |
|---|---|
| Launcher: lê o manifest, carrega o jar e inicia o MIDlet | `src/drpc/Launcher.java` |
| Carregamento + patches de bytecode (ASM) | `src/drpc/GameClassLoader.java` |
| Janela, escala e teclado | `src/drpc/Screen.java` |
| Gráficos (Canvas, GameCanvas, Graphics, Image) sobre Java2D | `src/javax/microedition/lcdui/` |
| Música MIDI via `javax.sound.midi` | `src/drpc/MidiPlayer.java`, `src/javax/microedition/media/` |
| Saves em arquivo | `src/javax/microedition/rms/RecordStore.java` |

### Patches de bytecode atuais

- `Class.getResourceAsStream` → `drpc.Runtime.classResource`. O jogo carrega recursos via
  `"string".getClass()`, que no celular lê do jar do jogo, mas no desktop retornaria `null`.

## Depuração

```bash
tools\jdk\bin\java -Ddrpc.dump=frames -Ddrpc.keys=6000:-5,2500:-5 -jar build\diamondrush-pc.jar
```

- `drpc.dump=<pasta>` salva um PNG do frame por segundo.
- `drpc.keys=<atraso ms>:<keycode>,...` envia teclas automaticamente. Os códigos Nokia são:
  -1 cima, -2 baixo, -3 esq, -4 dir, -5 OK, -6/-7 soft keys, 48–57 dígitos.

Para estudar o código do jogo, use o Vineflower (`tools\vineflower.jar`) e gere a saída **fora** do
repositório.
