package com.simonmicro.simple_server_roles;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.ArgumentTypes;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ClearCommand;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.MessageCommand;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import static net.minecraft.server.command.CommandManager.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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
			throw new SimpleCommandExceptionType(Text.of("internal error: player ref is null")).create();
		return player;
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
		// Register some commands

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
			
			String name = StringArgumentType.getString(context, "name");
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

			String name = StringArgumentType.getString(context, "name");
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

		// TODO add name length limit to 32 characters
		// TODO name sanitizer
		// IDEA roles edit <name> <color|name> <value>
		// TODO add edit for color
		// TODO add edit for displayname
		// TODO add edit for style (bold, italic, etc)
		// TODO on join show list of other role members (online)
	}
}
