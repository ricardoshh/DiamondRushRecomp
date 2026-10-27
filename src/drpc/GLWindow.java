package drpc;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.swing.SwingUtilities;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

/**
 * The game window, drawn with OpenGL on its own thread and synchronized to the monitor (vsync).
 *
 * Every refresh: build the game frame (interpolated when enabled), upload it as a texture and draw
 * the blurred background (mipmaps), the UI underlay (bezel, legend), the picture (scaling filter in
 * a shader) and the UI overlay (menu, messages). The UI layers are plain ARGB images drawn by
 * Screen with Java2D on the Swing thread, only when they change.
 *
 * GLFW must be used from a single thread, so this thread also owns keyboard and gamepad input;
 * events are forwarded to Screen on the Swing thread.
 */
final class GLWindow {

    private static final String VERTEX = String.join("\n",
            "#version 330 core",
            "layout(location = 0) in vec2 aPos;",
            "uniform vec4 uRect;  // x, y, w, h in framebuffer pixels, top-left origin",
            "uniform vec2 uView;",
            "out vec2 vUv;",
            "void main() {",
            "    vec2 p = uRect.xy + aPos * uRect.zw;",
            "    gl_Position = vec4(p.x / uView.x * 2.0 - 1.0, 1.0 - p.y / uView.y * 2.0, 0.0, 1.0);",
            "    vUv = aPos;",
            "}");

    private static final String FRAGMENT = String.join("\n",
            "#version 330 core",
            "in vec2 vUv;",
            "out vec4 color;",
            "uniform sampler2D uTex;",
            "uniform int uMode;       // 0 opaque, 1 sharp bilinear, 2 blur, 3 straight-alpha layer,",
            "                         // 4 Scale2x pass, 5 Scale3x pass (into an offscreen texture)",
            "uniform vec2 uTexSize;",
            "uniform vec2 uScale;     // output pixels per texel",
            "uniform float uLod;",
            "uniform float uDim;",
            "ivec2 srcSize;",
            "vec3 px(ivec2 c) { return texelFetch(uTex, clamp(c, ivec2(0), srcSize - 1), 0).rgb; }",
            "bool eq(vec3 a, vec3 b) { return all(equal(a, b)); }",
            "// AdvMAME Scale2x/Scale3x. Rows grow downwards, like the uploaded game image, and the",
            "// offscreen texture keeps that orientation because output rows are taken from gl_FragCoord.",
            "vec3 scaleNx(int n) {",
            "    srcSize = textureSize(uTex, 0);",
            "    ivec2 o = ivec2(gl_FragCoord.xy);",
            "    ivec2 p = o / n;",
            "    ivec2 q = o - p * n;",
            "    vec3 A = px(p + ivec2(-1, -1)), B = px(p + ivec2(0, -1)), C = px(p + ivec2(1, -1));",
            "    vec3 D = px(p + ivec2(-1, 0)),  E = px(p),                F = px(p + ivec2(1, 0));",
            "    vec3 G = px(p + ivec2(-1, 1)),  H = px(p + ivec2(0, 1)),  I = px(p + ivec2(1, 1));",
            "    if (eq(B, H) || eq(D, F)) return E;",
            "    if (n == 2) {",
            "        if (q == ivec2(0, 0)) return eq(D, B) ? D : E;",
            "        if (q == ivec2(1, 0)) return eq(B, F) ? F : E;",
            "        if (q == ivec2(0, 1)) return eq(D, H) ? D : E;",
            "        return eq(H, F) ? F : E;",
            "    }",
            "    int k = q.y * 3 + q.x;",
            "    if (k == 0) return eq(D, B) ? D : E;",
            "    if (k == 1) return (eq(D, B) && !eq(E, C)) || (eq(B, F) && !eq(E, A)) ? B : E;",
            "    if (k == 2) return eq(B, F) ? F : E;",
            "    if (k == 3) return (eq(D, B) && !eq(E, G)) || (eq(D, H) && !eq(E, A)) ? D : E;",
            "    if (k == 4) return E;",
            "    if (k == 5) return (eq(B, F) && !eq(E, I)) || (eq(H, F) && !eq(E, C)) ? F : E;",
            "    if (k == 6) return eq(D, H) ? D : E;",
            "    if (k == 7) return (eq(D, H) && !eq(E, I)) || (eq(H, F) && !eq(E, G)) ? H : E;",
            "    return eq(H, F) ? F : E;",
            "}",
            "void main() {",
            "    if (uMode == 4 || uMode == 5) {",
            "        color = vec4(scaleNx(uMode == 4 ? 2 : 3), 1.0);",
            "    } else if (uMode == 1) {",
            "        // Sharp bilinear: hard texel interiors, 1-output-pixel smooth edges.",
            "        vec2 texel = vUv * uTexSize;",
            "        vec2 s = max(uScale, vec2(1.0));",
            "        vec2 region = 0.5 - 0.5 / s;",
            "        vec2 d = fract(texel) - 0.5;",
            "        vec2 f = (d - clamp(d, -region, region)) * s + 0.5;",
            "        color = vec4(texture(uTex, (floor(texel) + f) / uTexSize).rgb, 1.0);",
            "    } else if (uMode == 2) {",
            "        color = vec4(textureLod(uTex, vUv, uLod).rgb * uDim, 1.0);",
            "    } else if (uMode == 3) {",
            "        color = texture(uTex, vUv);",
            "    } else {",
            "        color = vec4(texture(uTex, vUv).rgb, 1.0);",
            "    }",
            "}");

