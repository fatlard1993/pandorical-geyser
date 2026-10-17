package justfatlard.pandorical_geyser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java assets as Pandorical holds them. A dedicated server has none of Minecraft's own, so its
 * block models come from a bundled copy.
 */
final class JavaModels {
	private static final JsonObject VANILLA_PARENTS = resource("vanilla-block-models.json");

	/** Each asset parsed once, as every state of a block reads the same files. Callers don't change what they get. */
	private static final Map<String, Optional<JsonObject>> PARSED = new ConcurrentHashMap<>();

	private JavaModels() {}

	static Optional<JsonObject> json(String path) {
		return PARSED.computeIfAbsent(path, unused -> PandoricalApi.content().asset(path)
			.map(bytes -> JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject()));
	}

	static Optional<JsonObject> model(Identifier id) {
		Optional<JsonObject> held = json("assets/" + id.getNamespace() + "/models/" + id.getPath() + ".json");
		if (held.isPresent()) return held;
		return VANILLA_PARENTS.get(id.toString()) instanceof JsonObject vanilla ? Optional.of(vanilla) : Optional.empty();
	}

	static JsonObject resource(String name) {
		try (var in = JavaModels.class.getResourceAsStream("/pandorical-geyser/" + name)) {
			if (in == null) throw new IllegalStateException("Missing bundled resource " + name);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException e) {
			throw new IllegalStateException("Unreadable bundled resource " + name, e);
		}
	}
}
