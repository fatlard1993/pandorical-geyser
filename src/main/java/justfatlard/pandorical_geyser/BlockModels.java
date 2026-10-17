package justfatlard.pandorical_geyser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.math.OctahedralGroup;
import com.mojang.math.Quadrant;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A block state's Java model, as Java would draw it: the blockstate file's variant or multipart
 * pieces, each model's elements with its rotation applied, and every face's texture resolved.
 * Coordinates stay in Java's 0 to 16 block space.
 */
final class BlockModels {
	/** {@code rotation} turns the texture clockwise on the face, in degrees. */
	record Face(float[] uv, Identifier texture, String variable, boolean tinted, int rotation) {}

	/** {@code rotation} holds an angle per axis, in Java's sense; all zero for none. */
	record Element(float[] from, float[] to, float[] origin, float[] rotation, boolean rescale, Map<Direction, Face> faces) {}

	/**
	 * {@code elements} is empty when nothing could be drawn; {@code problem} then says why, and
	 * {@code fallbackTexture} is the best guess at what the block looks like.
	 */
	record Baked(List<Element> elements, Optional<Identifier> fallbackTexture, String problem) {}

	private record Resolved(JsonArray elements, Map<String, String> textures) {}

	private BlockModels() {}

	static Baked bake(BlockState state) {
		Identifier block = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		Optional<JsonObject> blockstate = JavaModels.json("assets/" + block.getNamespace() + "/blockstates/" + block.getPath() + ".json");
		if (blockstate.isEmpty()) return new Baked(List.of(), Optional.empty(), "no blockstate file");

		List<JsonObject> pieces = pieces(blockstate.get(), state);
		if (pieces.isEmpty()) return new Baked(List.of(), Optional.empty(), "no variant matches " + describe(state));

		List<Element> elements = new ArrayList<>();
		Identifier fallback = null;
		String problem = null;
		for (JsonObject piece : pieces) {
			Identifier modelId = Identifier.parse(piece.get("model").getAsString());
			Resolved model = resolve(modelId);
			if (fallback == null) {
				fallback = texture(model.textures(), "#particle")
					// A model nowhere to be found is usually named after its texture.
					.or(() -> modelId.getNamespace().equals("minecraft") ? Optional.of(modelId) : Optional.empty())
					.orElse(null);
			}
			if (model.elements() == null) {
				problem = "model " + modelId + " has no geometry here";
				continue;
			}
			OctahedralGroup rotation = Quadrant.fromXYZAngles(quadrant(piece, "x"), quadrant(piece, "y"), quadrant(piece, "z"));
			boolean uvlock = piece.has("uvlock") && piece.get("uvlock").getAsBoolean();
			for (JsonElement element : model.elements()) {
				elements.add(rotate(element(element.getAsJsonObject(), model.textures()), rotation, uvlock));
			}
		}
		if (!elements.isEmpty()) problem = null;
		return new Baked(elements, Optional.ofNullable(fallback), problem);
	}

	/** The pieces Java would draw: one variant, or every multipart entry whose condition holds. */
	private static List<JsonObject> pieces(JsonObject blockstate, BlockState state) {
		List<JsonObject> pieces = new ArrayList<>();
		if (blockstate.get("variants") instanceof JsonObject variants) {
			for (var variant : variants.entrySet()) {
				if (matches(variant.getKey(), state)) {
					pieces.add(first(variant.getValue()));
					break;
				}
			}
		} else if (blockstate.get("multipart") instanceof JsonArray multipart) {
			for (JsonElement part : multipart) {
				JsonObject object = part.getAsJsonObject();
				if (!(object.get("when") instanceof JsonObject when) || holds(when, state)) {
					pieces.add(first(object.get("apply")));
				}
			}
		}
		return pieces;
	}

	private static boolean matches(String key, BlockState state) {
		if (key.isEmpty()) return true;
		for (String pair : key.split(",")) {
			String[] parts = pair.split("=", 2);
			if (parts.length != 2 || !parts[1].equals(value(state, parts[0]))) return false;
		}
		return true;
	}

