package net.antwire.civitas.client;

import net.antwire.civitas.network.Payloads;
import net.antwire.civitas.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;

public class CivitasClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModelLayerRegistry.registerModelLayer(CitizenModel.LAYER, CitizenModel::createLayer);
		EntityRendererRegistry.register(ModEntities.CITIZEN, CitizenRenderer::new);
		ClientPlayNetworking.registerGlobalReceiver(Payloads.State.TYPE, (payload, context) -> context.client().execute(() -> ClientState.accept(payload.kind(), payload.json())));
	}
}
