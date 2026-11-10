package glassland.end_poem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, GlasslandEndPoem.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> END_POEM =
            SOUND_EVENTS.register("end_poem", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(GlasslandEndPoem.MOD_ID, "end_poem")));

    private ModSounds() {}
}
