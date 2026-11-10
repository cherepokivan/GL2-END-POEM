# Видео вместо титров Энда (Minecraft 1.21.1 / NeoForge)

Ниже — интеграционный шаблон под Mojang mappings. Важно: пример использует JCodec, который декодирует H.264 в MP4, а не произвольные кодеки MP4. Перекодируйте видео в H.264, 30 FPS. JCodec `FrameGrab` читает файл через `SeekableByteChannel`; у самого `FrameGrab` нет отдельного `close()`, поэтому закрывается канал. Для конвертации кадра используется `jcodec-javase`/`AWTUtil`.[1] [2]

> В Mojang mappings 1.21.1 классы называются `GuiGraphics` и метод отрисовки — `blit`; `DrawContext.drawTexture` — имена из Yarn mappings. `WinScreen.init()` является `protected`, без аргументов.[3] [4]

## 1. Экран и декодер: `VideoPlayerScreen.java`

Замените `glassland.end_poem` и `glpoem` на пакет и mod id проекта. Декодер работает в фоне и передаёт готовые RGBA-пиксели в UI-поток; NativeImage/DynamicTexture обновляются только из `render()`.

```java
package glassland.end_poem.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;
import org.slf4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public final class VideoPlayerScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long FRAME_NS = 1_000_000_000L / 30L;

    private static final ResourceLocation VIDEO_RESOURCE =
            ResourceLocation.fromNamespaceAndPath("glpoem", "video/end_poem.mp4");
    private static final ResourceLocation TEXTURE_ID =
            ResourceLocation.fromNamespaceAndPath("glpoem", "dynamic/end_poem_video");

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<DecodedFrame> pendingFrame = new AtomicReference<>();

    private volatile boolean stopDecoder;
    private volatile boolean decoderFinished;
    private volatile SeekableByteChannel videoChannel;
    private Thread decoderThread;
    private Path tempVideo;
    private boolean started;

    private DynamicTexture videoTexture;
    private boolean textureRegistered;
    private int videoWidth;
    private int videoHeight;
    private long lastFrameUploadNs;

    private SoundInstance soundInstance;
    private boolean soundStarted;

    public VideoPlayerScreen() {
        super(Component.empty());
    }

    @Override
    protected void init() {
        super.init();
        if (started) return; // init вызывается повторно при изменении размера окна.
        started = true;

        try {
            tempVideo = copyVideoResourceToTempFile();
            if (tempVideo == null) {
                LOGGER.warn("Не найден ресурс {} — закрываю экран титров.", VIDEO_RESOURCE);
                closeAndReturnToGameSoon();
                return;
            }
            startDecoder(tempVideo);
        } catch (Exception e) {
            LOGGER.error("Не удалось подготовить видео титров Энда", e);
            closeAndReturnToGameSoon();
        }
    }

    /** Копируем classpath/resource-pack файл в обычный файл: JCodec нужен seekable channel. */
    private Path copyVideoResourceToTempFile() throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        Optional<Resource> resource = minecraft.getResourceManager().getResource(VIDEO_RESOURCE);
        if (resource.isEmpty()) return null;

        Path temp = Files.createTempFile("glpoem-end-poem-", ".mp4");
        try (InputStream input = resource.get().open()) {
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            return temp;
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private void startDecoder(Path file) {
        decoderThread = new Thread(() -> decodeLoop(file), "glpoem-end-poem-decoder");
        decoderThread.setDaemon(true);
        decoderThread.start();
    }

    /** Декодирование/конвертация не блокируют render thread. */
    private void decodeLoop(Path file) {
        try (SeekableByteChannel channel = NIOUtils.readableChannel(file.toFile())) {
            videoChannel = channel;
            if (stopDecoder) return;

            // FrameGrab закрывается вместе с базовым каналом: у FrameGrab нет close().
            FrameGrab grab = FrameGrab.createFrameGrab(channel);
            long deadline = System.nanoTime();

            while (!stopDecoder) {
                Picture picture = grab.getNativeFrame();
                if (picture == null) break; // конец файла

                BufferedImage image = AWTUtil.toBufferedImage(picture);
                int w = image.getWidth();
                int h = image.getHeight();
                int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);

                // NativeImage.setPixelRGBA ожидает байты RGBA в little-endian packed int.
                // BufferedImage.getRGB возвращает ARGB, поэтому меняем местами R и B.
                for (int i = 0; i < pixels.length; i++) {
                    int argb = pixels[i];
                    pixels[i] = (argb & 0xFF00FF00)
                            | ((argb & 0x00FF0000) >>> 16)
                            | ((argb & 0x000000FF) << 16);
                }

                pendingFrame.set(new DecodedFrame(w, h, pixels));
                deadline += FRAME_NS;
                long sleepNs = deadline - System.nanoTime();
                if (sleepNs > 0) LockSupport.parkNanos(sleepNs);
            }
        } catch (Exception e) {
            if (!stopDecoder) LOGGER.error("Ошибка декодирования end_poem.mp4", e);
        } finally {
            videoChannel = null;
            decoderFinished = true;
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOGGER.debug("Не удалось удалить временный MP4 {}", file, e);
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Не показываем фон игры или текст титров под видео.
        graphics.fill(0, 0, this.width, this.height, 0xFF000000);

        if (closed.get()) return;
        long now = System.nanoTime();
        DecodedFrame frame = pendingFrame.get();

        if (frame != null && (videoTexture == null || now - lastFrameUploadNs >= FRAME_NS)) {
            if (pendingFrame.compareAndSet(frame, null)) {
                try {
                    uploadFrame(frame);
                    lastFrameUploadNs = now;
                    if (!soundStarted) startAudioOnFirstFrame();
                } catch (Exception e) {
                    LOGGER.error("Не удалось загрузить видеокадр", e);
                    finishAndReturnToGame();
                    return;
                }
            }
        }

        if (videoTexture != null) {
            // Растягиваем кадр на всё окно. В Mojang mappings это GuiGraphics.blit.
            graphics.blit(TEXTURE_ID, 0, 0, 0.0F, 0.0F,
                    this.width, this.height, videoWidth, videoHeight);
        }

        // Последний кадр остаётся видимым примерно один frame interval.
        if (decoderFinished && pendingFrame.get() == null
                && (lastFrameUploadNs == 0L || now - lastFrameUploadNs >= FRAME_NS)) {
            finishAndReturnToGame();
        }
    }

    private void uploadFrame(DecodedFrame frame) {
        if (videoTexture == null || frame.width != videoWidth || frame.height != videoHeight) {
            releaseVideoTexture();
            videoWidth = frame.width;
            videoHeight = frame.height;

            NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA,
                    videoWidth, videoHeight, false);
            writePixels(nativeImage, frame.pixels, videoWidth, videoHeight);

            videoTexture = new DynamicTexture(nativeImage);
            Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, videoTexture);
            textureRegistered = true;
            videoTexture.upload();
        } else {
            writePixels(videoTexture.getPixels(), frame.pixels, videoWidth, videoHeight);
            videoTexture.upload();
        }
    }

    private static void writePixels(NativeImage image, int[] pixels, int width, int height) {
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setPixelRGBA(x, y, pixels[index++]);
            }
        }
    }

    private void startAudioOnFirstFrame() {
        soundStarted = true;
        try {
            DeferredHolder<SoundEvent, SoundEvent> sound = ModSounds.END_POEM;
            soundInstance = SimpleSoundInstance.forMusic(sound.get());
            Minecraft.getInstance().getSoundManager().play(soundInstance);
        } catch (Exception e) {
            // Видео всё равно продолжается, даже если sound event не зарегистрирован.
            LOGGER.error("Не удалось запустить OGG-дорожку титров", e);
        }
    }

    private void releaseVideoTexture() {
        if (videoTexture == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (textureRegistered) {
            // TextureManager.release удаляет запись и закрывает зарегистрированную DynamicTexture.
            minecraft.getTextureManager().release(TEXTURE_ID);
            textureRegistered = false;
        } else {
            // На случай ошибки между созданием DynamicTexture и register().
            videoTexture.close();
        }
        videoTexture = null;
    }

    private void closeAndReturnToGameSoon() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen == this) finishAndReturnToGame();
        });
    }

    private void finishAndReturnToGame() {
        close();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == this) minecraft.setScreen(null);
    }

    /** Освобождает аудио, канал, временный кадр и DynamicTexture. Идемпотентен. */
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        stopDecoder = true;
        pendingFrame.set(null);
        if (decoderThread != null) decoderThread.interrupt();

        SeekableByteChannel channel = videoChannel;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Канал также закрывается try-with-resources в decoder thread.
            }
        }

        if (soundInstance != null) {
            Minecraft.getInstance().getSoundManager().stop(soundInstance);
            soundInstance = null;
        }
        releaseVideoTexture();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            finishAndReturnToGame();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        finishAndReturnToGame();
    }

    @Override
    public void removed() {
        close();
        super.removed();
    }

    private record DecodedFrame(int width, int height, int[] pixels) {}
}
```

