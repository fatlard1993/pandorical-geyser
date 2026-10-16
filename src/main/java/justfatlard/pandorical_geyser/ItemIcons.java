package justfatlard.pandorical_geyser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bedrock icons for modded items, taken from the Java assets Pandorical holds. Only flat item models
 * ({@code item/generated} and {@code item/handheld}, drawn from {@code layer0}) convert; anything
 * else is left to {@link ModdedItems}.
 */
final class ItemIcons {
	record Icon(String key, String packPath, byte[] png, boolean handheld) {}

	private static Map<Identifier, Icon> icons;

	private ItemIcons() {}

	static synchronized Map<Identifier, Icon> all() {
		if (icons == null) icons = build();
		return icons;
	}

	static Optional<Icon> of(Identifier item) {
		return Optional.ofNullable(all().get(item));
	}

	private static Map<Identifier, Icon> build() {
		Map<Identifier, Icon> built = new LinkedHashMap<>();
		Map<Identifier, String> unconverted = new LinkedHashMap<>();
		for (var key : BuiltInRegistries.ITEM.keySet()) {
			if (key.getNamespace().equals("minecraft")) continue;
			try {
				convert(key).ifPresentOrElse(icon -> built.put(key, icon), () -> unconverted.put(key, "not a flat item model with its own texture"));
			} catch (RuntimeException | IOException e) {
				unconverted.put(key, e.toString());
			}
		}
		Main.LOGGER.info("[pandorical-geyser] Converted {} item icons for Bedrock", built.size());
		unconverted.forEach((item, why) -> Main.LOGGER.debug("[pandorical-geyser] No flat icon for {}: {}", item, why));
		return built;
	}

	private static Optional<Icon> convert(Identifier item) throws IOException {
		Identifier model = itemModel(item);
		Map<String, String> textures = new HashMap<>();
		boolean flat = false;
		boolean handheld = false;
		// Child textures win, so a parent's entry is only kept where the child left a gap.
		for (int depth = 0; model != null && depth < 16; depth++) {
			String path = model.getPath();
			if (model.getNamespace().equals("minecraft") && (path.equals("item/generated") || path.equals("builtin/generated"))) {
				flat = true;
				break;
			}
			if (model.getNamespace().equals("minecraft") && path.equals("item/handheld")) {
				flat = handheld = true;
				break;
			}
			Optional<JsonObject> json = JavaModels.json("assets/" + model.getNamespace() + "/models/" + path + ".json");
			if (json.isEmpty()) break;
			if (json.get().get("textures") instanceof JsonObject own) {
				own.entrySet().forEach(e -> textures.putIfAbsent(e.getKey(), e.getValue().getAsString()));
			}
			model = json.get().has("parent") ? Identifier.parse(json.get().get("parent").getAsString()) : null;
		}
		if (!flat) return Optional.empty();

		String layer0 = textures.get("layer0");
		for (int hops = 0; layer0 != null && layer0.startsWith("#") && hops < 16; hops++) {
			layer0 = textures.get(layer0.substring(1));
		}
		if (layer0 == null) return Optional.empty();
		Identifier texture = Identifier.parse(layer0);
		String base = "assets/" + texture.getNamespace() + "/textures/" + texture.getPath();
		Optional<byte[]> png = PandoricalApi.content().asset(base + ".png");
		if (png.isEmpty()) return Optional.empty();
		byte[] frame = PandoricalApi.content().asset(base + ".png.mcmeta").isPresent() ? firstFrame(png.get()) : png.get();

		String key = BedrockPack.key(item.getNamespace() + "_" + item.getPath());
		return Optional.of(new Icon(key, BedrockPack.fitPath("textures/items/" + key, item.toString()), frame, handheld));
	}

	/** The model an item's {@code items/} definition names first; the pre-definition default otherwise. */
	private static Identifier itemModel(Identifier item) {
		return JavaModels.json("assets/" + item.getNamespace() + "/items/" + item.getPath() + ".json")
			.map(definition -> firstModel(definition.get("model")))
			.orElseGet(() -> Identifier.fromNamespaceAndPath(item.getNamespace(), "item/" + item.getPath()));
	}

	private static Identifier firstModel(JsonElement element) {
		if (element instanceof JsonObject object) {
			if (object.get("model") instanceof JsonElement model && model.isJsonPrimitive()) return Identifier.parse(model.getAsString());
			for (var entry : object.entrySet()) {
				Identifier found = firstModel(entry.getValue());
				if (found != null) return found;
			}
		} else if (element != null && element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				Identifier found = firstModel(child);
				if (found != null) return found;
			}
		}
		return null;
	}

	/** An animated texture is a vertical strip of square frames; Bedrock would squash all of them. */
	private static byte[] firstFrame(byte[] png) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
		if (image == null || image.getHeight() <= image.getWidth()) return png;
		var out = new ByteArrayOutputStream();
		ImageIO.write(image.getSubimage(0, 0, image.getWidth(), image.getWidth()), "png", out);
		return out.toByteArray();
	}
}
