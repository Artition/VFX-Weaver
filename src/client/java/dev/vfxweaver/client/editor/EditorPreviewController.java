package dev.vfxweaver.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.resource.VFXDefinitionManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Owns one visual-editor preview session: the definition set, the view state (order/mute/solo) and
 * the transport (tick/playing/loop/ended). It is the single writer of every preview mutation,
 * applied on the game/render thread - the same thread the ordinary play path uses - so it never
 * races the effect manager.
 *
 * <p>The preview is always <b>exact</b> (design §16): it projects the tab onto individual
 * instances (one {@code playReplay} per child, controller-owned tick, never the engine loop, never
 * {@code beginFadeOut}), anchored by the additive {@link VFXActiveEffect#setAge(float)} override.
 * Definitions are kept in {@link VFXDefinitionManager}'s local layer and diffed by string
 * equality on every {@code sync}, so a rejected definition keeps its last-good version.
 *
 * <p>State is only touched on the game thread; HTTP callers submit a task and wait for its result,
 * so the response reflects the applied state. The class is client-only and never referenced from
 * {@code src/main}.
 */
public final class EditorPreviewController {
	private static final EditorPreviewController INSTANCE = new EditorPreviewController();

	/** Upper bound on preview children accepted from the client (matches the definition cap). */
	private static final int MAX_CHILDREN = 256;
	/** Upper bound on entity UUID targets accepted from one context. */
	private static final int MAX_TARGETS = 64;
	/** How long an HTTP caller waits for the game thread to apply a request. */
	private static final long APPLY_TIMEOUT_SECONDS = 5L;

	// --- session state: game thread only ---------------------------------------------------------
	/** Applied definition id (raw) to raw JSON; the diff/tombstone bookkeeping. */
	private final Map<String, String> applied = new LinkedHashMap<>();
	/** The tab's children, keyed for the viewstate. */
	private List<EditorPreviewPlan.Child> children = new ArrayList<>();
	/** Stable instance id per child key, reused across re-issues. */
	private final Map<String, Long> instances = new LinkedHashMap<>();
	private List<String> order = List.of();
	private Set<String> muted = Set.of();
	private Set<String> solo = Set.of();
	private float tick;
	private boolean playing;
	private boolean loop;
	private boolean ended;
	private float tabDuration;
	private long revision;
	private @Nullable Vec3 contextPos;
	private List<UUID> contextTargets = List.of();
	/** The ids rejected by the last sync, mirrored into {@code preview/state} so the web reconciles. */
	private List<String> lastRejected = List.of();
	/** Set when the last SSE client disconnects; consumed on the next client tick (game thread). */
	private final AtomicBoolean shutdownRequested = new AtomicBoolean();

	private EditorPreviewController() {
	}

	public static EditorPreviewController get() {
		return INSTANCE;
	}

	/**
	 * A bridge response: the HTTP status and the JSON body.
	 *
	 * @param status the HTTP status code
	 * @param body   the JSON body
	 */
	public record Response(int status, JsonObject body) {
	}

	// --- endpoints -------------------------------------------------------------------------------

	/**
	 * {@code POST /preview/sync}: replace the tab's definition set and re-project it. Idempotent by
	 * revision ({@code rev == lastApplied} is a no-op, {@code rev < lastApplied} is a {@code 409});
	 * registered definitions are diffed by string equality (tombstones for removed ids); rejected
	 * ids keep their last-good version and raise a {@code preview/error} event.
	 *
	 * @param body the parsed request body
	 * @return the {@code {ok, applied, rejected}} response, or a {@code 400}/{@code 409}
	 */
	public Response sync(final JsonObject body) {
		try {
			final long rev = longOr(body, "revision", -1L);
			final Map<String, String> definitions = readStringMap(body.getAsJsonObject("definitions"));
			final JsonObject meta = body.getAsJsonObject("meta");
			final List<EditorPreviewPlan.Child> newChildren = readChildren(meta);
			final float tabDur = meta != null ? floatOr(meta, "tabDuration", -1.0F) : -1.0F;
			final Boolean loopValue = meta != null && meta.has("loop") ? boolOr(meta, "loop", false) : null;
			final JsonObject context = meta != null ? meta.getAsJsonObject("context") : null;
			final @Nullable Vec3 position = readPosition(context);
			final List<UUID> targets = readTargets(context);
			return submit(() -> applySync(rev, definitions, newChildren, tabDur, loopValue, position, targets));
		} catch (final RuntimeException e) {
			return error(400, "bad_request", message(e));
		}
	}

	/**
	 * {@code POST /preview/viewstate}: reconcile visibility via instance operations only - a muted
	 * or not-due child is removed, a visible due child is spawned, and {@code order} is pushed as the
	 * per-frame {@code "order"} play-param. Accepted only at the current revision.
	 *
	 * @param body the parsed request body
	 * @return {@code {ok}} on success, or a {@code 409}
	 */
	public Response viewstate(final JsonObject body) {
		try {
			final long rev = longOr(body, "revision", -1L);
			final List<String> newOrder = readStringList(body.getAsJsonArray("order"));
			final Set<String> newMuted = new LinkedHashSet<>(readStringList(body.getAsJsonArray("muted")));
			final Set<String> newSolo = new LinkedHashSet<>(readStringList(body.getAsJsonArray("solo")));
			return submit(() -> {
				final Response stale = gate(rev);
				if (stale != null) {
					return stale;
				}
				this.order = newOrder;
				this.muted = newMuted;
				this.solo = newSolo;
				reconcile();
				emitState();
				return ok(new JsonObject());
			});
		} catch (final RuntimeException e) {
			return error(400, "bad_request", message(e));
		}
	}

	/**
	 * {@code POST /preview/param}: a revision-gated live parameter edit on one child's running
	 * instance (the fast path the web fires at ~30Hz). Accepted only at the current revision.
	 *
	 * @param body the parsed request body
	 * @return {@code {ok}} on success, or a {@code 400}/{@code 409}
	 */
	public Response param(final JsonObject body) {
		try {
			final long rev = longOr(body, "revision", -1L);
			final String key = stringOrNull(body, "key");
			final String name = stringOrNull(body, "param");
			final Float value = floatOrNull(body, "value");
			if (key == null || name == null || value == null) {
				return error(400, "bad_request", "key, param and value are required");
			}
			return submit(() -> {
				final Response stale = gate(rev);
				if (stale != null) {
					return stale;
				}
				final Long id = this.instances.get(key);
				if (id == null || find(id) == null) {
					return ok(new JsonObject());
				}
				final String defId = defForKey(key);
				final @Nullable Identifier def = defId == null ? null : Identifier.tryParse(defId);
				if (def != null) {
					VFXEffectManager.get().setParam(def, name, value);
				}
				return ok(new JsonObject());
			});
		} catch (final RuntimeException e) {
			return error(400, "bad_request", message(e));
		}
	}

	/**
	 * {@code POST /preview/transport}: time control ({@code play|pause|seek|stop|setMode}). The
	 * controller owns the tick and the loop; a non-looping play that reaches the tab duration ends.
	 * Accepted only at the current revision.
	 *
	 * @param body the parsed request body
	 * @return {@code {ok}} on success, or a {@code 400}/{@code 409}
	 */
	public Response transport(final JsonObject body) {
		try {
			final long rev = longOr(body, "revision", -1L);
			final String action = stringOrNull(body, "action");
			final Float seekTick = floatOrNull(body, "tick");
			final Boolean loopValue = body.has("loop") ? boolOr(body, "loop", false) : null;
			if (action == null) {
				return error(400, "bad_request", "action is required");
			}
			return submit(() -> {
				final Response stale = gate(rev);
				if (stale != null) {
					return stale;
				}
				if (loopValue != null) {
					this.loop = loopValue;
				}
				switch (action) {
					case "play" -> doPlay();
					case "pause" -> this.playing = false;
					case "seek" -> doSeek(seekTick != null ? seekTick : 0.0F);
					case "stop" -> doStop();
					// The old setMode action was removed; a client that still sends it stays Exact
					// (accepted as a no-op, never a 400).
					case "setMode" -> {
					}
					default -> {
						return error(400, "bad_request", "unknown action " + action);
					}
				}
				emitState();
				return ok(new JsonObject());
			});
		} catch (final RuntimeException e) {
			return error(400, "bad_request", message(e));
		}
	}

	// --- client tick / lifecycle -----------------------------------------------------------------

	/**
	 * Advances the preview clock while playing and emits {@code preview/state}. Called once per
	 * client tick from the client tick hook (the hook the editor/game work already uses); a no-op
	 * while paused, stopped, or when the game is paused.
	 */
	public void onClientTick() {
		if (this.shutdownRequested.getAndSet(false)) {
			shutdown();
		}
		if (!this.playing || Minecraft.getInstance().isPaused()) {
			return;
		}
		final float next = this.tick + 1.0F;
		if (this.tabDuration > 0.0F && next >= this.tabDuration) {
			if (this.loop) {
				this.tick = next % this.tabDuration;
			} else {
				this.tick = this.tabDuration;
				this.playing = false;
				this.ended = true;
				reconcile();
				emitState();
				return;
			}
		} else {
			this.tick = next;
		}
		reconcile();
		emitState();
	}

	/**
	 * Flags a shutdown for the next client tick. Called when the last SSE client disconnects (the
	 * editor tab closed), so the preview stops and its private definitions leave the local layer.
	 * Never runs from a network/reaper thread directly - {@link #onClientTick()} applies it on the
	 * game thread, keeping this class single-writer.
	 */
	public void onSseEmpty() {
		this.shutdownRequested.set(true);
	}

	/**
	 * Clears every preview instance, unregisters the applied definitions and resets the session, so
	 * a stopped/restarted bridge starts from a clean revision. Called on the client thread when the
	 * editor server stops.
	 */
	public void shutdown() {
		this.playing = false;
		this.tick = 0.0F;
		this.ended = false;
		stopAllPreviewInstances();
		for (final String id : new ArrayList<>(this.applied.keySet())) {
			final Identifier rid = Identifier.tryParse(id);
			if (rid != null) {
				VFXDefinitionManager.get().unregisterLocal(rid);
			}
		}
		this.applied.clear();
		this.children.clear();
		this.instances.clear();
		this.order = List.of();
		this.muted = Set.of();
		this.solo = Set.of();
		this.revision = 0L;
		this.tabDuration = 0.0F;
		this.loop = false;
		this.contextPos = null;
		this.contextTargets = List.of();
		this.lastRejected = List.of();
		this.shutdownRequested.set(false);
	}

	// --- game-thread mutation --------------------------------------------------------------------

	private Response applySync(
		final long rev,
		final Map<String, String> definitions,
		final List<EditorPreviewPlan.Child> newChildren,
		final float tabDur,
		final @Nullable Boolean loopValue,
		final @Nullable Vec3 position,
		final List<UUID> targets
	) {
		if (rev >= 0 && rev == this.revision) {
			return ok(syncBody(List.of(), List.of(), true));
		}
		if (rev >= 0 && rev < this.revision) {
			return error(409, "stale_revision", "expected revision " + this.revision + ", got " + rev);
		}
		final EditorPreviewPlan.Diff diff = EditorPreviewPlan.diff(this.applied, definitions);
		final List<String> appliedIds = new ArrayList<>();
		final List<String> rejectedIds = new ArrayList<>();
		for (final String id : diff.removed()) {
			final @Nullable Identifier rid = Identifier.tryParse(id);
			if (rid != null) {
				VFXDefinitionManager.get().unregisterLocal(rid);
			}
			this.applied.remove(id);
		}
		// Reject collection-typed children (design §16.8): a collection child would make the engine
		// schedule its own children through playReplay, bypassing this controller. Nested
		// collections are out of scope, so the child is rejected with a preview/error.
		final Set<String> collectionChildren = new LinkedHashSet<>();
		final List<EditorPreviewPlan.Child> acceptedChildren = new ArrayList<>();
		for (final EditorPreviewPlan.Child child : newChildren) {
			if (isCollectionJson(definitions.get(child.def()))) {
				collectionChildren.add(child.def());
			} else {
				acceptedChildren.add(child);
			}
		}
		final Map<Identifier, String> toRegister = new LinkedHashMap<>();
		for (final Map.Entry<String, String> entry : diff.changed().entrySet()) {
			final @Nullable Identifier rid = Identifier.tryParse(entry.getKey());
			if (rid == null || collectionChildren.contains(entry.getKey())) {
				rejectedIds.add(entry.getKey());
				continue;
			}
			toRegister.put(rid, entry.getValue());
		}
		final Set<Identifier> failed = toRegister.isEmpty() ? Set.of() : VFXDefinitionManager.get().registerLocal(toRegister);
		for (final Map.Entry<String, String> entry : diff.changed().entrySet()) {
			final @Nullable Identifier rid = Identifier.tryParse(entry.getKey());
			if (rid == null) {
				continue;
			}
			if (failed.contains(rid)) {
				// MERGE keeps the last-good definition; the id stays out of `applied` so the next
				// sync retries it, and it is never re-issued (it would render the rejected shape).
				rejectedIds.add(entry.getKey());
			} else {
				this.applied.put(entry.getKey(), entry.getValue());
				appliedIds.add(entry.getKey());
			}
		}
		final List<EditorPreviewPlan.Child> oldChildren = new ArrayList<>(this.children);
		this.children = acceptedChildren;
		if (tabDur >= 0.0F) {
			this.tabDuration = tabDur;
		}
		if (loopValue != null) {
			this.loop = loopValue;
		}
		this.contextPos = position;
		this.contextTargets = targets;
		this.revision = rev >= 0 ? rev : this.revision;
		reissueChanged(oldChildren, appliedIds);
		reconcile();
		for (final String id : collectionChildren) {
			if (!rejectedIds.contains(id)) {
				rejectedIds.add(id);
			}
		}
		this.lastRejected = List.copyOf(rejectedIds);
		if (!rejectedIds.isEmpty()) {
			broadcastError(rejectedIds.get(0), "rejected " + rejectedIds.size() + " definition(s): " + String.join(", ", rejectedIds));
		}
		emitState();
		return ok(syncBody(appliedIds, rejectedIds, false));
	}

	/** Removes the instances of children that are gone or whose definition/meta changed. */
	private void reissueChanged(final List<EditorPreviewPlan.Child> oldChildren, final List<String> appliedIds) {
		final Map<String, EditorPreviewPlan.Child> oldByKey = new LinkedHashMap<>();
		for (final EditorPreviewPlan.Child child : oldChildren) {
			oldByKey.put(child.key(), child);
		}
		final Set<String> newKeys = new LinkedHashSet<>();
		for (final EditorPreviewPlan.Child child : this.children) {
			newKeys.add(child.key());
		}
		for (final EditorPreviewPlan.Child old : oldChildren) {
			if (!newKeys.contains(old.key())) {
				removeChild(old.key());
				this.instances.remove(old.key());
			}
		}
		final Set<String> changedDefs = new LinkedHashSet<>(appliedIds);
		for (final EditorPreviewPlan.Child child : this.children) {
			final EditorPreviewPlan.Child old = oldByKey.get(child.key());
			final boolean metaChanged = old == null || !old.equals(child);
			if (metaChanged || changedDefs.contains(child.def())) {
				removeChild(child.key());
			}
		}
	}

	/**
	 * Projects the current children onto instances for the current tick: removes instances that are
	 * muted/soloed out or outside their window, (re)spawns visible due children, and applies the
	 * order rank. Instance ids are stable per key.
	 */
	private void reconcile() {
		final Set<String> desired = new LinkedHashSet<>();
		for (final EditorPreviewPlan.Child child : this.children) {
			if (EditorPreviewPlan.visible(child, this.muted, this.solo)
					&& EditorPreviewPlan.due(child, this.tick)) {
				desired.add(child.key());
			}
		}
		for (final Map.Entry<String, Long> entry : this.instances.entrySet()) {
			if (!desired.contains(entry.getKey())) {
				VFXEffectManager.get().removeInstance(entry.getValue());
			}
		}
		for (final EditorPreviewPlan.Child child : this.children) {
			if (!desired.contains(child.key())) {
				continue;
			}
			final @Nullable Identifier def = Identifier.tryParse(child.def());
			if (def == null || !VFXDefinitionManager.get().contains(def)) {
				continue;
			}
			final long id = this.instances.computeIfAbsent(child.key(), key -> VFXEffectManager.get().allocateInstanceId());
			final float age = EditorPreviewPlan.age(child, this.tick);
			if (find(id) == null) {
				spawn(child, def, id, age);
			} else {
				setAge(id, age);
			}
			if (child.isPost()) {
				applyOrder(id, EditorPreviewPlan.orderIndex(this.order, child.key()));
			}
		}
	}

	/**
	 * Applies the post-processing rank to an instance. Rank {@code 0} <b>clears</b> the override
	 * rather than writing a constant {@code 0}: an all-zero override set makes
	 * {@code VFXTimeline.isZeroedOut()} report the effect as finished, so every rank-0 child —
	 * including a single-post tab — would be reaped each tick and never render (design §16.2).
	 */
	private void applyOrder(final long id, final int index) {
		final @Nullable VFXActiveEffect effect = find(id);
		if (effect == null) {
			return;
		}
		if (index == 0) {
			effect.getTimeline().clearOverride("order");
		} else {
			effect.getTimeline().setOverride("order", index);
		}
	}

	private void spawn(final EditorPreviewPlan.Child child, final Identifier def, final long id, final float age) {
		final int duration = child.duration() > 0.0F ? Math.round(child.duration()) : 0;
		// `order` is applied per-instance in reconcile(): never pass it as a spawn constant, a
		// zero value would zero out the override set and the instance would be reaped (§16.2).
		final Map<String, Float> params = Map.of();
		// Anchor trigger so age == clock - trigger, then pin the exact age: the controller owns the
		// tick, so a pause/seek never depends on the shared clock's drift.
		final float trigger = VFXEffectManager.get().getClock() - age;
		VFXEffectManager.get().playReplay(def, duration, trigger, id, this.contextPos, this.contextTargets, params, null, false);
		setAge(id, age);
	}

	private void doPlay() {
		if (this.ended || this.tick >= this.tabDuration) {
			this.tick = 0.0F;
		}
		this.ended = false;
		this.playing = true;
		reconcile();
	}

	private void doSeek(final float target) {
		this.tick = Math.max(0.0F, target);
		this.ended = false;
		reconcile();
	}

	private void doStop() {
		this.playing = false;
		this.ended = false;
		this.tick = 0.0F;
		stopAllPreviewInstances();
	}

	private void stopAllPreviewInstances() {
		final Set<Long> ids = new LinkedHashSet<>(this.instances.values());
		VFXEffectManager.get().removeInstances(ids);
		removeActivePreviewInstances();
	}

	/**
	 * Removes every active instance whose effect id is one of the definitions this session
	 * registered, so no preview instance outlives a stop. Bounded by the active-effect cap and the
	 * session's applied definitions.
	 */
	private void removeActivePreviewInstances() {
		final Set<Long> ids = new LinkedHashSet<>();
		for (final VFXActiveEffect effect : VFXEffectManager.get().getActive()) {
			if (this.applied.containsKey(effect.getId().toString())) {
				ids.add(effect.getInstanceId());
			}
		}
		VFXEffectManager.get().removeInstances(ids);
	}

	private void removeChild(final String key) {
		final @Nullable Long id = this.instances.get(key);
		if (id != null) {
			VFXEffectManager.get().removeInstance(id);
		}
	}

	private void setAge(final long id, final float age) {
		final @Nullable VFXActiveEffect effect = find(id);
		if (effect != null) {
			effect.setAge(age);
		}
	}

	private @Nullable VFXActiveEffect find(final long id) {
		for (final VFXActiveEffect effect : VFXEffectManager.get().getActive()) {
			if (effect.getInstanceId() == id) {
				return effect;
			}
		}
		return null;
	}

	private @Nullable String defForKey(final String key) {
		for (final EditorPreviewPlan.Child child : this.children) {
			if (child.key().equals(key)) {
				return child.def();
			}
		}
		return null;
	}

	// --- events ----------------------------------------------------------------------------------

	private void emitState() {
		final JsonObject out = new JsonObject();
		out.addProperty("revision", this.revision);
		out.addProperty("playing", this.playing);
		out.addProperty("tick", this.tick);
		out.addProperty("ended", this.ended);
		// The last sync's rejects ride every state frame, so the web can reconcile resolved entries
		// (a defId-less preview/error is cleared by a later successful sync, design §16.9 P2).
		out.add("rejected", toArray(this.lastRejected));
		EditorSse.broadcast("preview/state", out.toString());
	}

	private void broadcastError(final @Nullable String defId, final String message) {
		final JsonObject out = new JsonObject();
		out.addProperty("revision", this.revision);
		out.addProperty("message", message);
		if (defId != null) {
			out.addProperty("defId", defId);
		}
		EditorSse.broadcast("preview/error", out.toString());
	}

	// --- helpers ---------------------------------------------------------------------------------

	private <T> Response submit(final java.util.function.Supplier<Response> task) {
		final Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.isSameThread()) {
			return task.get();
		}
		final CompletableFuture<Response> future = new CompletableFuture<>();
		minecraft.execute(() -> {
			try {
				future.complete(task.get());
			} catch (final Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get(APPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (final Exception e) {
			return error(503, "game_unavailable", "the game did not apply the request in time");
		}
	}

	private @Nullable Response gate(final long rev) {
		if (rev != this.revision) {
			return error(409, "stale_revision", "expected revision " + this.revision + ", got " + rev);
		}
		return null;
	}

	private static JsonObject syncBody(final List<String> appliedIds, final List<String> rejected, final boolean noop) {
		final JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.add("applied", toArray(appliedIds));
		out.add("rejected", toArray(rejected));
		if (noop) {
			out.addProperty("noop", true);
		}
		return out;
	}

	private static JsonArray toArray(final List<String> values) {
		final JsonArray array = new JsonArray();
		for (final String value : values) {
			array.add(value);
		}
		return array;
	}

	private static Response ok(final JsonObject body) {
		body.addProperty("ok", true);
		return new Response(200, body);
	}

	private static Response error(final int status, final String code, final @Nullable String detail) {
		final JsonObject out = new JsonObject();
		out.addProperty("ok", false);
		out.addProperty("error", code);
		if (detail != null) {
			out.addProperty("detail", detail);
		}
		return new Response(status, out);
	}

	private static String message(final Throwable e) {
		return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
	}

	private static Map<String, String> readStringMap(final @Nullable JsonObject object) {
		final Map<String, String> out = new LinkedHashMap<>();
		if (object == null) {
			return out;
		}
		for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
			final @Nullable JsonElement value = entry.getValue();
			if (value == null || value.isJsonNull()) {
				continue;
			}
			out.put(entry.getKey(), value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
				? value.getAsString() : value.toString());
		}
		return out;
	}

	private static List<EditorPreviewPlan.Child> readChildren(final @Nullable JsonObject meta) {
		final List<EditorPreviewPlan.Child> out = new ArrayList<>();
		if (meta == null) {
			return out;
		}
		final @Nullable JsonElement element = meta.get("children");
		if (element == null || !element.isJsonArray()) {
			return out;
		}
		for (final JsonElement childElement : element.getAsJsonArray()) {
			if (out.size() >= MAX_CHILDREN || !childElement.isJsonObject()) {
				break;
			}
			final JsonObject child = childElement.getAsJsonObject();
			final @Nullable String key = stringOrNull(child, "key");
			final @Nullable String def = stringOrNull(child, "def");
			if (key == null || def == null) {
				continue;
			}
			out.add(new EditorPreviewPlan.Child(key, def,
				floatOr(child, "delay", 0.0F), floatOr(child, "duration", 0.0F), boolOr(child, "isPost", false)));
		}
		return out;
	}

	/**
	 * True when a raw definition's {@code type} is {@code collection}: a collection child must not be
	 * played through {@code playReplay} (design §16.8). Never throws - a malformed definition is
	 * rejected later by {@code registerLocal}.
	 */
	private static boolean isCollectionJson(final @Nullable String raw) {
		if (raw == null) {
			return false;
		}
		try {
			final JsonElement parsed = JsonParser.parseString(raw);
			if (parsed.isJsonObject()) {
				final @Nullable JsonElement type = parsed.getAsJsonObject().get("type");
				return type != null && type.isJsonPrimitive() && "collection".equals(type.getAsString());
			}
		} catch (final RuntimeException ignored) {
			// a malformed definition is rejected by registerLocal, not here
		}
		return false;
	}

	private static List<String> readStringList(final @Nullable JsonArray array) {
		final List<String> out = new ArrayList<>();
		if (array == null) {
			return out;
		}
		for (final JsonElement element : array) {
			if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
				out.add(element.getAsString());
			}
		}
		return out;
	}

	private static @Nullable Vec3 readPosition(final @Nullable JsonObject context) {
		if (context == null) {
			return null;
		}
		final @Nullable JsonElement element = context.get("pos");
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() < 3) {
			return null;
		}
		try {
			final JsonArray pos = element.getAsJsonArray();
			return new Vec3(pos.get(0).getAsDouble(), pos.get(1).getAsDouble(), pos.get(2).getAsDouble());
		} catch (final RuntimeException e) {
			return null;
		}
	}

	private static List<UUID> readTargets(final @Nullable JsonObject context) {
		final List<UUID> out = new ArrayList<>();
		if (context == null) {
			return out;
		}
		final @Nullable JsonElement element = context.get("targets");
		if (element == null || !element.isJsonArray()) {
			return out;
		}
		for (final JsonElement target : element.getAsJsonArray()) {
			if (out.size() >= MAX_TARGETS) {
				break;
			}
			try {
				out.add(UUID.fromString(target.getAsString()));
			} catch (final RuntimeException ignored) {
				// a malformed selector is skipped; the rest still anchor
			}
		}
		return out;
	}

	private static @Nullable String stringOrNull(final JsonObject body, final String name) {
		final @Nullable JsonElement element = body.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return null;
		}
		return element.getAsString();
	}

	private static long longOr(final JsonObject body, final String name, final long fallback) {
		final @Nullable JsonElement element = body.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}
		return element.getAsLong();
	}

	private static float floatOr(final JsonObject body, final String name, final float fallback) {
		final @Nullable JsonElement element = body.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}
		return element.getAsFloat();
	}

	private static @Nullable Float floatOrNull(final JsonObject body, final String name) {
		final @Nullable JsonElement element = body.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return null;
		}
		return element.getAsFloat();
	}

	private static boolean boolOr(final JsonObject body, final String name, final boolean fallback) {
		final @Nullable JsonElement element = body.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			return fallback;
		}
		return element.getAsBoolean();
	}
}
