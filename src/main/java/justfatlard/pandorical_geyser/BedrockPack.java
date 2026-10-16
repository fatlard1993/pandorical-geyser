package justfatlard.pandorical_geyser;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A Bedrock resource pack holding converted item icons, block textures and block geometry. Its
 * UUIDs come from its contents: Bedrock caches a pack by UUID and version, so a changed icon has
 * to arrive as a new pack.
 */
final class BedrockPack {
	private static final String NAME = "Pandorical content";
	/** Bedrock warns, and some platforms fail, at pack paths this long. */
	private static final int MAX_PATH = 80;

	private BedrockPack() {}

	/** A texture key from a readable name, in the characters Bedrock accepts. */
	static String key(String readable) {
		return "pandorical_" + readable.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
	}

	/** {@code path} itself when short enough with its extension, otherwise a hash of {@code source} in its folder. */
	static String fitPath(String path, String source) {
		if (path.length() + ".png".length() < MAX_PATH) return path;
		return path.substring(0, path.lastIndexOf('/') + 1) + "pandorical_" + Integer.toHexString(source.hashCode());
	}

	static void register(GeyserDefineResourcePacksEvent event) {
		try {
			Collection<ItemIcons.Icon> icons = ItemIcons.all().values();
			Map<String, JsonObject> geometries = ModdedBlocks.geometries();
			if (icons.isEmpty() && geometries.isEmpty()) return;
			Path pack = write(icons, geometries, BlockTextures.all().values());
			event.register(ResourcePack.create(PackCodec.path(pack)));
			Main.LOGGER.info("[pandorical-geyser] Sending Bedrock players {} item icons and {} block shapes in {}", icons.size(), geometries.size(), pack);
		} catch (IOException | RuntimeException e) {
			Main.LOGGER.error("[pandorical-geyser] Couldn't build the Bedrock pack; modded items and blocks will look wrong", e);
		}
	}

	private static Path write(Collection<ItemIcons.Icon> icons, Map<String, JsonObject> geometries, Collection<BlockTextures.Texture> blockTextures) throws IOException {
		var gson = new GsonBuilder().setPrettyPrinting().create();
		Map<String, byte[]> files = new LinkedHashMap<>();

		// By key throughout: the registries hand these over in an order a restart doesn't keep.
		JsonObject textureData = new JsonObject();
		for (ItemIcons.Icon icon : sorted(icons, ItemIcons.Icon::key)) {
			JsonObject entry = new JsonObject();
			entry.addProperty("textures", icon.packPath());
			textureData.add(icon.key(), entry);
			files.put(icon.packPath() + ".png", icon.png());
		}
		JsonObject itemTexture = new JsonObject();
		itemTexture.addProperty("resource_pack_name", NAME);
		itemTexture.addProperty("texture_name", "atlas.items");
		itemTexture.add("texture_data", textureData);
		files.put("textures/item_texture.json", gson.toJson(itemTexture).getBytes(StandardCharsets.UTF_8));

		JsonObject terrainData = new JsonObject();
		for (BlockTextures.Texture texture : sorted(blockTextures, BlockTextures.Texture::key)) {
			JsonObject entry = new JsonObject();
			entry.addProperty("textures", texture.path());
			terrainData.add(texture.key(), entry);
			if (texture.png() != null) files.put(texture.path() + ".png", texture.png());
		}
		JsonObject terrainTexture = new JsonObject();
		terrainTexture.addProperty("resource_pack_name", NAME);
		terrainTexture.addProperty("texture_name", "atlas.terrain");
		terrainTexture.addProperty("padding", 8);
		terrainTexture.addProperty("num_mip_levels", 4);
		terrainTexture.add("texture_data", terrainData);
		files.put("textures/terrain_texture.json", gson.toJson(terrainTexture).getBytes(StandardCharsets.UTF_8));

		for (var geometry : geometries.entrySet()) {
			String name = geometry.getKey().substring("geometry.".length());
			files.put("models/blocks/" + name + ".geo.json", gson.toJson(geometry.getValue()).getBytes(StandardCharsets.UTF_8));
		}

		// Sorted, so the same content hashes the same on every start: a new hash is a new pack
		// to every Bedrock player.
		files = new TreeMap<>(files);
		ByteBuffer hash = ByteBuffer.wrap(sha256(files));
		UUID header = new UUID(hash.getLong(), hash.getLong());
		UUID module = new UUID(hash.getLong(), hash.getLong());
		files.put("manifest.json", gson.toJson(manifest(header, module)).getBytes(StandardCharsets.UTF_8));

		Path path = FabricLoader.getInstance().getGameDir().resolve("pandorical-geyser").resolve("bedrock-content.mcpack");
		Files.createDirectories(path.getParent());
		var bytes = new ByteArrayOutputStream();
		try (var zip = new ZipOutputStream(bytes)) {
			for (var file : files.entrySet()) {
				ZipEntry entry = new ZipEntry(file.getKey());
				entry.setTime(0);
				zip.putNextEntry(entry);
				zip.write(file.getValue());
				zip.closeEntry();
			}
		}
		Files.write(path, bytes.toByteArray());
		return path;
	}

	private static <T> List<T> sorted(Collection<T> items, Function<T, String> key) {
		return items.stream().sorted(Comparator.comparing(key)).toList();
	}

	private static JsonObject manifest(UUID header, UUID module) {
		JsonObject head = new JsonObject();
		head.addProperty("name", NAME);
		head.addProperty("description", "Items and blocks from the server's mods, made by pandorical-geyser");
		head.addProperty("uuid", header.toString());
		head.add("version", version());
		JsonArray engine = new JsonArray();
		engine.add(1);
		engine.add(21);
		engine.add(0);
		head.add("min_engine_version", engine);

		JsonObject resources = new JsonObject();
		resources.addProperty("type", "resources");
		resources.addProperty("uuid", module.toString());
		resources.add("version", version());
		JsonArray modules = new JsonArray();
		modules.add(resources);

		JsonObject manifest = new JsonObject();
		manifest.addProperty("format_version", 2);
		manifest.add("header", head);
		manifest.add("modules", modules);
		return manifest;
	}

	private static JsonArray version() {
		JsonArray version = new JsonArray();
		version.add(1);
		version.add(0);
		version.add(0);
		return version;
	}

	private static byte[] sha256(Map<String, byte[]> files) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (var file : files.entrySet()) {
				digest.update(file.getKey().getBytes(StandardCharsets.UTF_8));
				digest.update(file.getValue());
			}
			return digest.digest();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