    private final Screen screen;
    private final Settings settings;
    private final String title;
    private final BufferedImage icon;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    // Frames handed over by the game when interpolation is off (triple buffer, see present()).
    private final Object swapLock = new Object();
    private BufferedImage back = newFrame();
    private BufferedImage ready = newFrame();
    private BufferedImage shown = newFrame();
    private boolean fresh;

    // UI layers from Screen
    private volatile BufferedImage underLayer;
    private volatile BufferedImage overLayer;
    private volatile int layerVersion;

    private long window;
    private int program;
    private int vao;
    private int uRect;
    private int uView;
    private int uMode;
    private int uTexSize;
    private int uScale;
    private int uLod;
    private int uDim;
    private int gameTex;
    private int underTex;
    private int overTex;
    private int underW;
    private int underH;
    private int overW;
    private int overH;
    private boolean hasUnder;
    private boolean hasOver;
    private int uploadedLayers = -1;

    private int fbW;
    private int fbH;
    private boolean fullscreen;
    private int[] windowedBounds;
    private int swapInterval = -1;
    private long lastClick;

    private final BufferedImage interpFrame = newFrame();

    GLWindow(Screen screen, Settings settings, String title, BufferedImage icon) {
        this.screen = screen;
        this.settings = settings;
        this.title = title;
        this.icon = icon;
    }

    private static BufferedImage newFrame() {
        return new BufferedImage(Runtime.SCREEN_W, Runtime.SCREEN_H, BufferedImage.TYPE_INT_RGB);
    }

