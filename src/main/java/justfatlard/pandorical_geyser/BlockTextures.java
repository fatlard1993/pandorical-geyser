package justfatlard.pandorical_geyser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bedrock terrain textures for Java texture ids. A mod's own texture is copied into the pack; a
 * vanilla one points at Bedrock's copy, which every client already has, under its Bedrock name.
 */
final class BlockTextures {
	private static final JsonObject BEDROCK_NAMES = JavaModels.resource("bedrock-block-textures.json");
	/** Bedrock's own stand-in for a texture it can't find. */
	private static final String MISSING = "textures/misc/missing_texture";

	/** A terrain_texture.json entry; {@code png} is set when the file has to ship in the pack. */
	record Texture(String key, String path, byte[] png) {}

	private static final Map<String, Texture> byJavaId = new LinkedHashMap<>();

	private BlockTextures() {}

	/** Empty when the texture is neither held by Pandorical nor a vanilla block texture. */
	static synchronized Optional<Texture> of(Identifier texture) {
		String javaId = texture.toString();
		Texture known = byJavaId.get(javaId);
		if (known != null) return Optional.of(known);

		Texture resolved = null;
		Optional<byte[]> png = PandoricalApi.content().asset("assets/" + texture.getNamespace() + "/textures/" + texture.getPath() + ".png");
		if (png.isPresent()) {
			String key = BedrockPack.key(texture.getNamespace() + "_" + texture.getPath());
			resolved = new Texture(key, BedrockPack.fitPath("textures/blocks/" + key, javaId), png.get());
		} else if (texture.getNamespace().equals("minecraft") && texture.getPath().startsWith("block/")) {
			String name = texture.getPath().substring("block/".length());
			String bedrockName = bedrockName(name);
			resolved = new Texture("pandorical_vanilla_" + bedrockName.replace('/', '_'), "textures/blocks/" + bedrockName, null);
		}
		if (resolved == null) return Optional.empty();
		byJavaId.put(javaId, resolved);
		return Optional.of(resolved);
	}

	/** Bedrock's name for a vanilla texture; where Bedrock splits one into several, the first. */
	private static String bedrockName(String javaName) {
		JsonElement mapped = BEDROCK_NAMES.get(javaName);
		if (mapped == null) return javaName;
		return mapped.isJsonArray() ? mapped.getAsJsonArray().get(0).getAsString() : mapped.getAsString();
	}

	static synchronized Texture missing() {
		return byJavaId.computeIfAbsent("", unused -> new Texture("pandorical_missing", MISSING, null));
	}

	/** In the order they were first asked for, which a restart repeats. */
	static synchronized Map<String, Texture> all() {
		return new LinkedHashMap<>(byJavaId);
	}
}
