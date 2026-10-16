package justfatlard.pandorical_geyser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.geysermc.geyser.api.block.custom.CustomBlockPermutation;
import org.geysermc.geyser.api.block.custom.CustomBlockState;
import org.geysermc.geyser.api.block.custom.NonVanillaCustomBlockData;
import org.geysermc.geyser.api.block.custom.component.BoxComponent;
import org.geysermc.geyser.api.block.custom.component.CustomBlockComponents;
import org.geysermc.geyser.api.block.custom.component.GeometryComponent;
import org.geysermc.geyser.api.block.custom.component.MaterialInstance;
import org.geysermc.geyser.api.block.custom.nonvanilla.JavaBlockState;
import org.geysermc.geyser.api.block.custom.nonvanilla.JavaBoundingBox;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomBlocksEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every modded block as a Geyser custom block. Geyser draws a block state it doesn't know as air,
 * which leaves Bedrock players walking into blocks they can't see.
 *
 * <p>Each distinct look among a block's states (geometry, textures, boxes, light) becomes one
 * Bedrock permutation, picked by the {@link #VARIANT} properties. A state whose Java model can't be
 * read still gets its shape, as a box with the best texture to hand.</p>
 */
final class ModdedBlocks {
	/** Bedrock asks for 16 values at most per property, so variants past 16 spill into a second. */
	private static final String VARIANT = "pandorical:v";
	private static final String VARIANT_HIGH = "pandorical:w";
	private static final int MAX_VARIANTS = 16 * 16;

	record Material(String texture, String renderMethod, String tintMethod) {}

	record Look(String geometry, Map<String, Material> materials, List<BoxComponent> collision, BoxComponent selection, int lightEmission, int lightDampening, float hardness) {}

	record Converted(Block block, org.geysermc.geyser.api.util.Identifier bedrockId, List<Look> looks, Map<BlockState, Integer> lookOf) {}

	private static Map<Block, Converted> converted;
	/** The blocks Geyser took; an item may only place one of these. */
	private static final Set<Block> registered = ConcurrentHashMap.newKeySet();
	private static final Map<String, JsonObject> geometries = new TreeMap<>();

	private ModdedBlocks() {}

	static synchronized Map<Block, Converted> all() {
		if (converted == null) converted = build();
		return converted;
	}

	static Optional<org.geysermc.geyser.api.util.Identifier> bedrockId(Block block) {
		if (!registered.contains(block)) return Optional.empty();
		return Optional.ofNullable(all().get(block)).map(Converted::bedrockId);
	}

	/** Every state of each block Geyser took, block by block. */
	static List<List<BlockState>> registeredStates() {
		return all().values().stream()
			.filter(block -> registered.contains(block.block()))
			.map(block -> List.copyOf(block.lookOf().keySet()))
			.toList();
	}

	/** Geometry files for the pack, by identifier. */
	static synchronized Map<String, JsonObject> geometries() {
		all();
		return new TreeMap<>(geometries);
	}

	static void register(GeyserDefineCustomBlocksEvent event) {
		int states = 0;
		for (Converted block : all().values()) {
			try {
				// Every override built before any is registered, so a state that can't be described
				// leaves the block out whole rather than half there.
				NonVanillaCustomBlockData data = data(block);
				Map<JavaBlockState, CustomBlockState> overrides = new LinkedHashMap<>();
				for (var entry : block.lookOf().entrySet()) {
					overrides.put(javaState(entry.getKey()), customState(data, block.looks().size(), entry.getValue()));
				}
				event.register(data);
				overrides.forEach(event::registerOverride);
				registered.add(block.block());
				states += overrides.size();
			} catch (RuntimeException e) {
				Main.LOGGER.warn("[pandorical-geyser] Bedrock players won't see {}: {}", block.bedrockId(), e.getMessage());
			}
		}
		Main.LOGGER.info("[pandorical-geyser] Mapped {} modded blocks ({} states) for Bedrock", registered.size(), states);
	}

	private static Map<Block, Converted> build() {
		Map<Block, Converted> built = new LinkedHashMap<>();
		Map<Identifier, String> standIns = new LinkedHashMap<>();
		for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
			Identifier id = entry.getKey().identifier();
			if (id.getNamespace().equals("minecraft")) continue;
			try {
				built.put(entry.getValue(), convert(id, entry.getValue(), standIns));
			} catch (RuntimeException e) {
				Main.LOGGER.warn("[pandorical-geyser] Couldn't convert {} for Bedrock, so its states stay air there", id, e);
			}
		}
		if (!standIns.isEmpty()) {
			Main.LOGGER.info("[pandorical-geyser] Bedrock draws a stand-in box for some states of: {}", standIns);
		}
		return built;
	}

	private static Converted convert(Identifier id, Block block, Map<Identifier, String> standIns) {
		List<Look> looks = new ArrayList<>();
		Map<BlockState, Integer> lookOf = new LinkedHashMap<>();
		for (BlockState state : block.getStateDefinition().getPossibleStates()) {
			BlockModels.Baked baked;
			try {
				baked = BlockModels.bake(state);
			} catch (RuntimeException e) {
				// A blockstate or model that doesn't read costs this state its look, not the block.
				baked = new BlockModels.Baked(List.of(), Optional.empty(), "unreadable model: " + e.getMessage());
			}
			if (baked.elements().isEmpty()) standIns.putIfAbsent(id, baked.problem());
			Look look = look(state, baked);
			int index = looks.indexOf(look);
			if (index < 0) {
				if (looks.size() == MAX_VARIANTS) {
					standIns.putIfAbsent(id, "more than " + MAX_VARIANTS + " looks; the rest share the first");
					index = 0;
				} else {
					index = looks.size();
					looks.add(look);
				}
			}
			lookOf.put(state, index);
		}
		return new Converted(block, org.geysermc.geyser.api.util.Identifier.of(id.getNamespace(), id.getPath()), looks, lookOf);
	}

	private static Look look(BlockState state, BlockModels.Baked baked) {
		List<BlockModels.Element> elements = baked.elements();
		// Drawn by nothing on Java either (air-like blocks, barriers' kin), so nothing on Bedrock.
		if (state.getRenderShape() == RenderShape.INVISIBLE) elements = List.of();
		else if (elements.isEmpty()) elements = List.of(standIn(state, baked));

		String renderMethod = renderMethod(state);
		Map<String, Material> materials = new LinkedHashMap<>();
		JsonArray cubes = new JsonArray();
		for (BlockModels.Element element : elements) {
			JsonObject uv = new JsonObject();
			element.faces().forEach((direction, face) -> {
				BlockTextures.Texture texture = Optional.ofNullable(face.texture()).flatMap(BlockTextures::of).orElseGet(BlockTextures::missing);
				String tint = face.tinted() ? tintMethod(face.texture()) : null;
				Material material = new Material(texture.key(), renderMethod, tint);
				// Named after the model's variable, so blocks differing only in texture share their
				// geometry; numbered where multipart pieces use one variable for different textures.
				String base = face.variable().replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
				String instance = base;
				for (int n = 2; materials.containsKey(instance) && !materials.get(instance).equals(material); n++) {
					instance = base + "_" + n;
				}
				materials.putIfAbsent(instance, material);
				uv.add(direction.getSerializedName(), bedrockFace(face.uv(), face.rotation(), direction, instance));
			});
			if (!uv.isEmpty()) cubes.add(bedrockCube(element, uv));
		}

		float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		return new Look(geometry(cubes), materials, collisionBoxes(state), selectionBox(state),
			state.getLightEmission(), state.getLightDampening(), hardness < 0 ? 1_000_000f : hardness);
	}

	/** A box the size of the block's outline, every face in the one texture a guess can find. */
	private static BlockModels.Element standIn(BlockState state, BlockModels.Baked baked) {
		AABB bounds = bounds(shape(state, false)).orElse(new AABB(0, 0, 0, 1, 1, 1));
		float[] from = {(float) bounds.minX * 16, (float) bounds.minY * 16, (float) bounds.minZ * 16};
		float[] to = {(float) bounds.maxX * 16, (float) bounds.maxY * 16, (float) bounds.maxZ * 16};
		Identifier texture = baked.fallbackTexture().filter(id -> BlockTextures.of(id).isPresent()).orElse(null);
		Map<Direction, BlockModels.Face> faces = new java.util.EnumMap<>(Direction.class);
		for (Direction direction : Direction.values()) {
			faces.put(direction, new BlockModels.Face(new float[] {0, 0, 16, 16}, texture, "stand_in", false, 0));
		}
		return new BlockModels.Element(from, to, new float[] {8, 8, 8}, new float[3], false, faces);
	}

	/**
	 * How Bedrock should draw it. Java's render layer is client code a server never sees, so
	 * translucency is read off the kinds of block that have it, which modded glass and ice extend.
	 */
	private static String renderMethod(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof BeaconBeamBlock || block instanceof IceBlock || block instanceof SlimeBlock || block instanceof HoneyBlock) {
			return "blend";
		}
		return state.canOcclude() ? "opaque" : "alpha_test";
	}

	/** Vanilla tints by biome; a server can't see a mod's tint, so foliage and grass are guessed by name. */
	private static String tintMethod(Identifier texture) {
		String name = texture == null ? "" : texture.getPath();
		if (name.contains("birch_leaves")) return "birch_foliage";
		if (name.contains("spruce_leaves")) return "evergreen_foliage";
		if (name.contains("leaves") || name.contains("vine")) return "default_foliage";
		if (name.contains("water")) return "water";
		return "grass";
	}

	/** Java's 0 to 16 block space to Bedrock geometry, which mirrors x about the block's centre. */
	private static JsonObject bedrockCube(BlockModels.Element element, JsonObject uv) {
		float[] from = element.from().clone();
		float[] to = element.to().clone();
		float[] origin = element.origin();
		float[] rotation = element.rotation();

		int axes = 0;
		int axis = -1;
		for (int i = 0; i < 3; i++) if (rotation[i] != 0) { axes++; axis = i; }
		// Java stretches a turned plane back across the block; Bedrock has no such flag.
		if (element.rescale() && axes == 1) {
			float scale = (float) (1 / Math.cos(Math.toRadians(rotation[axis])));
			for (int i = 0; i < 3; i++) {
				if (i == axis) continue;
				from[i] = origin[i] + (from[i] - origin[i]) * scale;
				to[i] = origin[i] + (to[i] - origin[i]) * scale;
			}
		}

		JsonObject cube = new JsonObject();
		cube.add("origin", array(8 - to[0], from[1], from[2] - 8));
		cube.add("size", array(to[0] - from[0], to[1] - from[1], to[2] - from[2]));
		if (axes > 0) {
			cube.add("pivot", array(8 - origin[0], origin[1], origin[2] - 8));
			cube.add("rotation", array(-rotation[0], -rotation[1], rotation[2]));
		}
		cube.add("uv", uv);
		return cube;
	}

	/** Bedrock reads up and down faces from the opposite corner, and turns a face with uv_rotation. */
	private static JsonObject bedrockFace(float[] javaUv, int rotation, Direction direction, String instance) {
		JsonObject face = new JsonObject();
		boolean flipped = direction == Direction.UP || direction == Direction.DOWN;
		float u = flipped ? javaUv[2] : javaUv[0];
		float v = flipped ? javaUv[3] : javaUv[1];
		face.add("uv", array(u, v));
		face.add("uv_size", array((flipped ? javaUv[0] : javaUv[2]) - u, (flipped ? javaUv[1] : javaUv[3]) - v));
		// Clockwise, as Java's; which way round Bedrock turns a bottom face is still to be seen.
		if (rotation != 0) face.addProperty("uv_rotation", rotation);
		face.addProperty("material_instance", instance);
		return face;
	}

	private static synchronized String geometry(JsonArray cubes) {
		String canonical = cubes.toString();
		String id = "geometry.pandorical_" + HexFormat.of().formatHex(sha256(canonical), 0, 6);
		geometries.computeIfAbsent(id, unused -> {
			JsonObject description = new JsonObject();
			description.addProperty("identifier", id);
			description.addProperty("texture_width", 16);
			description.addProperty("texture_height", 16);
			JsonObject bone = new JsonObject();
			bone.addProperty("name", "block");
			bone.add("pivot", array(0, 0, 0));
			bone.add("cubes", cubes);
			JsonArray bones = new JsonArray();
			bones.add(bone);
			JsonObject geometry = new JsonObject();
			geometry.add("description", description);
			geometry.add("bones", bones);
			JsonArray list = new JsonArray();
			list.add(geometry);
			JsonObject file = new JsonObject();
			// 1.21.0 for uv_rotation.
			file.addProperty("format_version", "1.21.0");
			file.add("minecraft:geometry", list);
			return file;
		});
		return id;
	}

	private static List<BoxComponent> collisionBoxes(BlockState state) {
		List<AABB> boxes = shape(state, true).toAabbs();
		if (boxes.isEmpty()) return List.of(BoxComponent.emptyBox());
		if (boxes.size() > 16) boxes = List.of(bounds(shape(state, true)).orElseThrow());
		return boxes.stream().map(box -> bedrockBox(box, 1.5)).toList();
	}

	private static BoxComponent selectionBox(BlockState state) {
		return bounds(shape(state, false)).map(box -> bedrockBox(box, 1)).orElse(BoxComponent.emptyBox());
	}

	/** As Geyser converts its own: Bedrock mirrors x, and caps boxes at the block's edges. */
	private static BoxComponent bedrockBox(AABB box, double maxY) {
		double minX = Math.clamp(box.minX, 0, 1), maxX = Math.clamp(box.maxX, 0, 1);
		double minY = Math.clamp(box.minY, 0, 1), top = Math.clamp(box.maxY, 0, maxY);
		double minZ = Math.clamp(box.minZ, 0, 1), maxZ = Math.clamp(box.maxZ, 0, 1);
		return new BoxComponent((float) (16 * (1 - maxX) - 8), (float) (16 * minY), (float) (16 * minZ - 8),
			(float) (16 * (maxX - minX)), (float) (16 * (top - minY)), (float) (16 * (maxZ - minZ)));
	}

	private static VoxelShape shape(BlockState state, boolean collision) {
		try {
			return collision
				? state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
				: state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		} catch (RuntimeException e) {
			// Some shapes read the world around them; outside one, a full block is the safe guess.
			return net.minecraft.world.phys.shapes.Shapes.block();
		}
	}

	private static Optional<AABB> bounds(VoxelShape shape) {
		return shape.isEmpty() ? Optional.empty() : Optional.of(shape.bounds());
	}

	private static NonVanillaCustomBlockData data(Converted block) {
		var builder = NonVanillaCustomBlockData.builder()
			.namespace(block.bedrockId().namespace())
			.name(block.bedrockId().path())
			.includedInCreativeInventory(false)
			.components(components(block.looks().getFirst()));
		int looks = block.looks().size();
		if (looks > 1) {
			builder.intProperty(VARIANT, range(Math.min(looks, 16)));
			if (looks > 16) builder.intProperty(VARIANT_HIGH, range((looks + 15) / 16));
			List<CustomBlockPermutation> permutations = new ArrayList<>();
			for (int i = 0; i < looks; i++) {
				permutations.add(new CustomBlockPermutation(components(block.looks().get(i)), condition(i, looks)));
			}
			builder.permutations(permutations);
		}
		return builder.build();
	}

	private static CustomBlockComponents components(Look look) {
		var builder = CustomBlockComponents.builder()
			.geometry(GeometryComponent.builder().identifier(look.geometry()).build())
			.collisionBoxes(look.collision())
			.selectionBox(look.selection())
			.lightEmission(look.lightEmission())
			.lightDampening(look.lightDampening())
			.destructibleByMining(look.hardness());
		boolean first = true;
		for (var material : look.materials().entrySet()) {
			MaterialInstance instance = MaterialInstance.builder()
				.texture(material.getValue().texture())
				.renderMethod(material.getValue().renderMethod())
				.tintMethod(material.getValue().tintMethod())
				.faceDimming(true)
				.ambientOcclusion(true)
				.build();
			if (first) builder.materialInstance("*", instance);
			builder.materialInstance(material.getKey(), instance);
			first = false;
		}
		return builder.build();
	}

	private static String condition(int look, int looks) {
		String condition = "q.block_state('" + VARIANT + "') == " + (look % 16);
		return looks > 16 ? condition + " && q.block_state('" + VARIANT_HIGH + "') == " + (look / 16) : condition;
	}

	private static CustomBlockState customState(NonVanillaCustomBlockData data, int looks, int look) {
		if (looks == 1) return data.defaultBlockState();
		var builder = data.blockStateBuilder().intProperty(VARIANT, look % 16);
		if (looks > 16) builder.intProperty(VARIANT_HIGH, look / 16);
		return builder.build();
	}

	private static JavaBlockState javaState(BlockState state) {
		JavaBoundingBox[] collision = shape(state, true).toAabbs().stream()
			.map(box -> new JavaBoundingBox(box.getCenter().x, box.getCenter().y, box.getCenter().z, box.getXsize(), box.getYsize(), box.getZsize()))
			.toArray(JavaBoundingBox[]::new);
		float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		return JavaBlockState.builder()
			.identifier(BlockModels.describe(state))
			.javaId(Block.getId(state))
			.stateGroupId(BuiltInRegistries.BLOCK.getId(state.getBlock()))
			.blockHardness(hardness < 0 ? 1_000_000f : hardness)
			.waterlogged(state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED))
			.collision(collision)
			.canBreakWithHand(!state.requiresCorrectToolForDrops())
			.pistonBehavior(state.getPistonPushReaction().name().toLowerCase(Locale.ROOT))
			.build();
	}

	private static List<Integer> range(int count) {
		List<Integer> values = new ArrayList<>(count);
		for (int i = 0; i < count; i++) values.add(i);
		return values;
	}

	private static JsonArray array(float... values) {
		JsonArray array = new JsonArray();
		for (float value : values) array.add(value);
		return array;
	}

	private static byte[] sha256(String text) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
