package net.antwire.civitas.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Locale;
import net.antwire.civitas.Civitas;
import net.antwire.civitas.city.Job;
import net.antwire.civitas.entity.CitizenEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Citizens: a base skin (face, hair, skin tone) under their work clothes, which come in four states - fine, plain,
 * worn and patched, and in rags - depending on how they are doing. Name, job and current thought float above them.
 */
public class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, CitizenRenderState, CitizenModel> {
	private static final Identifier[][] BASE = new Identifier[2][12];
	private static final Identifier[][] OUTFIT = new Identifier[Job.values().length][4];

	static {
		for (int s = 0; s < 12; s++) {
			BASE[0][s] = Civitas.id("textures/entity/citizen/base/m" + s + ".png");
			BASE[1][s] = Civitas.id("textures/entity/citizen/base/f" + s + ".png");
		}
		for (Job j : Job.values()) {
			for (int t = 0; t < 4; t++) {
				OUTFIT[j.ordinal()][t] = Civitas.id("textures/entity/citizen/outfit/" + j.name().toLowerCase(Locale.ROOT) + "_" + t + ".png");
			}
		}
	}

	public CitizenRenderer(EntityRendererProvider.Context context) {
		super(context, new CitizenModel(context.bakeLayer(CitizenModel.LAYER)), 0.5F);
		this.addLayer(new OutfitLayer(this));
	}

	@Override
	public CitizenRenderState createRenderState() {
		return new CitizenRenderState();
	}

	@Override
	public void extractRenderState(CitizenEntity entity, CitizenRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.job = entity.job().ordinal();
		state.tier = Math.max(0, Math.min(3, entity.tier()));
		state.skin = Math.floorMod(entity.skin(), 12);
		state.female = entity.female();
		state.thought = entity.thought();
		state.action = entity.action();
		state.jobTitle = entity.job().title;
		if (state.action == CitizenEntity.ACTION_SIT) {
			state.isPassenger = true;
		}
	}

	@Override
	public Identifier getTextureLocation(CitizenRenderState state) {
		return BASE[state.female ? 1 : 0][state.skin];
	}

	@Override
	protected void submitNameDisplay(CitizenRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.nameTag == null) {
			return;
		}
		pose.pushPose();
		collector.submitNameTag(pose, state.nameTagAttachment, 0, Component.literal(state.jobTitle).withStyle(ChatFormatting.GRAY), !state.isDiscrete,
			state.lightCoords, camera);
		pose.translate(0.0F, 9.0F * 1.15F * 0.025F, 0.0F);
		collector.submitNameTag(pose, state.nameTagAttachment, 0, state.nameTag, !state.isDiscrete, state.lightCoords, camera);
		if (!state.thought.isEmpty()) {
			pose.translate(0.0F, 9.0F * 1.4F * 0.025F, 0.0F);
			collector.submitNameTag(pose, state.nameTagAttachment, 0,
				Component.literal("“" + state.thought + "”").withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC), !state.isDiscrete, state.lightCoords, camera);
		}
		pose.popPose();
	}

	/** The work clothes, drawn over the body. */
	static class OutfitLayer extends RenderLayer<CitizenRenderState, CitizenModel> {
		OutfitLayer(RenderLayerParent<CitizenRenderState, CitizenModel> parent) {
			super(parent);
		}

		@Override
		public void submit(PoseStack pose, SubmitNodeCollector collector, int light, CitizenRenderState state, float yRot, float xRot) {
			renderColoredCutoutModel(this.getParentModel(), OUTFIT[state.job][state.tier], pose, collector, light, state, -1, 1);
		}
	}
}