	private static boolean holds(JsonObject when, BlockState state) {
		if (when.get("OR") instanceof JsonArray any) {
			for (JsonElement each : any) if (holds(each.getAsJsonObject(), state)) return true;
			return false;
		}
		if (when.get("AND") instanceof JsonArray all) {
			for (JsonElement each : all) if (!holds(each.getAsJsonObject(), state)) return false;
			return true;
		}
		for (var condition : when.entrySet()) {
			String actual = value(state, condition.getKey());
			if (actual == null || !List.of(condition.getValue().getAsString().split("\\|")).contains(actual)) return false;
		}
		return true;
	}

	private static JsonObject first(JsonElement variant) {
		return variant.isJsonArray() ? variant.getAsJsonArray().get(0).getAsJsonObject() : variant.getAsJsonObject();
	}

	private static Resolved resolve(Identifier modelId) {
		Map<String, String> textures = new HashMap<>();
		JsonArray elements = null;
		Identifier current = modelId;
		for (int depth = 0; current != null && depth < 16; depth++) {
			Optional<JsonObject> model = JavaModels.model(current);
			if (model.isEmpty()) break;
			if (model.get().get("textures") instanceof JsonObject own) {
				own.entrySet().forEach(e -> textures.putIfAbsent(e.getKey(), e.getValue().getAsString()));
			}
			if (elements == null && model.get().get("elements") instanceof JsonArray own) elements = own;
			current = model.get().has("parent") ? Identifier.parse(model.get().get("parent").getAsString()) : null;
		}
		return new Resolved(elements, textures);
	}

	private static Optional<Identifier> texture(Map<String, String> textures, String reference) {
		String value = reference;
		for (int hops = 0; value != null && value.startsWith("#") && hops < 16; hops++) {
			value = textures.get(value.substring(1));
		}
		return value == null || value.startsWith("#") ? Optional.empty() : Optional.of(Identifier.parse(value));
	}

	private static Element element(JsonObject json, Map<String, String> textures) {
		float[] from = vector(json.getAsJsonArray("from"));
		float[] to = vector(json.getAsJsonArray("to"));
		float[] origin = {8, 8, 8};
		float[] rotation = new float[3];
		boolean rescale = false;
		if (json.get("rotation") instanceof JsonObject rot) {
			origin = vector(rot.getAsJsonArray("origin"));
			if (rot.has("axis")) {
				rotation[Direction.Axis.byName(rot.get("axis").getAsString()).ordinal()] = rot.get("angle").getAsFloat();
			} else {
				rotation = new float[] {angle(rot, "x"), angle(rot, "y"), angle(rot, "z")};
			}
			rescale = rot.has("rescale") && rot.get("rescale").getAsBoolean();
		}

		Map<Direction, Face> faces = new EnumMap<>(Direction.class);
		if (json.get("faces") instanceof JsonObject faceJson) {
			for (var entry : faceJson.entrySet()) {
				Direction direction = Direction.byName(entry.getKey());
				JsonObject face = entry.getValue().getAsJsonObject();
				if (direction == null || !face.has("texture")) continue;
				String variable = face.get("texture").getAsString();
				float[] uv = face.has("uv") ? vector(face.getAsJsonArray("uv")) : defaultUv(from, to, direction);
				int turned = face.has("rotation") ? Math.floorMod(face.get("rotation").getAsInt(), 360) : 0;
				faces.put(direction, new Face(uv, texture(textures, variable).orElse(null), variable.replace("#", ""), face.has("tintindex"), turned));
			}
		}
		return new Element(from, to, origin, rotation, rescale, faces);
	}

