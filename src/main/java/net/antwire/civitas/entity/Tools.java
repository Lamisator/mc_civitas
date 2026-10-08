package net.antwire.civitas.entity;

import net.antwire.civitas.city.Job;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The tool of the trade a citizen carries when not busy with something else. */
final class Tools {
	private Tools() {
	}

	static Item of(Job job) {
		return switch (job) {
			case SHERIFF -> Items.IRON_SWORD;
			case MINER -> Items.IRON_PICKAXE;
			case FARMER -> Items.IRON_HOE;
			case LUMBERJACK -> Items.IRON_AXE;
			case BUILDER -> Items.IRON_SHOVEL;
			case BANKER, CLERK -> Items.WRITABLE_BOOK;
			default -> Items.AIR;
		};
	}

	static boolean isTool(Item item) {
		if (net.antwire.civitas.compat.Arms.isWeapon(item)) {
			return true;
		}
		for (Job j : Job.values()) {
			if (of(j) == item && item != Items.AIR) {
				return true;
			}
		}
		return false;
	}

	static void equip(CitizenEntity npc, Job job) {
		ItemStack hand = npc.getItemBySlot(EquipmentSlot.MAINHAND);
		Item want = of(job);
		if (hand.isEmpty() || (isTool(hand.getItem()) && hand.getItem() != want)) {
			npc.setItemSlot(EquipmentSlot.MAINHAND, want == Items.AIR ? ItemStack.EMPTY : new ItemStack(want));
		}
	}
}
