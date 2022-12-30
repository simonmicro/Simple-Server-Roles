package com.simonmicro.simple_server_roles;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import static net.minecraft.server.command.CommandManager.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class SimpleServerRoles implements ModInitializer {
	public static final String MOD_ID = "simple_server_roles";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final String TEAM_NAME_PREFIX = MOD_ID + ".";
	private Random randomGenerator = new Random();

	private class RoleEditAttributeSuggestions implements SuggestionProvider<ServerCommandSource> {
        @Override
        public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
			builder.suggest("name");
			builder.suggest("color");
    
            return builder.buildFuture();
        }
    }

	private String makeMD5(String string) {
		String ret = "";
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			md.update(string.getBytes());
			byte[] digest = md.digest();
			for (byte b : digest) {
				ret += Integer.toHexString((b & 0xFF) | 0x100).substring(1, 3);
			}
		} catch(NoSuchAlgorithmException e) {
			LOGGER.error("MD5 not found");
			return null;
		}
		return ret;
	}

	private ServerPlayerEntity getPlayer(ServerCommandSource source) throws CommandSyntaxException {
		ServerPlayerEntity player = source.getPlayer();
		if(player == null)
			throw new SimpleCommandExceptionType(Text.of("internal error: player ref is null (this is a bug)")).create();
		return player;
	}

	private String checkRoleName(String name) throws CommandSyntaxException {
		if(name.length() > 32)
			throw new SimpleCommandExceptionType(Text.of("role name is too long")).create();
		if(name.length() < 4)
			throw new SimpleCommandExceptionType(Text.of("role name is too short")).create();
		return name;
	}

	private Team checkPlayerInRole(ServerCommandSource source) throws CommandSyntaxException {
		Scoreboard scoreboard = source.getServer().getScoreboard();
		ServerPlayerEntity player = this.getPlayer(source);

		// Check if in a team
		Team team = scoreboard.getPlayerTeam(player.getName().getString());
		if(team == null)
			throw new SimpleCommandExceptionType(Text.of("you are not in a role")).create();

		// Make sure the team is a role
		if(!team.getName().startsWith(TEAM_NAME_PREFIX))
			throw new SimpleCommandExceptionType(Text.of("you are not in a role, you are in a normal team")).create();
		return team;
	}

	private void joinRole(ServerCommandSource source, Team team) throws CommandSyntaxException {
		Scoreboard scoreboard = source.getServer().getScoreboard();
		ServerPlayerEntity player = this.getPlayer(source);

		// leave any current role
		Team oldTeam = scoreboard.getPlayerTeam(player.getName().getString());
		if(oldTeam != null)
			this.leaveRole(source);
		
		// add player to team
		if(!scoreboard.addPlayerToTeam(player.getName().getString(), team))
			LOGGER.warn("Failed to add player to team");
			source.sendFeedback(Text.of("Joined role " + team.getDisplayName().getString()), false);
	}

	private void leaveRole(ServerCommandSource source) throws CommandSyntaxException {
		Scoreboard scoreboard = source.getServer().getScoreboard();
		ServerPlayerEntity player = this.getPlayer(source);

		Team team = this.checkPlayerInRole(source);

		// Leave team
		scoreboard.removePlayerFromTeam(player.getName().getString(), team);
		source.sendFeedback(Text.of("Left role " + team.getDisplayName().getString()), false);
			
		// Destroy team if empty
		if(team.getPlayerList().isEmpty()) {
			scoreboard.removeTeam(team);
			source.sendFeedback(Text.of("Removed role " + team.getDisplayName().getString() + ", because it has no members anymore."), false);
		}
	}

	@Override
	public void onInitialize() {
		// Register the commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(literal("roles").then(literal("list").executes(context -> {
			ServerCommandSource source = context.getSource();
			MinecraftServer server = source.getServer();
			Scoreboard scoreboard = server.getScoreboard();
			
			// TODO handling for no teams

			source.sendFeedback(Text.of("Available roles are:"), false);
			for(Team team : scoreboard.getTeams()) {
				if(team.getName().startsWith(TEAM_NAME_PREFIX)) {
					source.sendFeedback(Text.of("- " + team.getDisplayName().getString()), false);
				}
			}

			return 1;
		}))));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			literal("roles").then(
				literal("create").then(CommandManager.argument("name", StringArgumentType.string()).executes(context -> {
			ServerCommandSource source = context.getSource();
			MinecraftServer server = source.getServer();
			Scoreboard scoreboard = server.getScoreboard();
			
			String name = this.checkRoleName(StringArgumentType.getString(context, "name"));
			String id = TEAM_NAME_PREFIX + this.makeMD5(name);
			
			// check if already exists
			Team team = scoreboard.getTeam(id);
			if(team != null)
				throw new SimpleCommandExceptionType(Text.of("role already exists")).create();
			
			// add team
			team = scoreboard.addTeam(id);
			team.setDisplayName(Text.of(name));
			team.setPrefix(Text.of("[" + name + "] "));
			List<String> colors = Formatting.getNames(true, false).stream().collect(Collectors.toCollection(LinkedList::new));
			team.setColor(Formatting.byName(colors.get(this.randomGenerator.nextInt(colors.size())))); // It is assumed, that at least one color exists
			source.sendFeedback(Text.of("Added role " + name), false);

			// join team
			this.joinRole(source, team);
			
			return 1;
		})))));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			literal("roles").then(
				literal("join").then(CommandManager.argument("name", StringArgumentType.string()).executes(context -> {
			ServerCommandSource source = context.getSource();
			MinecraftServer server = source.getServer();
			Scoreboard scoreboard = server.getScoreboard();

			String name = this.checkRoleName(StringArgumentType.getString(context, "name"));
			String id = TEAM_NAME_PREFIX + this.makeMD5(name);
			
			// check if exists
			Team team = scoreboard.getTeam(id);
			if(team == null)
				throw new SimpleCommandExceptionType(Text.of("role does not exist, use \"list\" to look for them")).create();

			this.joinRole(source, team);
			return 1;
		})))));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			literal("roles").then(literal("leave").executes(context -> {
				this.leaveRole(context.getSource());
				return 1;
			}
		))));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			literal("roles").then(literal("edit")
				.then(CommandManager.argument("property", StringArgumentType.word()).suggests(new RoleEditAttributeSuggestions())
				.then(CommandManager.argument("value", StringArgumentType.string()).executes(context -> {
				ServerCommandSource source = context.getSource();

				Team team = this.checkPlayerInRole(source);

				String property = this.checkRoleName(StringArgumentType.getString(context, "property"));
				String value = this.checkRoleName(StringArgumentType.getString(context, "value"));
				if(property.equals("name")) {
					this.checkRoleName(value);
					team.setDisplayName(Text.of(value));
					team.setPrefix(Text.of("[" + value + "] "));
					source.sendFeedback(Text.of("Changed role name to " + value), false);
				} else if(property.equals("color")) {
					Formatting color = Formatting.byName(value);
					if(color == null)
						throw new SimpleCommandExceptionType(Text.of("unknown color - try one of " + String.join(", ", Formatting.getNames(true, false)))).create();
					if(!color.isColor())
						throw new SimpleCommandExceptionType(Text.of("value is not a color")).create();
					team.setColor(color);
					source.sendFeedback(Text.of("Changed role color to " + color.name()), false);
				} else
					throw new SimpleCommandExceptionType(Text.of("unknown property")).create();
				return 1;
			}
		))))));

		// Show welcome message to joined players
		Set<ServerPlayerEntity> knownPlayers = new HashSet<>();
		ServerTickEvents.START_SERVER_TICK.register(server -> {
			try {
				// I'm so sorry to use the server tick for this, but Fabric doesn't have a PlayerJoinEvent
				for(ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
					if(knownPlayers.add(player)) {
						// Is the player part of a role?
						Team team = server.getScoreboard().getPlayerTeam(player.getName().getString());
						if(team != null && team.getName().startsWith(TEAM_NAME_PREFIX) && team.getPlayerList().size() > 2) { // Print only if part of a role with at leat one other player
							// Get list of online players
							LinkedList<String> teamMembers = new LinkedList<>();
							for(String playerName: team.getPlayerList()) {
								if(playerName.equals(player.getName().getString()))
									continue;
								if(server.getPlayerManager().getPlayerList().contains(server.getPlayerManager().getPlayer(playerName))) {
									teamMembers.add(Formatting.FORMATTING_CODE_PREFIX + Formatting.GREEN.getCode() + playerName + Formatting.FORMATTING_CODE_PREFIX + Formatting.WHITE.getCode());
								} else {
									teamMembers.add(Formatting.FORMATTING_CODE_PREFIX + Formatting.DARK_RED.getCode() + playerName + Formatting.FORMATTING_CODE_PREFIX + Formatting.WHITE.getCode());
								}
							}
							player.sendMessage(Text.of("Welcome! Here are the members of your role: " + String.join(", ", teamMembers)), false);
						}
					}
				}
				for(ServerPlayerEntity player : knownPlayers) {
					if(!server.getPlayerManager().getPlayerList().contains(player)) {
						knownPlayers.remove(player);
					}
				}
			} catch(ConcurrentModificationException e) {
				// Ignore, maybe a player just left...
			}
		});
	}
}
