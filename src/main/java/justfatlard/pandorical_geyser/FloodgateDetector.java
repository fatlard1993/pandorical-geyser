package justfatlard.pandorical_geyser;

import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;

/** Kept apart from {@link Main} so Floodgate's classes are loaded only where Floodgate is installed. */
final class FloodgateDetector {
	private FloodgateDetector() {}

	static boolean isBedrock(UUID player) {
		return FloodgateApi.getInstance().isFloodgatePlayer(player);
	}
}
