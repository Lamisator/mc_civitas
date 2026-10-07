package net.antwire.civitas.registry;

import net.antwire.civitas.Civitas;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public final class ModTab {
	public static final CreativeModeTab TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Civitas.id("civitas"),
		FabricCreativeModeTab.builder()
			.title(Component.translatable("itemGroup.civitas"))
			.icon(() -> new ItemStack(ModItems.TOWN_CHARTER))
			.displayItems((params, output) -> {
				output.accept(ModItems.TOWN_CHARTER);
				output.accept(ModBlocks.TOWN_LEDGER);
				output.accept(ModItems.ALE);
			})
			.build());

	private ModTab() {
	}

	public static void init() {
	}
}
