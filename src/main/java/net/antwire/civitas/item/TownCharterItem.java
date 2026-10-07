package net.antwire.civitas.item;

import net.antwire.civitas.city.City;
import net.antwire.civitas.city.CityManager;
import net.antwire.civitas.network.ServerNet;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.function.Consumer;

/**
 * Use it on open ground to found a town there (you name it, the town hall is laid out facing you). Once you govern a
 * town, using it anywhere opens the town's ledger.
 */
public class TownCharterItem extends Item {
	public TownCharterItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (context.getPlayer() instanceof ServerPlayer player) {
			CityManager m = CityManager.get();
			City own = m == null ? null : m.governedBy(player.getUUID());
			if (own != null && !player.isShiftKeyDown()) {
				ServerNet.openGovern(player, own);
			} else {
				ServerNet.openFound(player, context.getClickedPos().above());
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer sp) {
			CityManager m = CityManager.get();
			City own = m == null ? null : m.governedBy(sp.getUUID());
			if (own != null) {
				ServerNet.openGovern(sp, own);
			} else {
				sp.sendOverlayMessage(Component.translatable("message.civitas.charter_hint").withStyle(ChatFormatting.GOLD));
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.translatable("tooltip.civitas.charter").withStyle(ChatFormatting.GRAY));
	}
}
