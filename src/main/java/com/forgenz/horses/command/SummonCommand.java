/*
 * Copyright 2013 Michael McKnight. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and contributors and should not be interpreted as representing official policies,
 * either expressed or implied, of anybody else.
 */

package com.forgenz.horses.command;

import static com.forgenz.horses.Messages.Command_Summon_Description;
import static com.forgenz.horses.Messages.Command_Summon_Error_AlreadySummoning;
import static com.forgenz.horses.Messages.Command_Summon_Error_MovedWhileSummoning;
import static com.forgenz.horses.Messages.Command_Summon_Error_NoLastActiveHorse;
import static com.forgenz.horses.Messages.Command_Summon_Error_OnDeathCooldown;
import static com.forgenz.horses.Messages.Command_Summon_Error_WorldGuard_CantUseSummonHere;
import static com.forgenz.horses.Messages.Command_Summon_Success_SummonedHorse;
import static com.forgenz.horses.Messages.Command_Summon_Success_SummoningHorse;
import static com.forgenz.horses.Messages.Misc_Command_Error_ConfigDenyPerm;
import static com.forgenz.horses.Messages.Misc_Command_Error_InvalidName;
import static com.forgenz.horses.Messages.Misc_Command_Error_NoHorseNamed;
import static com.forgenz.horses.Messages.Misc_Words_Horse;
import static com.forgenz.horses.Messages.Misc_Words_Name;

import java.util.WeakHashMap;
import java.util.regex.Pattern;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import com.forgenz.forgecore.v1_0.bukkit.ForgePlugin;
import com.forgenz.forgecore.v1_0.command.ForgeArgs;
import com.forgenz.forgecore.v1_0.command.ForgeCommand;
import com.forgenz.forgecore.v1_0.command.ForgeCommandArgument;
import com.forgenz.horses.Horses;
import com.forgenz.horses.PlayerHorse;
import com.forgenz.horses.Stable;
import com.forgenz.horses.config.HorsesConfig;
import com.forgenz.horses.config.HorsesPermissionConfig;

public class SummonCommand extends ForgeCommand
{
	private final WeakHashMap<Player, Long> summonTasks = new WeakHashMap<Player, Long>();
	private final int pluginLoadCount;
	private final Location cacheLoc = new Location(null, 0.0, 0.0, 0.0);
	
	public SummonCommand(ForgePlugin plugin)
	{
		super(plugin);
		
		pluginLoadCount = plugin.getLoadCount();
		
		registerAlias("summon", true);
		registerAlias("s", false);
		registerPermission("horses.command.summon");
		
		registerArgument(new ForgeCommandArgument(getPlugin().getHorsesConfig().forceEnglishCharacters ? "^[a-z0-9_&]+$" : "^[^ ]+$", Pattern.CASE_INSENSITIVE, true, Misc_Command_Error_InvalidName.toString()));
		
		setAllowOp(true);
		setAllowConsole(false);
		setArgumentString(String.format("<%1$s%2$s>", Misc_Words_Horse, Misc_Words_Name));
		setDescription(Command_Summon_Description.toString());
	}

	@Override
	protected void onCommand(CommandSender sender, ForgeArgs args)
	{
		final Player player = (Player) sender;
		
		HorsesConfig cfg = getPlugin().getHorsesConfig();
		HorsesPermissionConfig pcfg = cfg.getPermConfig(player);
		
		if (!pcfg.allowSummonCommand)
		{
			Misc_Command_Error_ConfigDenyPerm.sendMessage(sender, getMainCommand());
			return;
		}
		
		Long lastSummon = summonTasks.get(player);
		if (lastSummon != null)
		{
			if (System.currentTimeMillis() - lastSummon > pcfg.summonDelay * 1000)
			{
				summonTasks.remove(player);
			}
			else
			{
				Command_Summon_Error_AlreadySummoning.sendMessage(player);
				return;
			}
		}
		
		final Stable stable = getPlugin().getHorseDatabase().getPlayersStable(player);
		final PlayerHorse horse;
		
		// Check if we are summoning the last active horse
		if (args.getNumArgs() == 0)
		{
			horse = stable.getLastActiveHorse();
			
			if (horse == null)
			{
				Command_Summon_Error_NoLastActiveHorse.sendMessage(player);
				return;
			}
		}
		else
		{
			horse = stable.findHorse(args.getArg(0), false);
			
			if (horse == null)
			{
				Misc_Command_Error_NoHorseNamed.sendMessage(player, args.getArg(0));
				return;
			}
		}
		
		// Check if the horse is on a death cooldown
		long timeDiff = System.currentTimeMillis() - horse.getLastDeath();
		if (pcfg.deathCooldown > timeDiff)
		{
			Command_Summon_Error_OnDeathCooldown.sendMessage(player, horse.getDisplayName(), (pcfg.deathCooldown - timeDiff) / 1000);
			return;
		}
		
		// Check if the player is in the correct region to use this command
		if (cfg.worldGuardCfg != null && !cfg.worldGuardCfg.allowCommand(cfg.worldGuardCfg.commandSummonAllowedRegions, player.getLocation(cacheLoc)))
		{
			Command_Summon_Error_WorldGuard_CantUseSummonHere.sendMessage(player);
			return;
		}
		
		int tickDelay = pcfg.summonDelay * 20;
		if (tickDelay <= 0)
		{
			horse.spawnHorse(player);
			Command_Summon_Success_SummonedHorse.sendMessage(player, horse.getDisplayName());
		}
		else
		{
			final long startTime = System.currentTimeMillis();
			
			BukkitRunnable task = new BukkitRunnable()
			{
				@Override
				public void run()
				{
					// Validate that this task should run
					Long storedStartTime = summonTasks.get(player);
					// If the key does not exist or the value is incorrect we return
					if (storedStartTime == null || storedStartTime.longValue() != startTime)
						return;
					
					// Don't let horses summon after reload
					if (pluginLoadCount != getPlugin().getLoadCount())
						return;
					
					// Remove the time from the map
					summonTasks.remove(player);
					
					// Only summon the horse if the player is still alive
					if (player.isValid())
					{
						horse.spawnHorse(player);
						Command_Summon_Success_SummonedHorse.sendMessage(player, horse.getDisplayName());
					}
				}
			};

			task.runTaskLater(getPlugin(), tickDelay);
			summonTasks.put(player, startTime);
			Command_Summon_Success_SummoningHorse.sendMessage(player, horse.getDisplayName(), pcfg.summonDelay);
		}
	}
	
	public void cancelSummon(Player player)
	{
		if (player != null && summonTasks.remove(player) != null)
			Command_Summon_Error_MovedWhileSummoning.sendMessage(player);
	}
	
	public boolean isSummoning(Player player)
	{
		return player != null && summonTasks.containsKey(player);
	}

	@Override
	public Horses getPlugin()
	{
		return (Horses) super.getPlugin();
	}
}
