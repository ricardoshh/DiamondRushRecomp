# DiamondRushRecomp

[![Baixar para Windows](https://img.shields.io/badge/Baixar-Windows%20x64-0078D6?style=for-the-badge&logo=windows)](https://github.com/ricardoshh/DiamondRushRecomp/releases/latest/download/DiamondRushRecomp-windows-x64.zip)
[![Baixar para Linux](https://img.shields.io/badge/Baixar-Linux%20x64-E95420?style=for-the-badge&logo=linux&logoColor=white)](https://github.com/ricardoshh/DiamondRushRecomp/releases/latest/download/DiamondRushRecomp-linux-x64.tar.gz)
[![Todas as versões](https://img.shields.io/github/v/release/ricardoshh/DiamondRushRecomp?style=for-the-badge&label=vers%C3%A3o)](https://github.com/ricardoshh/DiamondRushRecomp/releases)

Port nativo para PC do Diamond Rush (Gameloft, 2006, J2ME). O bytecode original do jogo roda sem
alterações numa JVM desktop, sobre uma reimplementação própria das APIs `javax.microedition`
(MIDP 2.0 / CLDC 1.1 / MMAPI / RMS).

**Este repositório não contém nenhum código nem asset da Gameloft.** Você precisa do seu próprio
`.jar` do jogo. A versão testada é `DiamondRush_s40v3a-N6233.jar` (Nokia S40, 240x320, v1.0.3).

## Download

1. Clique no botão do seu sistema acima (ou veja todas as versões em
   [Releases](https://github.com/ricardoshh/DiamondRushRecomp/releases)) e extraia o arquivo.
2. Abra o `DiamondRushRecomp.exe` (Windows) ou `bin/DiamondRushRecomp` (Linux).
3. No launcher, clique em **Importar jogo (.jar)** e escolha o seu `.jar` do Diamond Rush.
4. Clique em **Jogar**.

O Java já vem incluído. Requer placa de vídeo com OpenGL 3.3.

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
`dist\DiamondRushRecomp-windows-x64.zip`. No Linux: `./package.sh 0.1.0`.

O GitHub Actions (`.github/workflows/release.yml`) compila Windows e Linux a cada push. Para
publicar uma release, crie e envie uma tag:

```bash
git tag v0.1.0
```

```bash
git push origin v0.1.0
```

### Build pessoal com o jogo embutido

```bash
powershell -ExecutionPolicy Bypass -File package.ps1 -Version 0.2.0 -GameJar DiamondRush_s40v3a-N6233.jar
```

O exe abre direto, sem pedir o arquivo. O zip sai com `PESSOAL-nao-distribuir` no nome: ele contém
o jogo da Gameloft e **não pode ser publicado** nas releases. O GitHub Actions nunca gera esse tipo.

## Controles

| Teclado | Controle | Celular |
|---|---|---|
| Setas / WASD | D-pad / analógico esquerdo | Direcional |
| Enter / Espaço | A | OK (5) |
| F1 / Q | Start | Soft key esquerda |
| F2 / E / Esc | B / Back | Soft key direita |
| 0–9 (teclado ou numpad) | X = 7, Y = 9, LT = 0, RT = 5 | Teclas numéricas |
| numpad `*` / `/` | LB / RB | `*` e `#` |

| Teclado | Controle | Função |
|---|---|---|
| F12 / Tab | Guide ou Back+Start | Menu de opções |
| F10 / Shift+F10 | — | Trocar filtro de imagem |
| F11 / Alt+Enter / duplo clique | — | Tela cheia (sem bordas) |

O controle é lido via GLFW, que já reconhece Xbox, PlayStation, Switch Pro e muitos genéricos.
Para outros controles, coloque um `gamecontrollerdb.txt` (formato SDL) ao lado do launcher.

O jogo detecta o último dispositivo usado (teclado ou controle). Quando há espaço ao lado da imagem,
uma legenda mostra os botões desse dispositivo, com ícones de teclado, Xbox ou PlayStation.

## Vídeo: 60, 120, 144 fps

O jogo original roda a 20 ticks por segundo, com lógica e desenho presos ao mesmo ciclo. Para ter
movimento fluido sem mudar a velocidade do jogo:

1. Cada tick, os comandos de desenho da tela são gravados (`DrawList`).
2. Cada sprite é emparelhado com ele mesmo no tick anterior, e os quadros intermediários são
   desenhados com as posições interpoladas (`Interpolator`). O fundo do mapa, que o jogo guarda num
   buffer circular de tiles, é redesenhado a partir da câmera interpolada.
3. A janela é desenhada em OpenGL 3.3 (`GLWindow`), sincronizada ao monitor (vsync). Os filtros
   (nítido, Scale2x/3x/4x) e o fundo desfocado são shaders.

Medido numa GTX 950 em tela cheia 1080p com Scale4x: 60 fps cravados com vsync e 240 fps sem vsync,
com ~1,4 ms por quadro para montar a imagem do jogo e ~0,15 ms para desenhar na GPU.

Requer uma placa de vídeo com OpenGL 3.3 (praticamente qualquer GPU a partir de 2010).

## Launcher

Ao abrir o programa aparece o launcher:

- **Importar jogo (.jar)**: copia o seu `.jar` para a pasta `games` do programa e mostra o ícone,
  o nome e a versão do jogo.
- **Abas Vídeo, Jogo e Controles**: as mesmas opções do menu do jogo, mais o mapeamento de cada
  botão do controle.
- **Controles conectados**: lista os controles reconhecidos.
- **Jogar**: abre o jogo com as configurações escolhidas.

Desmarcando "Mostrar este launcher ao iniciar", o programa abre direto no jogo. Para voltar ao
launcher, use "Abrir launcher" no menu de opções do jogo (F12).

## Menu de opções

Abra com F12 (ou Guide / Back+Start). O jogo fica congelado, inclusive a música, enquanto o menu
está aberto.

| Opção | O que faz |
|---|---|
| Arquivo do jogo | Escolhe outro `.jar` e reinicia |
| Filtro de imagem | Pixel perfect, Nítido, Suave, Scale2x/3x/4x |
| Fundo / bordas | Preto, Desfoque do jogo ou Moldura em volta da imagem 3:4 |
| Taxa de quadros | Original (20), 60, 120, 144 ou 240 fps com interpolação |
| Tela cheia | Liga/desliga a tela cheia sem bordas |
| Tamanho da janela | 1x, 2x, 3x... (fora da tela cheia) |
| Velocidade do jogo | 50% a 200% da velocidade original |
| Volume | 0–100% |
| Ícones de botões | Automático, Teclado, Xbox ou PlayStation |
| Controles na lateral | Mostra/oculta a legenda de botões |

### Limitações conhecidas

- **Atraso da interpolação:** os quadros intermediários ficam um tick atrás do jogo (~50 ms). Quem
  preferir a resposta original pode escolher "Original (20)" em Taxa de quadros.
- **16:9 / 4:3 nativo:** a resolução 240x320 está fixa em ~160 pontos do código, e o mapa é
  desenhado num buffer de 240 px. Trocar só as constantes não funciona (testado). Por enquanto, a
  tela cheia usa fundo desfocado ou moldura em volta da imagem original.
- **Ícones dentro do jogo:** as dicas do tutorial (teclas 4, 6, 2, 8) são sprites do próprio jogo.
  A legenda lateral mostra os botões certos; substituir os sprites é um passo futuro.

## Configurações

O `settings.properties` é criado na primeira execução. Tudo o que o menu altera fica salvo nele, e
também o mapeamento dos botões do controle.

Os saves (RMS) ficam em `saves\`. Na versão empacotada, os erros vão para `log.txt`.

## Como funciona

| Parte | Arquivos |
|---|---|
| Launcher: lê o manifest, carrega o jar e inicia o MIDlet | `src/drpc/Launcher.java` |
| Carregamento + patches de bytecode (ASM) | `src/drpc/GameClassLoader.java` |
| Janela OpenGL, vsync, shaders de filtro, tela cheia | `src/drpc/GLWindow.java` |
| Estado da interface, legenda, mensagens, teclado | `src/drpc/Screen.java` |
| Interpolação de quadros | `src/drpc/Interpolator.java`, `src/javax/microedition/lcdui/DrawList.java` |
| Launcher (janela antes do jogo) | `src/drpc/LauncherWindow.java` |
| Menu de opções | `src/drpc/OptionsMenu.java` |
| Ícones de botões (teclado, Xbox, PlayStation) | `src/drpc/Glyphs.java` |
| Relógio do jogo (velocidade, pausa) | `src/drpc/GameClock.java` |
| Layout da imagem e moldura | `src/drpc/Upscaler.java` |
| Controle (GLFW) e junção das entradas | `src/drpc/Gamepad.java`, `src/drpc/Input.java` |
| Configurações | `src/drpc/Settings.java` |
| Gráficos (Canvas, GameCanvas, Graphics, Image) sobre Java2D | `src/javax/microedition/lcdui/` |
| Música MIDI via `javax.sound.midi` | `src/drpc/MidiPlayer.java`, `src/javax/microedition/media/` |
| Saves em arquivo | `src/javax/microedition/rms/RecordStore.java` |

### Patches de bytecode atuais

- `Class.getResourceAsStream` → `drpc.Runtime.classResource`. O jogo carrega recursos via
  `"string".getClass()`, que no celular lê do jar do jogo, mas no desktop retornaria `null`.
- `System.currentTimeMillis`, `Thread.sleep` e `Thread.yield` → `drpc.GameClock`, para controlar a
  velocidade e congelar o jogo com o menu aberto.
- `System.gc()` removido: o jogo o chama nas transições, e uma coleta completa forçada travava a
  tela por 300–400 ms.

## Depuração

```bash
tools\jdk\bin\java -Ddrpc.dump=frames -Ddrpc.keys=6000:-5,2500:-5 -jar build\diamondrush-recomp.jar
```

- `drpc.dump=<pasta>` salva um PNG do frame por segundo.
- `drpc.keys=<atraso ms>:<keycode>,...` envia teclas automaticamente. Os códigos Nokia são:
  -1 cima, -2 baixo, -3 esq, -4 dir, -5 OK, -6/-7 soft keys, 48–57 dígitos.

Para estudar o código do jogo, use o Vineflower (`tools\vineflower.jar`) e gere a saída **fora** do
repositório.
