package glassland.end_poem;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(GlasslandEndPoem.MOD_ID)
public final class GlasslandEndPoem {
    public static final String MOD_ID = "glpoem";

    public GlasslandEndPoem(IEventBus modEventBus) {
        ModSounds.SOUND_EVENTS.register(modEventBus);
    }
}
