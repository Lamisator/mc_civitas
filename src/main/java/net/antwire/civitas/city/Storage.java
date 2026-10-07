package net.antwire.civitas.city;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.antwire.commerce.block.entity.ShopBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** The chests of a building: what its workers fetch from and put away into. */
public final class Storage {
	private Storage() {
	}

	public static List<Container> containers(ServerLevel level, Building b) {
		List<Container> out = new ArrayList<>();
		for (BlockPos p : b.marks("storage")) {
			if (level.isLoaded(p) && level.getBlockEntity(p) instanceof Container c) {
				out.add(c);
			}
		}
		return out;
	}

	public static @Nullable ShopBlockEntity shop(ServerLevel level, Building b) {
		BlockPos p = b.mark("shop");
		return p != null && level.isLoaded(p) && level.getBlockEntity(p) instanceof ShopBlockEntity s ? s : null;
	}

	public static int count(ServerLevel level, Building b, Item item) {
		int n = 0;
		for (Container c : containers(level, b)) {
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack s = c.getItem(i);
				if (s.is(item)) {
					n += s.getCount();
				}
			}
		}
		return n;
	}

	/** Takes up to n of an item; returns how many it got. */
	public static int take(ServerLevel level, Building b, Item item, int n) {
		int got = 0;
		for (Container c : containers(level, b)) {
			for (int i = 0; i < c.getContainerSize() && got < n; i++) {
				ItemStack s = c.getItem(i);
				if (s.is(item)) {
					int k = Math.min(n - got, s.getCount());
					s.shrink(k);
					got += k;
					c.setChanged();
				}
			}
		}
		return got;
	}

	/** Puts a stack away; returns what didn't fit. */
	public static ItemStack add(ServerLevel level, Building b, ItemStack stack) {
		ItemStack left = stack.copy();
		for (Container c : containers(level, b)) {
			for (int i = 0; i < c.getContainerSize() && !left.isEmpty(); i++) {
				ItemStack s = c.getItem(i);
				if (s.isEmpty()) {
					c.setItem(i, left.copy());
					left = ItemStack.EMPTY;
				} else if (ItemStack.isSameItemSameComponents(s, left) && s.getCount() < s.getMaxStackSize()) {
					int k = Math.min(left.getCount(), s.getMaxStackSize() - s.getCount());
					s.grow(k);
					left.shrink(k);
				}
			}
			c.setChanged();
		}
		return left;
	}

	public static Map<Item, Integer> contents(ServerLevel level, Building b) {
		Map<Item, Integer> out = new LinkedHashMap<>();
		for (Container c : containers(level, b)) {
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack s = c.getItem(i);
				if (!s.isEmpty()) {
					out.merge(s.getItem(), s.getCount(), Integer::sum);
				}
			}
		}
		return out;
	}

	/** Free slots across the building's chests. */
	public static int freeSlots(ServerLevel level, Building b) {
		int n = 0;
		for (Container c : containers(level, b)) {
			for (int i = 0; i < c.getContainerSize(); i++) {
				if (c.getItem(i).isEmpty()) {
					n++;
				}
			}
		}
		return n;
	}
}
