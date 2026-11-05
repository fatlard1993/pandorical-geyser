package justfatlard.pandorical_geyser.mixin;

import justfatlard.pandorical_geyser.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Data packs load once every mod has initialized, and every block and item is registered by then. */
@Mixin(net.minecraft.server.Main.class)
abstract class ServerMainMixin {
	@Inject(method = "loadOrCreateConfig", at = @At("HEAD"))
	private static void pandoricalGeyser$convertAhead(CallbackInfoReturnable<?> cir) {
		Main.convertAhead();
	}
}
