package net.antwire.civitas.client;

import net.antwire.civitas.Civitas;
import net.antwire.civitas.entity.CitizenEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.util.Mth;

/** A player-shaped body (with the outer layers for hair and clothes) and a few extra poses: protest, begging, sitting. */
public class CitizenModel extends HumanoidModel<CitizenRenderState> {
	public static final ModelLayerLocation LAYER = new ModelLayerLocation(Civitas.id("citizen"), "main");

	public CitizenModel(ModelPart root) {
		super(root);
	}

	public static LayerDefinition createLayer() {
		return LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64);
	}

	@Override
	public void setupAnim(CitizenRenderState state) {
		super.setupAnim(state);
		float t = state.ageInTicks;
		if (state.action == CitizenEntity.ACTION_PROTEST) {
			// fists in the air
			this.rightArm.xRot = -2.7F + Mth.sin(t * 0.25F) * 0.25F;
			this.rightArm.zRot = 0.2F;
			if ((int) (t / 40) % 2 == 0) {
				this.leftArm.xRot = -2.6F + Mth.cos(t * 0.25F) * 0.25F;
				this.leftArm.zRot = -0.2F;
			}
		} else if (state.action == CitizenEntity.ACTION_AIM) {
			// shouldered rifle or drawn bow
			net.minecraft.client.model.AnimationUtils.animateCrossbowHold(this.rightArm, this.leftArm, this.head, true);
		} else if (state.action == CitizenEntity.ACTION_BEG) {
			// cupped hands held out
			this.rightArm.xRot = -1.1F;
			this.leftArm.xRot = -1.1F;
			this.rightArm.yRot = -0.35F;
			this.leftArm.yRot = 0.35F;
			this.head.xRot += 0.35F;
		}
	}
}
