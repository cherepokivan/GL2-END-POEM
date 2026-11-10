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
