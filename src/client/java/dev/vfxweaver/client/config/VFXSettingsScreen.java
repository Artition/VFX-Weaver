package dev.vfxweaver.client.config;

import dev.vfxweaver.util.VFXSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The vanilla in-game settings screen opened by {@code /vfx config}: a cycling button for the chain
 * resolution, a toggle each for fusion and the experimental remap, a short warning label and a Done
 * button. Every change is written straight through {@link VFXSettings}; there are no new
 * dependencies (no Cloth Config, Mod Menu or YACL).
 */
public final class VFXSettingsScreen extends Screen {
	private static final int WIDGET_WIDTH = 220;
	private static final int WIDGET_HEIGHT = 20;
	private static final int GAP = 6;

	private Button chainResolutionButton;
	private Button fusionButton;
	private Button remapButton;

	private VFXSettingsScreen() {
		super(Component.translatable("vfxweaver.config.title"));
	}

	/**
	 * Opens this screen on the client thread. Registered with {@code VFXSettingsScreens} as the
	 * {@code /vfx config} hook, so the command only enqueues on the render thread.
	 */
	public static void open() {
		final Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> minecraft.setScreenAndShow(new VFXSettingsScreen()));
	}

	@Override
	protected void init() {
		final int x = (this.width - WIDGET_WIDTH) / 2;
		int y = this.height / 4;

		this.chainResolutionButton = this.addRenderableWidget(
			Button.builder(chainResolutionLabel(), button -> {
				final VFXSettings settings = VFXSettings.get();
				settings.setChainResolution(settings.chainResolution() < 1.0F ? 1.0F : 0.5F);
				this.chainResolutionButton.setMessage(chainResolutionLabel());
			}).bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build()
		);
		y += WIDGET_HEIGHT + GAP;

		this.fusionButton = this.addRenderableWidget(
			Button.builder(fusionLabel(), button -> {
				final VFXSettings settings = VFXSettings.get();
				settings.setFusion(!settings.fusion());
				this.fusionButton.setMessage(fusionLabel());
			}).bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build()
		);
		y += WIDGET_HEIGHT + GAP;

		this.remapButton = this.addRenderableWidget(
			Button.builder(remapLabel(), button -> {
				final VFXSettings settings = VFXSettings.get();
				settings.setRemap(!settings.remap());
				this.remapButton.setMessage(remapLabel());
			}).bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build()
		);
		y += WIDGET_HEIGHT + GAP;

		final Button warning = this.addRenderableWidget(
			Button.builder(Component.translatable("vfxweaver.config.remap_warning"), button -> {
			}).bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build()
		);
		warning.active = false;

		this.addRenderableWidget(
			Button.builder(Component.translatable("vfxweaver.config.done"), button -> this.onClose())
				.bounds(x, this.height - 40, WIDGET_WIDTH, WIDGET_HEIGHT).build()
		);
	}

	@Override
	public void onClose() {
		VFXSettings.get().save();
		super.onClose();
	}

	private Component chainResolutionLabel() {
		return Component.translatable(
			"vfxweaver.config.chainres",
			Component.translatable(VFXSettings.get().chainResolution() < 1.0F ? "vfxweaver.config.half" : "vfxweaver.config.full")
		);
	}

	private Component fusionLabel() {
		return Component.translatable("vfxweaver.config.fusion", onOff(VFXSettings.get().fusion()));
	}

	private Component remapLabel() {
		return Component.translatable("vfxweaver.config.remap", onOff(VFXSettings.get().remap()));
	}

	private static Component onOff(final boolean value) {
		return Component.translatable(value ? "vfxweaver.config.on" : "vfxweaver.config.off");
	}
}