    void start(int width, int height, boolean startFullscreen) {
        Thread t = new Thread(() -> run(width, height, startFullscreen), "render");
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    /** Runs on the render thread before the next frame. */
    void runLater(Runnable r) {
        tasks.add(r);
    }

    // ---------------------------------------------------------------- inputs from other threads

    /** A finished game frame (only used when interpolation is off). Called by the game thread. */
    void present(BufferedImage src) {
        back.setData(src.getRaster());
        synchronized (swapLock) {
            BufferedImage t = ready;
            ready = back;
            back = t;
            fresh = true;
        }
    }

    /** New UI layers (either may be null). Called by Screen on the Swing thread. */
    void setLayers(BufferedImage under, BufferedImage over) {
        underLayer = under;
        overLayer = over;
        layerVersion++;
    }

    void setFullscreen(boolean on) {
        runLater(() -> applyFullscreen(on));
    }

    void setWindowSize(int w, int h) {
        runLater(() -> {
            if (!fullscreen) {
                glfwSetWindowSize(window, w, h);
            }
        });
    }

    void updateSwapInterval() {
        runLater(() -> swapInterval = -1);
    }

    // ---------------------------------------------------------------- render thread

    private void run(int width, int height, boolean startFullscreen) {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            System.err.println("[drpc] glfwInit failed");
            System.exit(1);
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_AUTO_ICONIFY, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(width, height, title, 0, 0);
        if (window == 0) {
            System.err.println("[drpc] could not create an OpenGL 3.3 window");
            System.exit(1);
        }
        GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (mode != null) {
            glfwSetWindowPos(window, (mode.width() - width) / 2, Math.max(30, (mode.height() - height) / 2));
        }
        setIcon();
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("[drpc] OpenGL " + glGetString(GL_VERSION) + " on " + glGetString(GL_RENDERER)
                + ", GLFW " + glfwGetVersionString());
        initGl();
        installCallbacks();
        Gamepad gamepad = Gamepad.create(settings, screen);

        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetFramebufferSize(window, w, h);
        onFramebufferSize(w[0], h[0]);
        glfwShowWindow(window);
        if (startFullscreen) {
            applyFullscreen(true);
        }

        Stats stats = new Stats();
        long limiterNext = System.nanoTime();
        while (true) {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
            if (swapInterval < 0) {
                configureSwap();
            }
            glfwPollEvents();
            gamepad.poll();

            long t0 = System.nanoTime();
            BufferedImage frame = gameFrame(t0);
            long t1 = System.nanoTime();
            Debug.onPresent(frame);
            drawFrame(frame);
            long t2 = System.nanoTime();
            Debug.onWindow(fbW, fbH, this::readPixels);
            stats.frame(t1 - t0, t2 - t1);
            glfwSwapBuffers(window);

            // Without a usable vsync divisor, pace to the requested rate by sleeping.
            int fps = Interpolator.targetFps();
            if (swapInterval == 0 && fps > 0) {
                limiterNext += 1_000_000_000L / fps;
                long wait = limiterNext - System.nanoTime();
                if (wait > 0) {
                    java.util.concurrent.locks.LockSupport.parkNanos(wait);
                } else {
                    limiterNext = System.nanoTime();
                }
            }
        }
    }

    private BufferedImage gameFrame(long now) {
        if (Interpolator.renderFrame(interpFrame, now)) {
            return interpFrame;
        }
        synchronized (swapLock) {
            if (fresh) {
                BufferedImage t = shown;
                shown = ready;
                ready = t;
                fresh = false;
            }
        }
        return shown;
    }

    /**
     * Vsync at the monitor rate when the target rate is at least the refresh rate; every Nth
     * refresh when it divides evenly (60 on 120 Hz); otherwise no vsync and a sleep limiter.
     */
    private void configureSwap() {
        int refresh = 60;
        long monitor = fullscreen ? glfwGetWindowMonitor(window) : glfwGetPrimaryMonitor();
        GLFWVidMode mode = glfwGetVideoMode(monitor != 0 ? monitor : glfwGetPrimaryMonitor());
        if (mode != null && mode.refreshRate() > 0) {
            refresh = mode.refreshRate();
        }
        int fps = Interpolator.targetFps();
        if (Boolean.getBoolean("drpc.novsync")) {
            swapInterval = 0; // benchmark: no vsync, sleep limiter only
        } else if (fps <= 20 || fps >= refresh) {
            swapInterval = 1;
        } else if (refresh % fps == 0) {
            swapInterval = refresh / fps;
        } else {
            swapInterval = 0;
        }
        glfwSwapInterval(swapInterval);
        System.out.println("[drpc] display " + refresh + " Hz, target " + fps + " fps, swap interval " + swapInterval);
    }

    // ---------------------------------------------------------------- drawing

    private void initGl() {
        program = glCreateProgram();
        int vs = shader(GL_VERTEX_SHADER, VERTEX);
        int fs = shader(GL_FRAGMENT_SHADER, FRAGMENT);
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("shader link: " + glGetProgramInfoLog(program));
        }
        glUseProgram(program);
        uRect = glGetUniformLocation(program, "uRect");
        uView = glGetUniformLocation(program, "uView");
        uMode = glGetUniformLocation(program, "uMode");
        uTexSize = glGetUniformLocation(program, "uTexSize");
        uScale = glGetUniformLocation(program, "uScale");
        uLod = glGetUniformLocation(program, "uLod");
        uDim = glGetUniformLocation(program, "uDim");
        glUniform1i(glGetUniformLocation(program, "uTex"), 0);

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, new float[] {0, 0, 1, 0, 0, 1, 1, 1}, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);

