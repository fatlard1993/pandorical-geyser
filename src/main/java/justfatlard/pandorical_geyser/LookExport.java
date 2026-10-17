package justfatlard.pandorical_geyser;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Every Bedrock look this mod made, beside the Java model it was made from, for
 * {@code tools/bedrock-check} to draw both and compare. Written only when the server is started
 * with {@code -Dpandorical-geyser.export=true}; a server without it does nothing here.
 */
final class LookExport {
	private static final String PROPERTY = "pandorical-geyser.export";

	private LookExport() {}

	static void writeIfAsked(Map<?, ModdedBlocks.Converted> blocks, Map<String, JsonObject> geometries) {
		if (!Boolean.getBoolean(PROPERTY)) return;
		Path dir = FabricLoader.getInstance().getGameDir().resolve("pandorical-geyser").resolve("export");
		try {
			Files.createDirectories(dir.resolve("textures"));
			JsonArray looks = new JsonArray();
			Set<String> textureKeys = new HashSet<>();
			for (ModdedBlocks.Converted block : blocks.values()) {
				Set<Integer> seen = new HashSet<>();
				for (var entry : block.lookOf().entrySet()) {
					// One state stands for each look; the others draw identically by construction.
					if (!seen.add(entry.getValue())) continue;
					BlockState state = entry.getKey();
					ModdedBlocks.Look look = block.looks().get(entry.getValue());
					JsonObject materials = new JsonObject();
					look.materials().forEach((name, material) -> {
						JsonObject drawn = new JsonObject();
						drawn.addProperty("texture", material.texture());
						drawn.addProperty("render", material.renderMethod());
						materials.add(name, drawn);
						textureKeys.add(material.texture());
					});
					JsonArray java = new JsonArray();
					BlockModels.javaPieces(state).forEach(java::add);
					JsonObject out = new JsonObject();
					out.addProperty("state", BlockModels.describe(state));
					out.add("geometry", geometries.get(look.geometry()));
					out.add("materials", materials);
					out.add("java", java);
					looks.add(out);
				}
			}
			JsonObject textures = new JsonObject();
			for (var entry : BlockTextures.all().entrySet()) {
				BlockTextures.Texture texture = entry.getValue();
				if (!textureKeys.contains(texture.key())) continue;
				textures.addProperty(texture.key(), entry.getKey());
				if (texture.png() != null) Files.write(dir.resolve("textures").resolve(texture.key() + ".png"), texture.png());
			}
			JsonObject file = new JsonObject();
			file.add("looks", looks);
			file.add("textures", textures);
			Files.writeString(dir.resolve("looks.json"), new GsonBuilder().create().toJson(file), StandardCharsets.UTF_8);
			Main.LOGGER.info("[pandorical-geyser] Exported {} block looks to {}", looks.size(), dir);
		} catch (IOException | RuntimeException e) {
			Main.LOGGER.warn("[pandorical-geyser] Couldn't export block looks", e);
		}
	}
}
