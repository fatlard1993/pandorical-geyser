package justfatlard.pandorical_geyser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.geysermc.geyser.level.block.Blocks;
import org.geysermc.geyser.level.block.type.Block;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.java.BuiltInJavaRegistries;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * HACK: works around a Geyser bug by writing to its internal state list, which it doesn't promise
 * to keep still. Remove once Geyser registers every state of a non-vanilla block itself.
 *
 * <p>Geyser makes a Java state known for only the first state of each non-vanilla block it meets.
 * Chunks come out right, since they map states straight to Bedrock, but a block update looks the
 * state up first, finds air, and sends air: a slab placed, a crop grown or a door opened vanishes
 * for a Bedrock player until the chunk is sent again.</p>
 *
 * <p>This fills in the rest once Geyser has finished starting, when everything it derives from
 * that list has been derived; the update path is the one place still reading it.</p>
 */
final class GeyserStates {
	private GeyserStates() {}

	/** Fills in what Geyser left out for these blocks; logged, not thrown, if its insides have moved. */
	static void fillIn(Collection<List<BlockState>> blocks) {
		try {
			List<org.geysermc.geyser.level.block.type.BlockState> known = BlockRegistries.BLOCK_STATES.get();
			org.geysermc.geyser.level.block.type.BlockState air = Blocks.AIR.defaultBlockState();
			int filled = 0;
			for (List<BlockState> states : blocks) {
				int groupId = BuiltInRegistries.BLOCK.getId(states.getFirst().getBlock());
				Optional<Block> block = BuiltInJavaRegistries.BLOCK.get(groupId);
				if (block.isEmpty()) continue;
				for (BlockState state : states) {
					int id = net.minecraft.world.level.block.Block.getId(state);
					while (known.size() <= id) known.add(air);
					if (known.get(id).block() == block.get()) continue;
					known.set(id, new org.geysermc.geyser.level.block.type.BlockState(block.get(), id));
					filled++;
				}
			}
			Main.LOGGER.info("[pandorical-geyser] Filled in {} block states Geyser left out", filled);
		} catch (LinkageError | RuntimeException e) {
			Main.LOGGER.error("[pandorical-geyser] Geyser has changed inside, so modded blocks a Bedrock player places or changes"
				+ " show as air until they move away and back. Update pandorical-geyser.", e);
		}
	}
}
