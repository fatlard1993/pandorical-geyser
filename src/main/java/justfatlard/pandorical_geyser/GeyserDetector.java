package justfatlard.pandorical_geyser;

import org.geysermc.geyser.api.GeyserApi;

import java.util.UUID;

final class GeyserDetector {
	private GeyserDetector() {}

	static boolean isBedrock(UUID player) {
		return GeyserApi.api().isBedrockPlayer(player);
	}
}