        gameTex = texture();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, Runtime.SCREEN_W, Runtime.SCREEN_H, 0, GL_BGRA,
                GL_UNSIGNED_INT_8_8_8_8_REV, (int[]) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        underTex = texture();
        overTex = texture();
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    private static int shader(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("shader compile: " + glGetShaderInfoLog(s));
        }
        return s;
    }

    private static int texture() {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        return t;
    }

    private static int[] pixels(BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    /** Uploads img into tex, reallocating when the size changed. Returns {w, h}. */
    private static void upload(int tex, BufferedImage img, int oldW, int oldH) {
        glBindTexture(GL_TEXTURE_2D, tex);
        int w = img.getWidth();
        int h = img.getHeight();
        if (w != oldW || h != oldH) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels(img));
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels(img));
        }
    }

    private void drawFrame(BufferedImage frame) {
        glViewport(0, 0, fbW, fbH);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        if (fbW <= 0 || fbH <= 0) {
            return;
        }
        glUseProgram(program);
        glBindVertexArray(vao);
        glUniform2f(uView, fbW, fbH);

        Upscaler.Filter filter = screen.filter();
        Upscaler.Background bg = screen.background();
        Rectangle r = Upscaler.layout(filter, bg, fbW, fbH);

        // game texture (+ mipmaps for the blurred background)
        glBindTexture(GL_TEXTURE_2D, gameTex);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, Runtime.SCREEN_W, Runtime.SCREEN_H, GL_BGRA,
                GL_UNSIGNED_INT_8_8_8_8_REV, pixels(frame));
        glGenerateMipmap(GL_TEXTURE_2D); // always: the mipmapped texture is incomplete without them
        boolean border = r.width < fbW || r.height < fbH;
        if (bg != Upscaler.Background.BLACK && border) {
            double cover = Math.max((double) fbW / Runtime.SCREEN_W, (double) fbH / Runtime.SCREEN_H) * 1.15;
            int cw = (int) (Runtime.SCREEN_W * cover);
            int ch = (int) (Runtime.SCREEN_H * cover);
            glUniform1i(uMode, 2);
            glUniform1f(uLod, 4.5f);
            glUniform1f(uDim, 0.45f);
            quad((fbW - cw) / 2, (fbH - ch) / 2, cw, ch);
        }

        syncLayers();
        if (hasUnder) {
            glEnable(GL_BLEND);
            glBindTexture(GL_TEXTURE_2D, underTex);
            glUniform1i(uMode, 3);
            quad(0, 0, fbW, fbH);
            glDisable(GL_BLEND);
        }

        // the picture
        int tex = gameTex;
        int tw = Runtime.SCREEN_W;
        int th = Runtime.SCREEN_H;
        if (filter.factor > 1) {
            // pixel-art filter on the GPU, into offscreen textures (Scale4x = Scale2x twice)
            if (filter.factor == 3) {
                tex = scalePass(gameTex, tw, th, 3, 0);
            } else {
                tex = scalePass(gameTex, tw, th, 2, 0);
                if (filter.factor == 4) {
                    tex = scalePass(tex, tw * 2, th * 2, 2, 1);
                }
            }
            tw *= filter.factor;
            th *= filter.factor;
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, fbW, fbH);
            glUniform2f(uView, fbW, fbH);
        }
        glBindTexture(GL_TEXTURE_2D, tex);
        int mag = filter == Upscaler.Filter.PIXEL ? GL_NEAREST : GL_LINEAR;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, mag);
        glUniform1i(uMode, filter == Upscaler.Filter.SMOOTH || filter == Upscaler.Filter.PIXEL ? 0 : 1);
        glUniform2f(uTexSize, tw, th);
        glUniform2f(uScale, (float) r.width / tw, (float) r.height / th);
        glUniform1f(uLod, 0);
        quad(r.x, r.y, r.width, r.height);

        if (hasOver) {
            glEnable(GL_BLEND);
            glBindTexture(GL_TEXTURE_2D, overTex);
            glUniform1i(uMode, 3);
            quad(0, 0, fbW, fbH);
            glDisable(GL_BLEND);
        }
    }

    private final int[] fbo = new int[2];
    private final int[] fboTex = new int[2];
    private final int[] fboW = new int[2];
    private final int[] fboH = new int[2];

    /** Runs the Scale2x/Scale3x shader from src (w x h) into offscreen slot; returns its texture. */
    private int scalePass(int src, int w, int h, int n, int slot) {
        int ow = w * n;
        int oh = h * n;
        if (fbo[slot] == 0 || fboW[slot] != ow || fboH[slot] != oh) {
            if (fbo[slot] == 0) {
                fbo[slot] = glGenFramebuffers();
                fboTex[slot] = texture();
            }
            glBindTexture(GL_TEXTURE_2D, fboTex[slot]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, ow, oh, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, (int[]) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[slot]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTex[slot], 0);
            fboW[slot] = ow;
            fboH[slot] = oh;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[slot]);
        glViewport(0, 0, ow, oh);
        glUniform2f(uView, ow, oh);
        glBindTexture(GL_TEXTURE_2D, src);
        glUniform1i(uMode, n == 2 ? 4 : 5);
        quad(0, 0, ow, oh);
        return fboTex[slot];
    }

    private void syncLayers() {
        int v = layerVersion;
        if (v == uploadedLayers) {
            return;
        }
        uploadedLayers = v;
        BufferedImage under = underLayer;
        BufferedImage over = overLayer;
        hasUnder = under != null;
        hasOver = over != null;
        if (under != null) {
            upload(underTex, under, underW, underH);
            underW = under.getWidth();
            underH = under.getHeight();
        }
        if (over != null) {
            upload(overTex, over, overW, overH);
            overW = over.getWidth();
            overH = over.getHeight();
        }
    }

    private void quad(int x, int y, int w, int h) {
        glUniform4f(uRect, x, y, w, h);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    private int[] readPixels() {
        int[] px = new int[fbW * fbH];
        glReadPixels(0, 0, fbW, fbH, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, px);
        return px;
    }

    // ---------------------------------------------------------------- window

    private void installCallbacks() {
        glfwSetFramebufferSizeCallback(window, (win, w, h) -> onFramebufferSize(w, h));
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (action == GLFW_REPEAT) {
                return;
            }
            boolean down = action == GLFW_PRESS;
            if (down && (key == GLFW_KEY_F11 || (key == GLFW_KEY_ENTER && (mods & GLFW_MOD_ALT) != 0))) {
                applyFullscreen(!fullscreen);
                return;
            }
            SwingUtilities.invokeLater(() -> screen.onKey(key, mods, down));
        });
        glfwSetWindowFocusCallback(window, (win, focused) -> {
            if (!focused) {
                SwingUtilities.invokeLater(screen::onFocusLost);
            }
        });
        glfwSetWindowIconifyCallback(window, (win, iconified) ->
                SwingUtilities.invokeLater(() -> screen.onIconify(iconified)));
        glfwSetWindowCloseCallback(window, win -> {
            glfwSetWindowShouldClose(win, false);
            SwingUtilities.invokeLater(screen::onCloseRequest);
        });
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                long now = System.currentTimeMillis();
                if (now - lastClick < 350) {
                    applyFullscreen(!fullscreen);
                    lastClick = 0;
                } else {
                    lastClick = now;
                }
            }
        });
    }

    private void onFramebufferSize(int w, int h) {
        fbW = w;
        fbH = h;
        SwingUtilities.invokeLater(() -> screen.onResize(w, h));
    }

    /** Fullscreen at the monitor's current mode (no mode switch) on the monitor holding the window. */
    private void applyFullscreen(boolean on) {
        if (on == fullscreen) {
            return;
        }
        fullscreen = on;
        if (on) {
            int[] x = new int[1];
            int[] y = new int[1];
            int[] w = new int[1];
            int[] h = new int[1];
            glfwGetWindowPos(window, x, y);
            glfwGetWindowSize(window, w, h);
            windowedBounds = new int[] {x[0], y[0], w[0], h[0]};
            long monitor = monitorFor(x[0] + w[0] / 2, y[0] + h[0] / 2);
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN);
        } else {
            int[] b = windowedBounds != null ? windowedBounds : new int[] {100, 100, 480, 640};
            glfwSetWindowMonitor(window, 0, b[0], b[1], b[2], b[3], GLFW_DONT_CARE);
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        }
        swapInterval = -1; // the refresh rate may differ between monitors
        SwingUtilities.invokeLater(() -> screen.onFullscreenChanged(on));
    }

    private static long monitorFor(int cx, int cy) {
        org.lwjgl.PointerBuffer monitors = glfwGetMonitors();
        if (monitors != null) {
            for (int i = 0; i < monitors.limit(); i++) {
                long m = monitors.get(i);
                int[] mx = new int[1];
                int[] my = new int[1];
                glfwGetMonitorPos(m, mx, my);
                GLFWVidMode mode = glfwGetVideoMode(m);
                if (mode != null && cx >= mx[0] && cx < mx[0] + mode.width() && cy >= my[0] && cy < my[0] + mode.height()) {
                    return m;
                }
            }
        }
        return glfwGetPrimaryMonitor();
    }

    private void setIcon() {
        if (icon == null) {
            return;
        }
        int w = icon.getWidth();
        int h = icon.getHeight();
        ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = icon.getRGB(x, y);
                buf.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >> 24));
            }
        }
        buf.flip();
        try (GLFWImage.Buffer images = GLFWImage.malloc(1)) {
            images.get(0).set(w, h, buf);
            glfwSetWindowIcon(window, images);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    // ---------------------------------------------------------------- stats

    /** -Ddrpc.stats=true: prints frame rate and render time every 2 seconds. */
    private static final class Stats {
        private final boolean on = Boolean.getBoolean("drpc.stats");
        private long start = System.nanoTime();
        private long last = start;
        private int frames;
        private long game;
        private long draw;
        private long worst;

        void frame(long gameNanos, long drawNanos) {
            if (!on) {
                return;
            }
            long now = System.nanoTime();
            worst = Math.max(worst, now - last);
            last = now;
            game += gameNanos;
            draw += drawNanos;
            frames++;
            if (now - start >= 2_000_000_000L) {
                System.out.printf("[stats] %.1f fps, game frame %.2f ms, draw %.2f ms, worst gap %.1f ms%n",
                        frames * 1e9 / (now - start), game / 1e6 / frames, draw / 1e6 / frames, worst / 1e6);
                start = now;
                frames = 0;
                game = 0;
                draw = 0;
                worst = 0;
            }
        }
    }
}
