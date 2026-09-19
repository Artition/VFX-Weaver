package dev.vfxweaver.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.vfxweaver.api.VFXAPI;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.network.VFXTriggerPayload;
import dev.vfxweaver.resource.VFXDefinitionManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /vfx play <effect> [<targets>]} triggers a VFX effect,
 * {@code /vfx stop <effect> [<targets>]} stops it and {@code /vfx list} lists known effects.
 * {@code /vfx validate [namespace]} prints a dry-run health report of datapack definitions.
 */
public final class VFXCommand {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/command");

	private static final DynamicCommandExceptionType ERROR_UNKNOWN_EFFECT = new DynamicCommandExceptionType(
		id -> Component.translatable("commands.vfxweaver.unknown_effect", String.valueOf(id))
	);

	private static final Dynamic2CommandExceptionType ERROR_ANCHOR_NOT_FOUND = new Dynamic2CommandExceptionType(
		(id, selector) -> Component.translatable("commands.vfxweaver.anchor_not_found", String.valueOf(id), String.valueOf(selector))
	);

	private VFXCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext context, final Commands.CommandSelection selection) {
		dispatcher.register(
			Commands.literal("vfx")
				.then(
					Commands.literal("play")
						.requires(VFXCommand::requirePermission)
						.then(
							Commands.argument("effect", IdentifierArgument.id())
								.suggests(VFXCommand::suggestEffects)
								.executes(context2 -> play(context2, List.of(requirePlayer(context2)), Map.of()))
								.then(
									Commands.argument("targets", EntityArgument.players())
										.executes(context2 -> play(context2, EntityArgument.getPlayers(context2, "targets"), Map.of()))
								)
								.then(
									Commands.argument("params", new ParamMapArgument())
										.suggests(VFXCommand::suggestParamMap)
										.executes(context2 -> play(context2, List.of(requirePlayer(context2)), params(context2)))
										.then(
											Commands.argument("targets", EntityArgument.players())
												.executes(context2 -> play(context2, EntityArgument.getPlayers(context2, "targets"), params(context2)))
										)
								)
				)
			)
			.then(
				Commands.literal("playat")
					.requires(VFXCommand::requirePermission)
					.then(
						Commands.argument("effect", IdentifierArgument.id())
							.suggests(VFXCommand::suggestEffects)
							.then(
								Commands.argument("pos", BlockPosArgument.blockPos())
									.executes(context2 -> playAt(context2, List.of(requirePlayer(context2)), Map.of()))
									.then(
										Commands.argument("targets", EntityArgument.players())
											.executes(context2 -> playAt(context2, EntityArgument.getPlayers(context2, "targets"), Map.of()))
									)
									.then(
										Commands.argument("params", new ParamMapArgument())
											.suggests(VFXCommand::suggestParamMap)
											.executes(context2 -> playAt(context2, List.of(requirePlayer(context2)), params(context2)))
											.then(
												Commands.argument("targets", EntityArgument.players())
													.executes(context2 -> playAt(context2, EntityArgument.getPlayers(context2, "targets"), params(context2)))
											)
									)
							)
					)
			)
			.then(
				Commands.literal("playentity")
					.requires(VFXCommand::requirePermission)
					.then(
						Commands.argument("effect", IdentifierArgument.id())
							.suggests(VFXCommand::suggestEffects)
							.executes(context2 -> playEntity(context2, List.of(requirePlayer(context2)), List.of(requirePlayer(context2)), Map.of()))
							.then(
								Commands.argument("targets", EntityArgument.entities())
									.executes(context2 -> playEntity(context2, EntityArgument.getEntities(context2, "targets"), List.of(requirePlayer(context2)), Map.of()))
									.then(
										Commands.argument("players", EntityArgument.players())
											.executes(context2 -> playEntity(context2, EntityArgument.getEntities(context2, "targets"), EntityArgument.getPlayers(context2, "players"), Map.of()))
									)
							)
							.then(
								Commands.argument("params", new ParamMapArgument())
									.suggests(VFXCommand::suggestParamMap)
									.executes(context2 -> playEntity(context2, List.of(requirePlayer(context2)), List.of(requirePlayer(context2)), params(context2)))
									.then(
										Commands.argument("targets", EntityArgument.entities())
											.executes(context2 -> playEntity(context2, EntityArgument.getEntities(context2, "targets"), List.of(requirePlayer(context2)), params(context2)))
											.then(
												Commands.argument("players", EntityArgument.players())
													.executes(context2 -> playEntity(context2, EntityArgument.getEntities(context2, "targets"), EntityArgument.getPlayers(context2, "players"), params(context2)))
											)
									)
							)
					)
			)
			.then(
				Commands.literal("stop")
						.requires(VFXCommand::requirePermission)
						.then(
							Commands.argument("effect", IdentifierArgument.id())
								.suggests(VFXCommand::suggestEffects)
								.executes(context2 -> stop(context2, List.of(requirePlayer(context2))))
								.then(
									Commands.argument("targets", EntityArgument.players())
										.executes(context2 -> stop(context2, EntityArgument.getPlayers(context2, "targets")))
								)
						)
				)
				.then(
					Commands.literal("set")
						.requires(VFXCommand::requirePermission)
						.then(
							Commands.argument("effect", IdentifierArgument.id())
								.suggests(VFXCommand::suggestEffects)
								.then(
									Commands.argument("params", new ParamMapArgument())
										.suggests(VFXCommand::suggestParamMap)
										.executes(context2 -> setParams(context2, List.of(requirePlayer(context2))))
										.then(
											Commands.argument("targets", EntityArgument.players())
												.executes(context2 -> setParams(context2, EntityArgument.getPlayers(context2, "targets")))
										)
								)
						)
				)
				.then(Commands.literal("list").executes(VFXCommand::list))
			.then(
				Commands.literal("validate")
					.requires(VFXCommand::requirePermission)
					.executes(context2 -> validate(context2, null))
					.then(
						Commands.argument("namespace", StringArgumentType.string())
							.suggests(VFXCommand::suggestNamespaces)
							.executes(context2 -> validate(context2, StringArgumentType.getString(context2, "namespace")))
					)
			)
		);
	}

	/**
	 * Restricts the mutating subcommands ({@code play}/{@code playat}/{@code stop}/{@code set}/{@code key})
	 * to operators (gamemaster level 2). {@code list} stays available to everyone. This prevents a
	 * non-operator player from triggering or stopping VFX effects on other players' clients.
	 */
	private static boolean requirePermission(final CommandSourceStack source) {
		return source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
	}

	private static int play(final CommandContext<CommandSourceStack> context, final Collection<ServerPlayer> targets, final Map<String, Float> overrides) throws CommandSyntaxException {
		Identifier effectId = IdentifierArgument.getId(context, "effect");
		VFXDefinition definition = VFXDefinitionManager.get().get(effectId);
		if (definition == null) {
			throw ERROR_UNKNOWN_EFFECT.create(effectId.toString());
		}

		String selector = definition.getEntitySelector();
		if (selector != null && !selector.isBlank()) {
			// Entity-targeted effect: the datapack declares its own selector, so plain /vfx play
			// resolves it into target UUIDs and sends to the executor.
			return playEntity(context, resolveSelector(context, selector), List.of(requirePlayer(context)), overrides);
		}

		if (!definition.getEntityAnchors().isEmpty()) {
			// World overlay with entity-anchored positions: resolve every anchor selector against
			// the command source (first match wins) and ship the UUIDs in anchor order. An anchor
			// that matches nothing fails the command instead of producing a half-tracked effect.
			List<UUID> anchorUuids = new ArrayList<>();
			for (VFXDefinition.EntityAnchor anchor : definition.getEntityAnchors()) {
				if (anchorUuids.size() >= VFXTriggerPayload.MAX_ENTITY_UUIDS) {
					LOGGER.warn("Effect '{}' declares more than {} entity anchors; the rest are ignored", effectId, VFXTriggerPayload.MAX_ENTITY_UUIDS);
					break;
				}
				Collection<? extends Entity> found = resolveSelector(context, anchor.selector());
				if (found.isEmpty()) {
					throw ERROR_ANCHOR_NOT_FOUND.create(effectId.toString(), anchor.selector());
				}
				anchorUuids.add(found.iterator().next().getUUID());
			}
			for (ServerPlayer player : targets) {
				VFXAPI.sendEffect(player, effectId, 0L, null, anchorUuids, overrides, null);
			}
			context.getSource()
				.sendSuccess(
					() -> Component.translatable("commands.vfxweaver.played", effectId.toString(), targets.size()),
					false
				);
			return targets.size();
		}

		for (ServerPlayer player : targets) {
			VFXAPI.sendEffect(player, effectId, overrides, null);
		}

		context.getSource()
			.sendSuccess(
				() -> Component.translatable("commands.vfxweaver.played", effectId.toString(), targets.size()),
				false
			);
		return targets.size();
	}

	/**
	 * Parses a raw entity selector string and resolves it against the command source.
	 */
	private static List<? extends Entity> resolveSelector(final CommandContext<CommandSourceStack> context, final String selector) throws CommandSyntaxException {
		return new EntitySelectorParser(new StringReader(selector), true).parse().findEntities(context.getSource());
	}

	private static int playEntity(final CommandContext<CommandSourceStack> context, final Collection<? extends Entity> targets, final Collection<ServerPlayer> viewers, final Map<String, Float> overrides) throws CommandSyntaxException {
		Identifier effectId = IdentifierArgument.getId(context, "effect");
		if (!VFXDefinitionManager.get().contains(effectId)) {
			throw ERROR_UNKNOWN_EFFECT.create(effectId.toString());
		}

		List<UUID> entityUuids = new ArrayList<>();
		for (Entity entity : targets) {
			if (entityUuids.size() >= VFXTriggerPayload.MAX_ENTITY_UUIDS) {
				break;
			}
			entityUuids.add(entity.getUUID());
		}

		for (ServerPlayer viewer : viewers) {
			VFXAPI.sendEffect(viewer, effectId, 0L, null, entityUuids, overrides, null);
		}

		context.getSource()
			.sendSuccess(
				() -> Component.translatable("commands.vfxweaver.played", effectId.toString(), entityUuids.size()),
				false
			);
		return entityUuids.size();
	}

	private static int playAt(final CommandContext<CommandSourceStack> context, final Collection<ServerPlayer> targets, final Map<String, Float> overrides) throws CommandSyntaxException {
		Identifier effectId = IdentifierArgument.getId(context, "effect");
		if (!VFXDefinitionManager.get().contains(effectId)) {
			throw ERROR_UNKNOWN_EFFECT.create(effectId.toString());
		}
		BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
		Vec3 worldPos = Vec3.atCenterOf(pos);

		for (ServerPlayer player : targets) {
			VFXAPI.sendEffect(player, effectId, worldPos, overrides, null);
		}

		context.getSource()
			.sendSuccess(
				() -> Component.translatable("commands.vfxweaver.played_at", effectId.toString(), pos.getX(), pos.getY(), pos.getZ(), targets.size()),
				false
			);
		return targets.size();
	}

	private static int stop(final CommandContext<CommandSourceStack> context, final Collection<ServerPlayer> targets) {
		Identifier effectId = IdentifierArgument.getId(context, "effect");
		for (ServerPlayer player : targets) {
			VFXAPI.sendStop(player, effectId);
		}
		context.getSource()
			.sendSuccess(
				() -> Component.translatable("commands.vfxweaver.stopped", effectId.toString(), targets.size()),
				false
			);
		return targets.size();
	}

	private static int setParams(final CommandContext<CommandSourceStack> context, final Collection<ServerPlayer> targets) throws CommandSyntaxException {
		final Identifier effectId = IdentifierArgument.getId(context, "effect");
		@SuppressWarnings("unchecked")
		final Map<String, Float> params = context.getArgument("params", Map.class);
		for (final Map.Entry<String, Float> entry : params.entrySet()) {
			for (final ServerPlayer player : targets) {
				VFXAPI.sendSetParam(player, effectId, entry.getKey(), entry.getValue());
			}
		}
		context.getSource()
			.sendSuccess(
				() -> Component.translatable("commands.vfxweaver.set", params.size(), effectId.toString(), targets.size()),
				false
			);
		return targets.size();
	}

	private static int list(final CommandContext<CommandSourceStack> context) {
		VFXDefinitionManager definitions = VFXDefinitionManager.get();
		context.getSource()
			.sendSuccess(
				() -> Component.translatable("commands.vfxweaver.list_count", definitions.getDefinitions().size())
					.append(Component.literal(" " + String.join(", ", definitions.getDefinitions().keySet().stream().map(Identifier::toString).toList()))),
				false
			);
		// Surface datapack files that failed to parse so the author can find them quickly.
		if (!definitions.getParseErrors().isEmpty()) {
			context.getSource()
				.sendSuccess(
					() -> Component.translatable("commands.vfxweaver.list_errors", definitions.getParseErrors().size())
						.append(Component.literal(" " + definitions.getParseErrors().entrySet().stream()
							.map(e -> e.getKey() + ": " + e.getValue())
							.collect(java.util.stream.Collectors.joining(" | ")))),
					false
				);
		}
		return definitions.getDefinitions().size();
	}

	/**
	 * Dry-run health report of the known VFX definitions: prints how many definitions are
	 * loaded (optionally filtered by namespace) and every parse error recorded on the last
	 * reload, so a datapack author sees broken files without reading server logs.
	 *
	 * @param namespace optional namespace filter, {@code null} checks every definition
	 * @return the number of broken definitions found
	 */
	private static int validate(final CommandContext<CommandSourceStack> context, final String namespace) {
		final VFXDefinitionManager definitions = VFXDefinitionManager.get();
		final int checked = (int) definitions.getDefinitions().keySet().stream()
			.filter(id -> namespace == null || id.getNamespace().equals(namespace))
			.count();
		final List<Map.Entry<Identifier, String>> broken = definitions.getParseErrors().entrySet().stream()
			.filter(e -> namespace == null || e.getKey().getNamespace().equals(namespace))
			.toList();
		context.getSource()
			.sendSuccess(
				() -> Component.translatable(
					broken.isEmpty() ? "commands.vfxweaver.validated_clean" : "commands.vfxweaver.validated",
					checked,
					broken.size()
				),
				false
			);
		// One chat line per broken file, same detail format as /vfx list.
		for (final Map.Entry<Identifier, String> error : broken) {
			context.getSource()
				.sendSuccess(() -> Component.literal(error.getKey().toString() + ": " + error.getValue()), false);
		}
		return broken.size();
	}

	private static CompletableFuture<Suggestions> suggestEffects(final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggestResource(VFXDefinitionManager.get().getDefinitions().keySet().stream(), builder);
	}

	/**
	 * Suggests the distinct namespaces of the currently known effect definitions
	 * (for the optional {@code [namespace]} argument of {@code /vfx validate}).
	 */
	private static CompletableFuture<Suggestions> suggestNamespaces(final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(
			VFXDefinitionManager.get().getDefinitions().keySet().stream().map(Identifier::getNamespace).distinct().toList(),
			builder
		);
	}

	/**
	 * Suggests the next token of the {@code {[name:value],...}} argument, walking the syntax:
	 * {@code {} after nothing, {@code [} at group start, {@code name:} inside a group,
	 * {@code ]} after a value, {@code ,} / {@code }} after a closed group. Suggestions are
	 * anchored at the current segment (via {@code createOffset}) so Brigadier filters them
	 * against the segment being typed, not the whole argument text.
	 */
	private static CompletableFuture<Suggestions> suggestParamMap(final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {
		java.util.Set<String> params;
		try {
			final Identifier effectId = context.getArgument("effect", Identifier.class);
			final VFXDefinition definition = VFXDefinitionManager.get().get(effectId);
			params = definition != null ? definition.getParams().keySet() : java.util.Set.of();
		} catch (IllegalArgumentException ignored) {
			params = java.util.Set.of();
		}

		final String remaining = builder.getRemaining();
		if (remaining.isEmpty()) {
			return SharedSuggestionProvider.suggest(List.of("{"), builder);
		}
		if (!remaining.startsWith("{")) {
			return builder.buildFuture();
		}
		// Anchor for tokens appended after everything typed so far.
		final SuggestionsBuilder atEnd = builder.createOffset(builder.getStart() + remaining.length());
		// Strip the opening brace: only the group after the last comma is being typed.
		final String content = remaining.substring(1);
		final String segment = content.substring(content.lastIndexOf(',') + 1);
		if (segment.isEmpty()) {
			return SharedSuggestionProvider.suggest(List.of("["), atEnd);
		}
		if (!segment.startsWith("[")) {
			return builder.buildFuture();
		}
		final int colon = segment.indexOf(':');
		if (colon < 0) {
			// Anchor right after the '[' (opening brace + previous groups + the bracket
			// itself), so the typed name prefix filters the suggestions.
			final int anchor = builder.getStart() + 1 + (content.length() - segment.length()) + 1;
			final SuggestionsBuilder atName = builder.createOffset(anchor);
			final List<String> names = new ArrayList<>(params.size());
			for (final String param : params) {
				names.add(param + ":");
			}
			return SharedSuggestionProvider.suggest(names, atName);
		}
		final String valuePart = segment.substring(colon + 1);
		if (valuePart.endsWith("]")) {
			return SharedSuggestionProvider.suggest(List.of(",", "}"), atEnd);
		}
		if (valuePart.isEmpty()) {
			return builder.buildFuture();
		}
		return SharedSuggestionProvider.suggest(List.of("]"), atEnd);
	}

	private static ServerPlayer requirePlayer(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return context.getSource().getPlayerOrException();
	}

	/**
	 * Reads the optional {@code params} argument ({@code {[name:value],...}}) as an override map,
	 * or an empty map when the argument is absent.
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Float> params(final CommandContext<CommandSourceStack> context) {
		try {
			return context.getArgument("params", Map.class);
		} catch (IllegalArgumentException e) {
			return Map.of();
		}
	}
}
