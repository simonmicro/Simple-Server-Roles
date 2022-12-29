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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import static net.minecraft.server.command.CommandManager.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

public class SimpleServerRoles implements ModInitializer {
	public static final String MOD_ID = "simple_server_roles";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final String TEAM_NAME_PREFIX = MOD_ID + ".";

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

	private String validateRoleNameString(String name) throws CommandSyntaxException {
		if(name.length() > 32)
			throw new SimpleCommandExceptionType(Text.of("role name is too long")).create();
		return name;
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

		// Check if in a team
		Team team = scoreboard.getPlayerTeam(player.getName().getString());
		if(team == null)
			throw new SimpleCommandExceptionType(Text.of("you are not in a role")).create();

		// Make sure the team is a role
		if(!team.getName().startsWith(TEAM_NAME_PREFIX))
			throw new SimpleCommandExceptionType(Text.of("you are not in a role, you are in a normal team")).create();

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

			context.getSource().sendFeedback(Text.of("Available roles are:"), false);
			for(Team team : scoreboard.getTeams()) {
				if(team.getName().startsWith(TEAM_NAME_PREFIX)) {
					context.getSource().sendFeedback(Text.of("- " + team.getDisplayName().getString()), false);
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
			
			String name = this.validateRoleNameString(StringArgumentType.getString(context, "name"));
			String id = TEAM_NAME_PREFIX + this.makeMD5(name);
			
			// check if already exists
			Team team = scoreboard.getTeam(id);
			if(team != null)
				throw new SimpleCommandExceptionType(Text.of("role already exists")).create();
			
			// add team
			team = scoreboard.addTeam(id);
			team.setDisplayName(Text.of(name));
			team.setPrefix(Text.of("[" + name + "] "));
			team.setColor(net.minecraft.util.Formatting.RED); // TODO default color is random?
			context.getSource().sendFeedback(Text.of("Added role " + name), false);

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

			String name = this.validateRoleNameString(StringArgumentType.getString(context, "name"));
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

		// Show welcome message to joined players
		Set<ServerPlayerEntity> knownPlayers = new HashSet();
		ServerTickEvents.START_SERVER_TICK.register(server -> {
			// I'm so sorry to use the server tick for this, but Fabric doesn't have a PlayerJoinEvent
			for(ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				if(knownPlayers.add(player)) {
					// Is the player part of a role?
					Team team = server.getScoreboard().getPlayerTeam(player.getName().getString());
					if(team != null && team.getName().startsWith(TEAM_NAME_PREFIX) && team.getPlayerList().size() > 2) { // Print only if part of a role with at leat one other player
						// Get list of online players
						LinkedList<String> teamMembers = new LinkedList();
						for(String playerName: team.getPlayerList()) {
							if(playerName.equals(player.getName().getString()))
								continue;
							if(server.getPlayerManager().getPlayerList().contains(server.getPlayerManager().getPlayer(playerName))) {
								teamMembers.add("§a" + playerName + "§f");
							} else {
								teamMembers.add("§4" + playerName + "§f");
							}
						}
						player.sendMessage(Text.of("Welcome! Here are the members of your role: " + String.join(",", teamMembers)), false);
					}
				}
			}
			for(ServerPlayerEntity player : knownPlayers) {
				if(!server.getPlayerManager().getPlayerList().contains(player)) {
					knownPlayers.remove(player);
				}
			}
		});

		// IDEA roles edit <name> <color|name> <value>
		// TODO add edit for color
		// TODO add edit for displayname
		// TODO add edit for style (bold, italic, etc)
	}
}
