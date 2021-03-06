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

package com.forgenz.horses.database;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import com.forgenz.forgecore.v1_0.util.BukkitConfigUtil;
import com.forgenz.horses.HorseType;
import com.forgenz.horses.Horses;
import com.forgenz.horses.PlayerHorse;
import com.forgenz.horses.Stable;

public class YamlDatabase extends HorseDatabase
{
	private static final String PLAYER_DATA_FOLDER = "playerdata";
	private static final String PLAYER_DATA_LOCATION = PLAYER_DATA_FOLDER + File.separatorChar + "%s.yml";
	private static final String GROUPED_PLAYER_DATA_LOCATION = PLAYER_DATA_FOLDER + File.separatorChar + "%s" + File.separatorChar + "%s.yml";
	
	public YamlDatabase(Horses plugin)
	{
		super(plugin, HorseDatabaseStorageType.YAML);
	}
	
	private File getPlayersConfigFile(String player, String stableGroup)
	{
		if (stableGroup.equals(HorseDatabase.DEFAULT_GROUP))
			return new File(getPlugin().getDataFolder(), String.format(PLAYER_DATA_LOCATION, player));
		else
			return new File(getPlugin().getDataFolder(), String.format(GROUPED_PLAYER_DATA_LOCATION, stableGroup, player));
	}
	
	private File getPlayersConfigFile(OfflinePlayer player, String stableGroup) {
		if (!Bukkit.getOnlineMode()) {
			return this.getPlayersConfigFile(player.getName(), stableGroup);
		}
		
		File uuidFile = this.getPlayersConfigFile(player.getUniqueId().toString(), stableGroup);
		
		if (uuidFile.exists()) {
			return uuidFile;
		}
		
		File nameFile = this.getPlayersConfigFile(player.getName(), stableGroup);
		
		if (nameFile.exists()) {
			// Move file
			return nameFile.renameTo(uuidFile) ? uuidFile : nameFile;
		}
		
		return uuidFile;
	}
	
