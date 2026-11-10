package glassland.end_poem.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import glassland.end_poem.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

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

/** Client-only full-screen MP4 player for the End credits. */
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
        if (started) return; // init() runs again after a window resize.
        started = true;

        try {
            Path tempVideo = copyVideoResourceToTempFile();
            if (tempVideo == null) {
                LOGGER.warn("Resource {} is missing; closing the End screen without a crash.", VIDEO_RESOURCE);
                closeAndReturnToGameSoon();
                return;
            }
            startDecoder(tempVideo);
        } catch (Exception e) {
            LOGGER.error("Could not prepare the End poem video", e);
            closeAndReturnToGameSoon();
        }
    }

    /** JCodec needs a seekable channel, so copy the classpath/resource-pack asset to a temp file. */
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

    /** Decoding and pixel conversion happen off the render thread. */
    private void decodeLoop(Path file) {
        try (SeekableByteChannel channel = NIOUtils.readableChannel(file.toFile())) {
            videoChannel = channel;
            if (stopDecoder) return;

            // FrameGrab has no separate close(); closing its underlying channel releases the file.
            FrameGrab grab = FrameGrab.createFrameGrab(channel);
            long deadline = System.nanoTime();

            while (!stopDecoder) {
                Picture picture = grab.getNativeFrame();
                if (picture == null) break;

                BufferedImage image = AWTUtil.toBufferedImage(picture);
                int width = image.getWidth();
                int height = image.getHeight();
                int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

                // NativeImage.setPixelRGBA expects packed RGBA bytes (little-endian).
                // BufferedImage.getRGB returns ARGB, so swap the red and blue channels.
                for (int i = 0; i < pixels.length; i++) {
                    int argb = pixels[i];
                    pixels[i] = (argb & 0xFF00FF00)
                            | ((argb & 0x00FF0000) >>> 16)
                            | ((argb & 0x000000FF) << 16);
                }

                pendingFrame.set(new DecodedFrame(width, height, pixels));
                deadline += FRAME_NS;
                long sleepNs = deadline - System.nanoTime();
                if (sleepNs > 0) LockSupport.parkNanos(sleepNs);
            }
        } catch (Exception e) {
            if (!stopDecoder) LOGGER.error("Error decoding video resource {}", VIDEO_RESOURCE, e);
        } finally {
            videoChannel = null;
            decoderFinished = true;
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOGGER.debug("Could not delete temporary video {}", file, e);
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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
                    LOGGER.error("Could not upload decoded video frame", e);
                    finishAndReturnToGame();
                    return;
                }
            }
        }

        if (videoTexture != null) {
            // Full-window stretch. In Mojang mappings 1.21.1 this method is GuiGraphics.blit.
            graphics.blit(TEXTURE_ID, 0, 0, 0.0F, 0.0F,
                    this.width, this.height, videoWidth, videoHeight);
        }

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

            NativeImage image = new NativeImage(NativeImage.Format.RGBA,
                    videoWidth, videoHeight, false);
            writePixels(image, frame.pixels, videoWidth, videoHeight);

            videoTexture = new DynamicTexture(image);
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
            soundInstance = SimpleSoundInstance.forMusic(ModSounds.END_POEM.get());
            Minecraft.getInstance().getSoundManager().play(soundInstance);
        } catch (Exception e) {
            LOGGER.error("Could not start the End poem OGG sound event", e);
        }
    }

    private void releaseVideoTexture() {
        if (videoTexture == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (textureRegistered) {
            // release() unregisters and closes the manager-owned DynamicTexture.
            minecraft.getTextureManager().release(TEXTURE_ID);
            textureRegistered = false;
        } else {
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

    /** Idempotent cleanup for sound, decoder channel, pending pixels, and DynamicTexture. */
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
                // The decoder thread also closes it through try-with-resources.
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
