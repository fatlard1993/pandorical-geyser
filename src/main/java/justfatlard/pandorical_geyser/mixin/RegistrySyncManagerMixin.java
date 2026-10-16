package justfatlard.pandorical_geyser.mixin;

import justfatlard.pandorical_geyser.Main;
import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HACK: reaches into Fabric API's internals, which promise nothing between versions. Fabric
 * refuses any client that can't take its registry sync while a synced registry holds a modded
 * entry, and offers no way to exempt one. Geyser translates for Bedrock players itself, so their
 * connections skip the check; every other client is still held to it.
 */
@Mixin(value = RegistrySyncManager.class, remap = false)
abstract class RegistrySyncManagerMixin {
	@Inject(method = "configureClient", at = @At("HEAD"), cancellable = true)
	private static void pandoricalGeyser$skipBedrock(ServerConfigurationPacketListenerImpl handler, MinecraftServer server, CallbackInfo ci) {
		if (Main.isBedrock(handler.getOwner().id())) {
			ci.cancel();
		}
	}
}