	/** Java's FaceBakery#defaultFaceUV. */
	private static float[] defaultUv(float[] from, float[] to, Direction face) {
		return switch (face) {
			case DOWN -> new float[] {from[0], 16 - to[2], to[0], 16 - from[2]};
			case UP -> new float[] {from[0], from[2], to[0], to[2]};
			case NORTH -> new float[] {16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
			case SOUTH -> new float[] {from[0], 16 - to[1], to[0], 16 - from[1]};
			case WEST -> new float[] {from[2], 16 - to[1], to[2], 16 - from[1]};
			case EAST -> new float[] {16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
		};
	}

	/**
	 * A blockstate rotation, applied as Java applies it about the block's centre. A face's texture
	 * turns with it, as Java's does, unless uvlock holds it to the world; under uvlock the region of
	 * the texture a face shows isn't recomputed, only its angle.
	 */
	private static Element rotate(Element element, OctahedralGroup rotation, boolean uvlock) {
		if (rotation == OctahedralGroup.IDENTITY) return element;
		float[] a = turn(element.from(), rotation);
		float[] b = turn(element.to(), rotation);
		float[] from = {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])};
		float[] to = {Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};

		float[] angles = new float[3];
		for (Direction.Axis axis : Direction.Axis.values()) {
			Direction turned = rotation.rotate(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE));
			angles[turned.getAxis().ordinal()] = element.rotation()[axis.ordinal()] * turned.getAxisDirection().getStep();
		}

		Map<Direction, Face> faces = new EnumMap<>(Direction.class);
		element.faces().forEach((direction, face) -> {
			Direction now = rotation.rotate(direction);
			int extra = uvlock ? 0 : turnOnFace(direction, now, rotation);
			faces.put(now, extra == 0 ? face
				: new Face(face.uv(), face.texture(), face.variable(), face.tinted(), (face.rotation() + extra) % 360));
		});
		return new Element(from, to, turn(element.origin(), rotation), angles, element.rescale(), faces);
	}

	/**
	 * How far a face's texture has turned, clockwise as seen from outside, once {@code rotation}
	 * carries the face from {@code was} to {@code now}: where the texture's top points, carried
	 * along, against where a texture's top points on the face it lands on.
	 */
	private static int turnOnFace(Direction was, Direction now, OctahedralGroup rotation) {
		Vector3i carried = rotation.rotate(textureTop(was));
		Vector3i top = textureTop(now);
		Vector3i normal = new Vector3i(now.getStepX(), now.getStepY(), now.getStepZ());
		for (int quarter = 0; quarter < 4; quarter++) {
			if (top.equals(carried)) return quarter * 90;
			// A quarter turn clockwise seen from outside is a negative turn about the face's normal.
			top = new Vector3i(
				top.y * normal.z - top.z * normal.y,
				top.z * normal.x - top.x * normal.z,
				top.x * normal.y - top.y * normal.x);
		}
		return 0;
	}

	/** Where the top of a texture points on each face, as FaceBakery lays out its default UVs. */
	private static Vector3i textureTop(Direction face) {
		return switch (face) {
			case UP -> new Vector3i(0, 0, -1);
			case DOWN -> new Vector3i(0, 0, 1);
			default -> new Vector3i(0, 1, 0);
		};
	}

	private static float[] turn(float[] point, OctahedralGroup rotation) {
		Vector3f v = new Vector3f(point[0] - 8, point[1] - 8, point[2] - 8);
		rotation.transformation().transform(v);
		return new float[] {round(v.x + 8), round(v.y + 8), round(v.z + 8)};
	}

	private static float round(float value) {
		return Math.round(value * 1000) / 1000f;
	}

	private static Quadrant quadrant(JsonObject piece, String axis) {
		int degrees = piece.has(axis) ? Math.floorMod(piece.get(axis).getAsInt(), 360) : 0;
		return switch (degrees) {
			case 90 -> Quadrant.R90;
			case 180 -> Quadrant.R180;
			case 270 -> Quadrant.R270;
			default -> Quadrant.R0;
		};
	}

	private static float angle(JsonObject rotation, String axis) {
		return rotation.has(axis) ? rotation.get(axis).getAsFloat() : 0;
	}

	private static float[] vector(JsonArray array) {
		float[] values = new float[array.size()];
		for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsFloat();
		return values;
	}

	static String describe(BlockState state) {
		Identifier block = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		if (state.getProperties().isEmpty()) return block.toString();
		List<String> pairs = new ArrayList<>();
		for (Property<?> property : state.getProperties()) pairs.add(property.getName() + "=" + value(state, property));
		return block + "[" + String.join(",", pairs) + "]";
	}

	private static String value(BlockState state, String propertyName) {
		for (Property<?> property : state.getProperties()) {
			if (property.getName().equals(propertyName)) return value(state, property);
		}
		return null;
	}

	private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}
}
