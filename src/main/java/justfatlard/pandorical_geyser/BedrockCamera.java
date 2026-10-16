package justfatlard.pandorical_geyser;

import justfatlard.pandorical.api.CameraApi;
import justfatlard.pandorical.api.NotUnderstood;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.bedrock.camera.CameraData;
import org.geysermc.geyser.api.bedrock.camera.CameraPerspective;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pandorical's camera hints for Bedrock players, through Geyser's camera. Bedrock can force a
 * perspective but has no camera distance or zoom, so those are reported as not understood, once a
 * session, the way a Java client reports what it can't do.
 */
final class BedrockCamera implements CameraApi {
	private final Map<UUID, Set<String>> reported = new ConcurrentHashMap<>();

	BedrockCamera() {
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> reported.remove(handler.getPlayer().getUUID()));
	}

	@Override
	public void setDistance(ServerPlayer player, float distance) {
		unsupported(player, "distance");
	}

	@Override
	public void setPerspective(ServerPlayer player, String perspective) {
		CameraData camera = camera(player);
		if (camera == null) return;
		if (perspective == null || perspective.isEmpty()) {
			camera.clearCameraInstructions();
			return;
		}
		CameraPerspective forced = switch (perspective) {
			case "first_person" -> CameraPerspective.FIRST_PERSON;
			case "third_person_back" -> CameraPerspective.THIRD_PERSON;
			case "third_person_front" -> CameraPerspective.THIRD_PERSON_FRONT;
			default -> null;
		};
		if (forced == null) {
			report(player, NotUnderstood.PERSPECTIVE, perspective);
			return;
		}
		camera.forceCameraPerspective(forced);
	}

	@Override
	public void zoom(ServerPlayer player, float factor) {
		unsupported(player, "zoom");
	}

	@Override
	public void reset(ServerPlayer player) {
		CameraData camera = camera(player);
		if (camera != null) camera.clearCameraInstructions();
	}

	private void unsupported(ServerPlayer player, String hint) {
		report(player, NotUnderstood.CAMERA_HINT, hint);
	}

	private void report(ServerPlayer player, String kind, String value) {
		if (reported.computeIfAbsent(player.getUUID(), unused -> ConcurrentHashMap.newKeySet()).add(kind + ":" + value)) {
			PandoricalApi.reportNotUnderstood(player, kind, value);
		}
	}

	private static CameraData camera(ServerPlayer player) {
		GeyserConnection connection = GeyserApi.api().connectionByUuid(player.getUUID());
		return connection == null ? null : connection.camera();
	}
}
