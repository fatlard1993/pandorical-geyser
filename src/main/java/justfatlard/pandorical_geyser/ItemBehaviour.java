package justfatlard.pandorical_geyser;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Repairable;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.food.FoodProperties;
import org.geysermc.geyser.api.item.custom.v2.NonVanillaCustomItemDefinition;
import org.geysermc.geyser.api.item.custom.v2.component.geyser.GeyserItemDataComponents;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaConsumable;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaEquippable;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaFoodProperties;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaItemDataComponents;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaRepairable;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaTool;
import org.geysermc.geyser.api.util.Holders;
import org.geysermc.geyser.api.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * What a modded item does, told to the Bedrock client. The server decides every outcome either way;
 * these are what Bedrock predicts and shows: how fast a tool digs, that food can be eaten, where
 * armour goes, what mends it, and the damage on a weapon's tooltip.
 */
final class ItemBehaviour {
	/** A player's own attack, which a weapon's tooltip adds its modifiers to. */
	private static final double PLAYER_ATTACK = 1;

	private ItemBehaviour() {}

	static void describe(NonVanillaCustomItemDefinition.Builder definition, Item item) {
		var components = item.components();

		Tool tool = components.get(DataComponents.TOOL);
		if (tool != null) definition.component(JavaItemDataComponents.TOOL, tool(tool));

		FoodProperties food = components.get(DataComponents.FOOD);
		if (food != null) {
			definition.component(JavaItemDataComponents.FOOD,
				JavaFoodProperties.of(food.nutrition(), Math.max(0, food.saturation()), food.canAlwaysEat()));
		}

		Consumable consumable = components.get(DataComponents.CONSUMABLE);
		if (consumable != null) {
			JavaConsumable.Animation animation = animation(consumable);
			// Bedrock wants a positive time; an instant one is the shortest it takes.
			float seconds = Math.max(0.05f, consumable.consumeSeconds());
			definition.component(JavaItemDataComponents.CONSUMABLE, JavaConsumable.of(seconds, animation));
		}

		Equippable equippable = components.get(DataComponents.EQUIPPABLE);
		if (equippable != null && slot(equippable.slot()) instanceof JavaEquippable.EquipmentSlot slot) {
			definition.component(JavaItemDataComponents.EQUIPPABLE, JavaEquippable.of(slot));
		}

		Repairable repairable = components.get(DataComponents.REPAIRABLE);
		if (repairable != null && holders(repairable.items(), BuiltInRegistries.ITEM) instanceof Holders items) {
			definition.component(JavaItemDataComponents.REPAIRABLE, JavaRepairable.of(items));
		}

		ItemAttributeModifiers modifiers = components.get(DataComponents.ATTRIBUTE_MODIFIERS);
		if (modifiers != null) {
			double damage = modifiers.compute(Attributes.ATTACK_DAMAGE, PLAYER_ATTACK, EquipmentSlot.MAINHAND);
			if (damage > PLAYER_ATTACK) definition.component(GeyserItemDataComponents.ATTACK_DAMAGE, (int) Math.round(damage));
		}
	}

	/** Each rule that sets a speed; one that only marks blocks as correct for drops says nothing to Bedrock. */
	private static JavaTool tool(Tool tool) {
		var builder = JavaTool.builder()
			.defaultMiningSpeed(Math.max(0.01f, tool.defaultMiningSpeed()))
			.canDestroyBlocksInCreative(tool.canDestroyBlocksInCreative());
		for (Tool.Rule rule : tool.rules()) {
			if (rule.speed().isEmpty() || rule.speed().get() <= 0) continue;
			Holders blocks = holders(rule.blocks(), BuiltInRegistries.BLOCK);
			if (blocks != null) builder.rule(JavaTool.Rule.of(blocks, rule.speed().get()));
		}
		return builder.build();
	}

	/** A tag by its name, which Geyser resolves itself; a list by its members; null if empty. */
	private static <T> Holders holders(HolderSet<T> set, Registry<T> registry) {
		var tag = set.unwrapKey();
		if (tag.isPresent()) {
			var id = tag.get().location();
			return Holders.ofTag(Identifier.of(id.getNamespace(), id.getPath()));
		}
		List<Identifier> ids = new ArrayList<>();
		for (Holder<T> holder : set) {
			var key = registry.getKey(holder.value());
			if (key != null) ids.add(Identifier.of(key.getNamespace(), key.getPath()));
		}
		return ids.isEmpty() ? null : Holders.of(ids);
	}

	private static JavaConsumable.Animation animation(Consumable consumable) {
		return switch (consumable.animation()) {
			case EAT -> JavaConsumable.Animation.EAT;
			case DRINK -> JavaConsumable.Animation.DRINK;
			case BLOCK -> JavaConsumable.Animation.BLOCK;
			case BOW -> JavaConsumable.Animation.BOW;
			case SPEAR, TRIDENT -> JavaConsumable.Animation.SPEAR;
			case CROSSBOW -> JavaConsumable.Animation.CROSSBOW;
			case SPYGLASS -> JavaConsumable.Animation.SPYGLASS;
			default -> JavaConsumable.Animation.NONE;
		};
	}

	/** Bedrock wears armour on four slots and a body; hands and a saddle aren't worn. */
	private static JavaEquippable.EquipmentSlot slot(EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> JavaEquippable.EquipmentSlot.HEAD;
			case CHEST -> JavaEquippable.EquipmentSlot.CHEST;
			case LEGS -> JavaEquippable.EquipmentSlot.LEGS;
			case FEET -> JavaEquippable.EquipmentSlot.FEET;
			case BODY -> JavaEquippable.EquipmentSlot.BODY;
			default -> null;
		};
	}
}
