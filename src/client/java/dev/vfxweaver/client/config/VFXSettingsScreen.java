package dev.vfxweaver.client.config;

import dev.vfxweaver.util.VFXSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
//? if <26.1 {
/*import net.minecraft.client.gui.GuiGraphics;
*///?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The vanilla in-game settings screen opened by the client-only {@code /vfxconfig} command: the
 * title is drawn above a self-describing cycling button for the chain resolution and a toggle each
 * for fusion and the shifted-read remap, followed by a Done button. Each button's own label carries
 * the setting name, its current value and a few words on what it does, so no separate description
 * widget is needed. Every change is written straight through {@link VFXSettings}; there are no new
 * dependencies (no Cloth Config, Mod Menu or YACL).
 */
public final class VFXSettingsScreen extends Screen {
	private static final int WIDGET_WIDTH = 220;
	private static final int WIDGET_HEIGHT = 20;
	private static final int GAP = 6;
	private static final int TITLE_Y = 15;

	private Button chainResolutionButton;
	private Button fusionButton;
	private Button remapButton;

	private VFXSettingsScreen() {
		super(Component.translatable("vfxweaver.config.title"));
	}

	/**
	 * Opens this screen on the client thread. Called by the client-only {@code /vfxconfig}
	 * command, so the command only enqueues on the render thread.
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

		this.addRenderableWidget(
			Button.builder(Component.translatable("vfxweaver.config.done"), button -> this.onClose())
				.bounds(x, this.height - 40, WIDGET_WIDTH, WIDGET_HEIGHT).build()
		);
	}

	//? if <26.1 {
	/*@Override
	public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);
	}
	*///?} else {
	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);
	}
	//?}

	@Override
	public void onClose() {
		VFXSettings.get().save();
		super.onClose();
	}

	private static Component chainResolutionLabel() {
		return Component.translatable(
			"vfxweaver.config.chainres",
			Component.translatable(VFXSettings.get().chainResolution() < 1.0F ? "vfxweaver.config.chainres_half" : "vfxweaver.config.chainres_full")
		);
	}

	private static Component fusionLabel() {
		return Component.translatable(
			"vfxweaver.config.fusion",
			Component.translatable(VFXSettings.get().fusion() ? "vfxweaver.config.fusion_on" : "vfxweaver.config.fusion_off")
		);
	}

	private static Component remapLabel() {
		return Component.translatable(
			"vfxweaver.config.remap",
			Component.translatable(VFXSettings.get().remap() ? "vfxweaver.config.remap_on" : "vfxweaver.config.remap_off")
		);
	}
}
