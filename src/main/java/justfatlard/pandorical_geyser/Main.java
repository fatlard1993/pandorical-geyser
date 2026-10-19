package justfatlard.pandorical_geyser;

import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.geysermc.api.Geyser;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomBlocksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.Predicate;

public class Main implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("pandorical-geyser");

	private static Predicate<UUID> bedrock = asked(GeyserDetector::isBedrock);

	@Override
	public void onInitialize() {
		// Floodgate first: it knows a player from the handshake, before configuration, where Geyser
		// alone may still be reading the login reply. Pandorical asks again at joining for that case.
		if (FabricLoader.getInstance().isModLoaded("floodgate")) {
			bedrock = asked(FloodgateDetector::isBedrock).or(bedrock);
			LOGGER.info("[pandorical-geyser] Recognising Bedrock players through Floodgate");
		}
		PandoricalApi.detectBedrock(bedrock);
		PandoricalApi.bridgeBedrockCamera(new BedrockCamera());

		// Geyser defines its blocks, items and packs when the server has started, and its API may not exist
		// before its own initializer has run.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			if (!Geyser.isRegistered()) {
				LOGGER.error("[pandorical-geyser] Geyser didn't start, so Bedrock players get no modded blocks or items");
				return;
			}
			var events = GeyserApi.api().eventBus();
			events.subscribe(EventRegistrar.of(this), GeyserDefineCustomBlocksEvent.class, ModdedBlocks::register);
			events.subscribe(EventRegistrar.of(this), GeyserDefineCustomItemsEvent.class, ModdedItems::register);
			events.subscribe(EventRegistrar.of(this), GeyserDefineResourcePacksEvent.class, BedrockPack::register);
		});
	}

	public static boolean isBedrock(UUID player) {
		return bedrock.test(player);
	}

	/**
	 * Asked at every login, Java players' included, from inside Fabric's registry sync: a detector
	 * that throws must answer "not Bedrock" rather than turn every login away.
	 */
	private static Predicate<UUID> asked(Predicate<UUID> detector) {
		return player -> {
			try {
				return detector.test(player);
			} catch (RuntimeException e) {
				LOGGER.warn("[pandorical-geyser] A Bedrock check failed for {}; taking them as Java", player, e);
				return false;
			}
		};
	}
}