`TextureManager.release(ResourceLocation)` убирает текстуру из менеджера и закрывает связанную `AbstractTexture`; поэтому для зарегистрированного `DynamicTexture` это предпочтительнее отдельного `close()` плюс `release()` (который мог бы закрыть её повторно). Для не зарегистрированной текстуры код вызывает `videoTexture.close()` напрямую.[5] Канал закрывается сразу при пропуске и повторно безопасно в `try-with-resources`; открытый `InputStream` существует только во время копирования ресурса.

## 2. Mixin для `WinScreen.init()`

`src/main/java/com/example/glpoem/mixin/WinScreenMixin.java`:

```java
package glassland.end_poem.mixin;

import glassland.end_poem.client.VideoPlayerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.WinScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WinScreen.class)
public abstract class WinScreenMixin {
    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void glpoem$replaceEndPoemWithVideo(CallbackInfo ci) {
        ci.cancel();
        Minecraft.getInstance().setScreen(new VideoPlayerScreen());
    }
}
```

Конфигурация `src/main/resources/glpoem.mixins.json`:

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "glassland.end_poem.mixin",
  "compatibilityLevel": "JAVA_21",
  "client": [
    "WinScreenMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```

В `src/main/resources/META-INF/neoforge.mods.toml` добавьте секцию (не внутрь `[mods]`):

```toml
[[mixins]]
config="glpoem.mixins.json"
```

NeoForge 1.21.x поддерживает объявление mixin-конфигов таким блоком `[[mixins]]` в `neoforge.mods.toml`.[6] В конфигурации Mixin-проекта должен быть включён annotation processor/refmap, если текущий MDK не настроил его автоматически.

## 3. Регистрация звука

Minecraft SoundManager воспроизводит зарегистрированные `SoundEvent`, а описания звуков задаются в `sounds.json`; стандартный формат звука Minecraft — OGG. Для длинного файла задайте `stream: true`.[7]

`src/main/java/com/example/glpoem/ModSounds.java`:

```java
package glassland.end_poem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, "glpoem");

    public static final DeferredHolder<SoundEvent, SoundEvent> END_POEM =
            SOUND_EVENTS.register("end_poem", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath("glpoem", "end_poem")));

    private ModSounds() {}
}
```

В конструкторе вашего `@Mod`-класса зарегистрируйте `ModSounds.SOUND_EVENTS` на mod event bus:

```java
public MyMod(IEventBus modBus) {
    ModSounds.SOUND_EVENTS.register(modBus);
    // Остальная инициализация мода.
}
```

`src/main/resources/assets/glpoem/sounds.json`:

```json
{
  "end_poem": {
    "sounds": [
      {
        "name": "glpoem:end_poem",
        "stream": true
      }
    ]
  }
}
```

Положите аудио как `src/main/resources/assets/glpoem/sounds/end_poem.ogg`. В sound definition путь относительно `assets/glpoem/sounds/`, поэтому указанный здесь OGG не должен лежать в `assets/glpoem/video/`. Если принципиально нужен файл рядом с MP4, используйте отдельный ресурсный pack/схему извлечения и прямое API OpenAL; для встроенного Minecraft SoundManager лучше придерживаться стандартной папки `sounds/`.

## 4. Gradle-зависимости

`jcodec-javase` нужен для `AWTUtil.toBufferedImage(Picture)`. Для разработки достаточно `implementation`; чтобы библиотека попала внутрь публикуемого mod JAR, используйте Jar-in-Jar через `jarJar` (при условии, что MDK подключил поддержку Jar-in-Jar):[2] [8]

```groovy
dependencies {
    // Компиляция и запуск в dev-среде:
    implementation 'org.jcodec:jcodec:0.2.5'
    implementation 'org.jcodec:jcodec-javase:0.2.5'

    // Для распространения вместе с модом вместо двух строк выше используйте:
    // jarJar(implementation('org.jcodec:jcodec:0.2.5')) {
    //     version { strictly '[0.2.5,0.2.6)'; prefer '0.2.5' }
    // }
    // jarJar(implementation('org.jcodec:jcodec-javase:0.2.5')) {
    //     version { strictly '[0.2.5,0.2.6)'; prefer '0.2.5' }
    // }
}
```

Не оставляйте одновременно обычные и закомментированные Jar-in-Jar объявления: выберите один вариант. В Jar-in-Jar варианте убедитесь, что выбранная версия NeoForge Gradle plugin включает конфигурацию `jarJar`.

## Пути файлов

```text
src/main/resources/
├── META-INF/
│   └── neoforge.mods.toml
├── assets/
│   └── glpoem/
│       ├── sounds.json
│       ├── sounds/
│       │   └── end_poem.ogg
│       └── video/
│           └── end_poem.mp4
└── glpoem.mixins.json
```

Проверяйте игру с коротким H.264 MP4 и OGG одинаковой длительности. SoundManager запускает звуковое событие при показе первого загруженного видеокадра, а на ESC звук немедленно останавливается и экран сбрасывается в `null`, возвращая игрока в мир.

## References

[1]: https://javadoc.io/static/org.jcodec/jcodec/0.2.5/org/jcodec/api/FrameGrab.html "JCodec FrameGrab 0.2.5 API"
[2]: https://github.com/jcodec/jcodec "JCodec project and usage examples"
[3]: https://mappings.dev/1.21.1/net/minecraft/client/gui/screens/WinScreen.html "Minecraft 1.21.1 WinScreen mappings"
[4]: https://docs.neoforged.net/docs/1.21.1/gui/screens/ "NeoForge 1.21.1 Screens"
[5]: https://mappings.dev/1.21.1/net/minecraft/client/renderer/texture/TextureManager.html "Minecraft 1.21.1 TextureManager mappings"
[6]: https://docs.neoforged.net/docs/gettingstarted/modfiles "NeoForge mod files and mixin configuration"
[7]: https://docs.neoforged.net/docs/1.21.1/resources/client/sounds/ "NeoForge 1.21.1 sounds"
[8]: https://docs.neoforged.net/toolchain/docs/dependencies/jarinjar/ "NeoForge Jar-in-Jar dependencies"
