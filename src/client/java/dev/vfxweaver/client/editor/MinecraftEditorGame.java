package dev.vfxweaver.client.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.api.VFXAPI;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.resource.VFXDefinitionManager;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The real {@link EditorGame}: the only editor class that touches Minecraft. Effects registered
 * and played by the browser editor go through the ordinary local API
 * ({@link VFXDefinitionManager#registerLocal} + {@link VFXAPI}); a play is queued onto the render
 * thread where the effect manager is consumed.
 *
 * <p>All ids are raw effect ids of the form {@code <namespace>:<name>}; a malformed id is rejected
 * without touching the game. A full compile validates and registers the definition synchronously
 * (so a rejected definition reports {@code 0} instead of a phantom play), then queues the play -
 * the registration therefore always happens before the play task runs. Live edits are safe to call
 * from the HTTP thread - the local dispatcher queues them onto the render thread itself.</p>
 */
public final class MinecraftEditorGame implements EditorGame {
	/** Transport value that starts playback; anything else stops the effect. */
	private static final String TRANSPORT_PLAY = "play";

	@Override
	public long applyAndPlay(final String effectId, final String json, final String transport) {
		final @Nullable Identifier id = Identifier.tryParse(effectId);
		// A malformed id or a definition with no resolvable type cannot be applied - report failure
		// synchronously and never queue anything, so the editor sees ok=false instead of a phantom id.
		if (id == null || json == null || !resolvesType(json)) {
			return 0L;
		}
		final Set<Identifier> failed = VFXDefinitionManager.get().registerLocal(Map.of(id, json));
		if (failed.contains(id)) {
			return 0L;
		}
		if (!TRANSPORT_PLAY.equals(transport)) {
			VFXAPI.stopEffect(id);
			return 0L;
		}
		// The instance id is allocated synchronously by the dispatcher; the play body is queued after
		// the registration above, so it always sees the new definition.
		return VFXAPI.playEffectId(id, 0, Map.of(), null);
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
		if (id == null) {
			return false;
		}
		// A seek replaces the running instances rather than stacking a new one on top: stop then
		// re-play, both queued onto the same client-thread FIFO in this order, so scrub does not leak
		// overlapping instances and there is no gap between them.
		Minecraft.getInstance().execute(() -> {
			VFXAPI.stopEffect(id);
			VFXAPI.playEffectAt(id, 0, elapsedTicks, Map.of(), null);
		});
		return true;
	}

	/** {@code true} when {@code json} is an object whose {@code type} resolves to a known effect type. */
	private static boolean resolvesType(final String json) {
		try {
			final JsonObject object = JsonParser.parseString(json).getAsJsonObject();
			return VFXEffectType.fromString(object.get("type").getAsString()) != null;
		} catch (final RuntimeException e) {
			return false;
		}
	}
}
