package justfatlard.pandorical_geyser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantable;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.NonVanillaCustomItemDefinition;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserBlockPlacer;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserItemDataComponents;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaItemDataComponents;
import org.geysermc.geyser.api.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tells Geyser about every modded item, under its Java id. Geyser drops any packet naming an item it
 * doesn't know, and a Bedrock player's whole inventory arrives in one packet, so a single unknown
 * item would leave that inventory empty.
 */
final class ModdedItems {
	/** A Bedrock vanilla texture, for items whose icon {@link ItemIcons} couldn't convert. */
	private static final String PLACEHOLDER_ICON = "paper";

	private ModdedItems() {}

	static void register(GeyserDefineCustomItemsEvent event) {
		Map<String, JsonObject> langs = new HashMap<>();
		List<net.minecraft.resources.Identifier> placeholders = new ArrayList<>();
		int registered = 0;
		for (var entry : BuiltInRegistries.ITEM.entrySet()) {
			var id = entry.getKey().identifier();
			if (id.getNamespace().equals("minecraft")) continue;
			// Caught per item: one that can't be described must not cost every other its place.
			try {
				var icon = ItemIcons.of(id);
				var block = entry.getValue() instanceof BlockItem blockItem ? ModdedBlocks.bedrockId(blockItem.getBlock()) : Optional.<Identifier>empty();
				event.register(definition(id, entry.getValue(), icon, block, langs));
				registered++;
				if (icon.isEmpty() && block.isEmpty()) placeholders.add(id);
			} catch (RuntimeException e) {
				Main.LOGGER.warn("[pandorical-geyser] Bedrock players won't see {}: {}", id, e.getMessage());
			}
		}
		Main.LOGGER.info("[pandorical-geyser] Mapped {} modded items for Bedrock", registered);
		if (!placeholders.isEmpty()) {
			Main.LOGGER.info("[pandorical-geyser] Bedrock shows a placeholder icon for: {}", placeholders);
		}
	}

	private static NonVanillaCustomItemDefinition definition(net.minecraft.resources.Identifier id, Item item, Optional<ItemIcons.Icon> icon,
			Optional<Identifier> block, Map<String, JsonObject> langs) {
		var options = CustomItemBedrockOptions.builder()
			.icon(icon.map(ItemIcons.Icon::key).orElse(PLACEHOLDER_ICON))
			.displayHandheld(icon.map(ItemIcons.Icon::handheld).orElseGet(() -> item.components().has(DataComponents.TOOL)));
		var definition = NonVanillaCustomItemDefinition.builder(Identifier.of(id.getNamespace(), id.getPath()), BuiltInRegistries.ITEM.getId(item))
			.displayName(englishName(item, id.getNamespace(), langs))
			.bedrockOptions(options)
			.component(JavaItemDataComponents.MAX_STACK_SIZE, Math.clamp(item.getDefaultMaxStackSize(), 1, 99));
		Integer maxDamage = item.components().get(DataComponents.MAX_DAMAGE);
		if (maxDamage != null && maxDamage > 0) {
			definition.component(JavaItemDataComponents.MAX_DAMAGE, maxDamage);
		}
		Enchantable enchantable = item.components().get(DataComponents.ENCHANTABLE);
		if (enchantable != null) {
			definition.component(JavaItemDataComponents.ENCHANTABLE, enchantable.value());
		}
		// A block item with no flat icon of its own shows its block, drawn by Bedrock.
		block.ifPresent(placed -> definition.component(GeyserItemDataComponents.BLOCK_PLACER, GeyserBlockPlacer.of(placed, icon.isEmpty())));
		return definition.build();
	}

	/** From the mod's own en_us.json, then Pandorical's; failing both, made from the item's path. */
	private static String englishName(Item item, String namespace, Map<String, JsonObject> langs) {
		String key = item.getDescriptionId();
		for (String langNamespace : new String[] {namespace, "pandorical"}) {
			JsonObject lang = langs.computeIfAbsent(langNamespace, ModdedItems::lang);
			if (lang.get(key) instanceof JsonPrimitive name && name.isString()) return name.getAsString();
		}
		String path = key.substring(key.lastIndexOf('.') + 1);
		StringBuilder name = new StringBuilder();
		for (String word : path.split("_")) {
			if (word.isEmpty()) continue;
			if (!name.isEmpty()) name.append(' ');
			name.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
		}
		return name.toString();
	}

	/** Empty when the mod has none, or one that isn't a JSON object. */
	private static JsonObject lang(String namespace) {
		try {
			return PandoricalApi.content().asset("assets/" + namespace + "/lang/en_us.json")
				.map(bytes -> JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)))
				.filter(JsonElement::isJsonObject)
				.map(JsonElement::getAsJsonObject)
				.orElseGet(JsonObject::new);
		} catch (JsonParseException e) {
			Main.LOGGER.warn("[pandorical-geyser] {}'s en_us.json doesn't parse; its items get names made from their ids", namespace);
			return new JsonObject();
		}
	}
}