	private YamlConfiguration getPlayerConfig(OfflinePlayer player, String stableGroup)
	{
		YamlConfiguration cfg = new YamlConfiguration();
		
		File file = getPlayersConfigFile(player, stableGroup);
		
		if (file.exists())
		{
			try
			{
				cfg.load(file);
			}
			catch (FileNotFoundException e)
			{
				e.printStackTrace();
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
			catch (InvalidConfigurationException e)
			{
				e.printStackTrace();
			}
		}
		
		return cfg;
	}
	
	@Override
	protected List<Stable> loadEverything()
	{
		File playerDataFolder = new File(getPlugin().getDataFolder(), PLAYER_DATA_FOLDER);
		
		if (!playerDataFolder.isDirectory())
		{
			return Collections.emptyList();
		}
		
		ArrayList<Stable> stables = new ArrayList<Stable>();
		
		loadStableGroup(playerDataFolder, stables, true);
		
		return stables;
	}
	
	@Override
	protected void importStables(List<Stable> stables)
	{
		for (Stable stable : stables)
			saveStable(stable);
	}
	
	private void loadStableGroup(File folder, ArrayList<Stable> stables, boolean recursive)
	{
		String groupName = folder.getName().equals(PLAYER_DATA_FOLDER) ? DEFAULT_GROUP : folder.getName();
		Pattern extentionReplace = Pattern.compile("\\.yml$", Pattern.CASE_INSENSITIVE);
		
		File[] fileList = folder.listFiles();
		// Don't waste time expanding the list
		stables.ensureCapacity(fileList.length + stables.size());
		
		for (File file : fileList)
		{
			// Check for additional stable groups
			if (file.isDirectory())
			{
				// Load the groups stables
				if (recursive)
					loadStableGroup(file, stables, false);
				continue;
			}
			
			String playerName = extentionReplace.matcher(file.getName()).replaceAll("");
			stables.add(loadStable(playerName, groupName));
		}
	}

	@Override
	protected Stable loadStable(String player, String stableGroup)
	{
		Stable stable = new Stable(getPlugin(), stableGroup, player);
		
		loadHorses(stable, stableGroup);
		
		return stable;
	}

	@Override
	protected void loadHorses(Stable stable, String stableGroup)
	{
		YamlConfiguration cfg = getPlayerConfig(Bukkit.getOfflinePlayer(stable.getOwner()), stableGroup);
		
		ConfigurationSection sect = BukkitConfigUtil.getAndSetConfigurationSection(cfg, "Horses");
		
		for (String horse : sect.getKeys(false))
		{
			ConfigurationSection horseSect = sect.getConfigurationSection(horse);
			
			HorseType type = HorseType.exactValueOf(horseSect.getString("type", HorseType.White.toString()));
			long lastDeath = horseSect.getLong("lastdeath") * 1000;
			double maxHealth = horseSect.getDouble("maxhealth");
			double health = horseSect.getDouble("health");
			double speed = horseSect.getDouble("speed", 0.225);
			double jumpStrength = horseSect.getDouble("jumpstrength", 0.7);
			boolean hasChest = type == HorseType.Mule || type == HorseType.Donkey ? horseSect.getBoolean("chest", false) : false;
			
			// Temporary Hack to fix old storage
			boolean saddle = false;
			if (horseSect.isBoolean("saddle"))
				saddle = horseSect.getBoolean("saddle", false);
			
			// Temporary hack for old storage
			Material armour = null;
			if (horseSect.isString("armour"))
				armour = Material.getMaterial(horseSect.getString("armour", "null"));
			
			ArrayList<ItemStack> items = new ArrayList<ItemStack>();
			
			for (Map<?, ?> itemMap : horseSect.getMapList("inventory"))
			{
				int slot = -1;
				
				try
				{
					slot = (Integer) itemMap.get("slot");
					
				}
				catch (NullPointerException e)
				{
					getPlugin().log(Level.SEVERE, "Player '%s' data file is corrupt: Inventory slot number was missing", e, stable.getOwner());
					continue;
				}
				catch (ClassCastException e)
				{
					getPlugin().log(Level.SEVERE, "Player '%s' data file is corrupt: Inventory slot number was not a number", e, stable.getOwner());
					continue;
				}
				
				@SuppressWarnings("unchecked")
				ItemStack item = ItemStack.deserialize((Map<String, Object>) itemMap);
				
				// Fill in the gaps with nothing
				while (items.size() <= slot)
					items.add(null);
				
				items.set(slot, item);
			}
			
			PlayerHorse horseData = new PlayerHorse(getPlugin(), stable, horse, type, maxHealth, health, speed, jumpStrength, null);
			horseData.setLastDeath(lastDeath);
			
			horseData.setItems(items.toArray(new ItemStack[items.size()]));
			if (saddle)
				horseData.setSaddle(Material.SADDLE);
			if (armour != null)
				horseData.setArmour(armour);
			horseData.setHasChest(hasChest);
			
			
			stable.addHorse(horseData);
		}
		
		if (cfg.isString("lastactive"))
		{
			PlayerHorse horse = stable.findHorse(cfg.getString("lastactive"), true);
			stable.setLastActiveHorse(horse);
		}
	}

	@Override
	protected void saveStable(Stable stable)
	{
		// Fetch the file to save data to
		File playerDataFile = getPlayersConfigFile(Bukkit.getOfflinePlayer(stable.getOwner()), stable.getGroup());
		
		// Delete the players config file if the player has no horses
		if (stable.getHorseCount() == 0)
		{
			if (playerDataFile.exists())
				playerDataFile.delete();
			return;
		}
		
		YamlConfiguration cfg = new YamlConfiguration();
		
		if (stable.getLastActiveHorse() != null)
			cfg.set("lastactive", stable.getLastActiveHorse().getName());
		else
			cfg.set("lastactive", null);
		
		ConfigurationSection sect = BukkitConfigUtil.getAndSetConfigurationSection(cfg, "Horses");
		
		for (PlayerHorse horse : stable)
		{
			String colourCodedDisplayName = COLOUR_CHAR_REPLACE.matcher(horse.getDisplayName()).replaceAll("&");
			ConfigurationSection horseSect = BukkitConfigUtil.getAndSetConfigurationSection(sect, colourCodedDisplayName);
			
			horseSect.set("type", horse.getType().toString());
			horseSect.set("lastdeath", horse.getLastDeath() / 1000);
			horseSect.set("maxhealth", horse.getMaxHealth());
			horseSect.set("health", horse.getHealth());
			horseSect.set("speed", horse.getSpeed());
			horseSect.set("jumpstrength", horse.getJumpStrength());
			if (horse.getType() == HorseType.Mule || horse.getType() == HorseType.Donkey)
			{
				horseSect.set("chest", horse.hasChest());
			}
			else
			{
				horseSect.set("chest", null);
			}
			
			// Remove old config nodes
			horseSect.set("saddle", null);
			horseSect.set("armour", null);
			
			// Save the inventory contents
			ArrayList<Map<String, Object>> itemList = new ArrayList<Map<String, Object>>();
			
			ItemStack[] items = horse.getItems();
			for (int i = 0; i < items.length; ++i)
			{
				if (items[i] == null)
					continue;
				
				Map<String, Object> item = items[i].serialize();
				
				item.put("slot", i);
				
				itemList.add(item);
			}
			horseSect.set("inventory", itemList);
		}
		
		try
		{
			cfg.save(playerDataFile);
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}

	@Override
	public void saveHorse(PlayerHorse horse)
	{
		saveStable(horse.getStable());
	}

	@Override
	public boolean deleteHorse(PlayerHorse horse)
	{
		saveStable(horse.getStable());
		return true;
	}

	public boolean migrateToUuidDb() {
		File dataFolder = new File(super.getPlugin().getDataFolder(), PLAYER_DATA_FOLDER);
		
		return migrateFile(dataFolder, true);
	}
	
	private boolean migrateFile(File file, boolean top) {
		if (file.isDirectory()) {
			if (!top) {
				return true;
			}
			boolean success = true;
			for (File data : file.listFiles()) {
				success = success && this.migrateFile(data, false);
			}
			return success;
		}
		
		if (!file.getName().endsWith(".yml")) {
			return true;
		}
		
		String fileName = file.getName().substring(0, file.getName().length() - ".yml".length());
		
		try {
			UUID.fromString(fileName);
			// Already migrated
			return true;
		} catch (IllegalArgumentException e) {
		}
		
		OfflinePlayer player = Bukkit.getOfflinePlayer(fileName);
		
		UUID id = player.getUniqueId();
		
		if (id == null) {
			return false;
		}
		
		File migratedFile = new File(file.getParentFile(), id.toString() + ".yml");
		
		if (migratedFile.exists()) {
			this.getPlugin().getLogger().info(String.format("Player has two datafiles '%s' and '%s'", migratedFile.getPath(), file.getPath()));
			return false;
		}
		
		return file.renameTo(migratedFile);
	}
}
