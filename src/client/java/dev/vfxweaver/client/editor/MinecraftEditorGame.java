package dev.vfxweaver.client.editor;

import dev.vfxweaver.api.VFXAPI;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.resource.VFXDefinitionManager;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The real {@link EditorGame}: the only editor class that touches Minecraft. Effects registered
 * and played by the browser editor go through the ordinary local API
 * ({@link VFXDefinitionManager#registerLocal} + {@link VFXAPI}), on the render thread where the
 * effect manager is consumed.
 *
 * <p>All ids are raw effect ids of the form {@code <namespace>:<name>}; a malformed id is rejected
 * without touching the game. A full compile is marshalled onto the client thread because it both
 * writes the definition layer and starts a play, and those must be ordered; it returns a
 * monotonic non-zero handle. Live edits are safe to call from the HTTP thread - the local
 * dispatcher queues them onto the render thread itself.</p>
 */
public final class MinecraftEditorGame implements EditorGame {
	/** Transport value that starts playback; anything else stops the effect. */
	private static final String TRANSPORT_PLAY = "play";

	private final AtomicLong handles = new AtomicLong();

	@Override
	public long applyAndPlay(final String effectId, final String json, final String transport) {
		final @Nullable Identifier id = Identifier.tryParse(effectId);
		if (id == null) {
			return 0L;
		}
		final long handle = this.handles.incrementAndGet();
		Minecraft.getInstance().execute(() -> {
			VFXDefinitionManager.get().registerLocal(Map.of(id, json));
			if (TRANSPORT_PLAY.equals(transport)) {
				VFXAPI.playEffectId(id, 0, Map.of(), null);
			} else {
				VFXAPI.stopEffect(id);
			}
		});
		return handle;
	}

	@Override
	public boolean stop(final String effectId) {
		final @Nullable Identifier id = Identifier.tryParse(effectId);
		return id != null && VFXAPI.stopEffect(id);
	}

	@Override
	public boolean setParam(final String effectId, final String name, final float value) {
		final @Nullable Identifier id = Identifier.tryParse(effectId);
		return id != null && VFXAPI.setParam(id, name, value);
	}

	@Override
	public boolean setKeyframe(final String effectId, final String name, final int time, final float value) {
		final @Nullable Identifier id = Identifier.tryParse(effectId);
		return id != null && VFXAPI.setKeyframe(id, name, time, value, (EasingType) null);
	}

	@Override
	public boolean seek(final String effectId, final int elapsedTicks) {
		final @Nullable Identifier id = Identifier.tryParse(effectId);
		return id != null && VFXAPI.seekEffect(id, elapsedTicks);
	}
}
