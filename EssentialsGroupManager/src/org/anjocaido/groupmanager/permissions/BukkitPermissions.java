/*
* This program is free software; you can redistribute it and/or
* modify it under the terms of the GNU General Public License
* as published by the Free Software Foundation; either version 2
* of the License, or (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program; if not, write to the Free Software
* Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
*/

package org.anjocaido.groupmanager.permissions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.anjocaido.groupmanager.GroupManager;
import org.anjocaido.groupmanager.data.User;
import org.anjocaido.groupmanager.dataholder.OverloadedWorldHolder;
import org.anjocaido.groupmanager.utils.PermissionCheckResult;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerListener;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;


/**
 * 
 * BukkitPermissions overrides to force GM reponses to Superperms
 * 
 * @author ElgarL, based upon PermissionsEX implementation
 */
public class BukkitPermissions {

	protected Map<Player, PermissionAttachment> attachments = new HashMap<Player, PermissionAttachment>();
	protected LinkedList<Permission> registeredPermissions = new LinkedList<Permission>();
	protected GroupManager plugin;
	protected boolean dumpAllPermissions = true;
	protected boolean dumpMatchedPermissions = true;
	public boolean player_join = false;

	public BukkitPermissions(GroupManager plugin) {
		this.plugin = plugin;

		this.collectPermissions();
		this.registerEvents();

		this.updateAllPlayers();

		GroupManager.logger.info("Superperms support enabled.");
	}

	private void registerEvents() {
		PluginManager manager = plugin.getServer().getPluginManager();

		PlayerEvents playerEventListener = new PlayerEvents();

		manager.registerEvent(Event.Type.PLAYER_JOIN, playerEventListener, Event.Priority.Lowest, plugin);
		manager.registerEvent(Event.Type.PLAYER_KICK, playerEventListener, Event.Priority.Lowest, plugin);
		manager.registerEvent(Event.Type.PLAYER_QUIT, playerEventListener, Event.Priority.Lowest, plugin);

		manager.registerEvent(Event.Type.PLAYER_RESPAWN, playerEventListener, Event.Priority.Lowest, plugin);
		manager.registerEvent(Event.Type.PLAYER_TELEPORT, playerEventListener, Event.Priority.Lowest, plugin);
		manager.registerEvent(Event.Type.PLAYER_PORTAL, playerEventListener, Event.Priority.Lowest, plugin);

		ServerListener serverListener = new BukkitEvents();

		manager.registerEvent(Event.Type.PLUGIN_ENABLE, serverListener, Event.Priority.Normal, plugin);
		manager.registerEvent(Event.Type.PLUGIN_DISABLE, serverListener, Event.Priority.Normal, plugin);
	}

	public void collectPermissions() {
		registeredPermissions.clear();
		for (Plugin bukkitPlugin : Bukkit.getServer().getPluginManager().getPlugins()) {
			for (Permission permission : bukkitPlugin.getDescription().getPermissions())
				registeredPermissions.push(permission);
		}
	}

	public void updatePermissions(Player player) {
		this.updatePermissions(player, null);
	}

	public void updatePermissions(Player player, String world) {
		if (player == null || !GroupManager.isLoaded()) {
			return;
		}

		if (!this.attachments.containsKey(player)) {
			this.attachments.put(player, player.addAttachment(plugin));
		}

		if (world == null) {
			world = player.getWorld().getName();
		}

		// All permissions registered with Bukkit for this player
		PermissionAttachment attachment = this.attachments.get(player);

		OverloadedWorldHolder worldData = plugin.getWorldsHolder().getWorldData(world);

		User user = worldData.getUser(player.getName());

		// clear permissions
		for (String permission : attachment.getPermissions().keySet())
			attachment.unsetPermission(permission);

		/*
		 * find matching permissions
		 * 
		 * and base bukkit perms if we are set to allow bukkit permissions to
		 * override.
		 */
		Boolean value = false;
				
		for (Permission permission : registeredPermissions) {
			
			PermissionCheckResult result = worldData.getPermissionsHandler().checkFullGMPermission(user, permission.getName(), false);

			// Only check bukkit override IF we don't have the permission
			// directly.
			if (result.resultType == PermissionCheckResult.Type.NOTFOUND) {
				PermissionDefault permDefault = permission.getDefault();

				if ((plugin.getGMConfig().isBukkitPermsOverride()) && ((permDefault == PermissionDefault.TRUE)
						|| ((permDefault == PermissionDefault.NOT_OP) && !player.isOp())
						|| ((permDefault == PermissionDefault.OP) && player.isOp()))) {
					value = true;
				} else {
					value = false;
				}
			} else if (result.resultType == PermissionCheckResult.Type.NEGATION) {
				value = false;
			} else {
				value = true;
			}

			// Set the root permission
			if ((value == true) || (result.resultType == PermissionCheckResult.Type.NEGATION)) {
				attachment.setPermission(permission, value);
			}
			/*
			if ((value == true) || (result.resultType == PermissionCheckResult.Type.NOTFOUND)) {
				// fetch and set all children of this permission node
				Map<String, Boolean> children = permission.getChildren();
				if (children != null) {
					for (String child : children.keySet()) {
						if (children.get(child))
							attachment.setPermission(child, value);
					}
				}
			}*/

		}

		// Add any missing permissions for this player (non bukkit plugins and child nodes)
		List<String> playerPermArray = worldData.getPermissionsHandler().getAllPlayersPermissions(player.getName());

		for (String permission : playerPermArray) {
			value = true;
			if (permission.startsWith("-")) {
				permission = permission.substring(1); // cut off -
				value = false;
			}
			
			if (!attachment.getPermissions().containsKey(permission)) {
				attachment.setPermission(permission, value);
			}
		}
		player.recalculatePermissions();
	}

	/**
	 * Returns a map of the ALL child permissions as defined by the supplying plugin
	 * null is empty
	 * 
	 * @param node
	 * @return Map of child permissions
	 */
	public Map<String, Boolean> getAllChildren(String node, List<String> playerPermArray) {
		
		LinkedList<String> stack = new LinkedList<String>();
		Map<String, Boolean> alreadyVisited = new HashMap<String, Boolean>();
		stack.push(node);
		alreadyVisited.put(node, true);
		
		while (!stack.isEmpty()) {
			String now = stack.pop();
			
			Map<String, Boolean> children = getChildren(now);
			
			if ((children != null) && (!playerPermArray.contains("-"+now))) {
				for (String childName : children.keySet()) {
					if (!alreadyVisited.containsKey(childName)) {
						stack.push(childName);
						alreadyVisited.put(childName, children.get(childName));
					}
				}
			}
		}
		alreadyVisited.remove(node);
		if (!alreadyVisited.isEmpty()) return alreadyVisited;
		
		return null;
	}
		
	/**
	 * Returns a map of the child permissions (1 node deep) as defined by the supplying plugin
	 * null is empty
	 * 
	 * @param node
	 * @return Map of child permissions
	 */
	public Map<String, Boolean> getChildren(String node) {
		for (Permission permission : registeredPermissions) {
			if (permission.getName().equalsIgnoreCase(node)) {
				return permission.getChildren();
			}
		}
		
		return null;
	}

	public List<String> listPerms(Player player) {
		List<String> perms = new ArrayList<String>();

		/*
		 * // All permissions registered with Bukkit for this player
		 * PermissionAttachment attachment = this.attachments.get(player);
		 * 
		 * // List perms for this player perms.add("Attachment Permissions:");
		 * for(Map.Entry<String, Boolean> entry :
		 * attachment.getPermissions().entrySet()){ perms.add(" " +
		 * entry.getKey() + " = " + entry.getValue()); }
		 */

		perms.add("Effective Permissions:");
		for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
			if (info.getValue() == true)
				perms.add(" " + info.getPermission() + " = " + info.getValue());
		}
		return perms;
	}

	public void updateAllPlayers() {
		for (Player player : Bukkit.getServer().getOnlinePlayers()) {
			updatePermissions(player);
		}
	}

	protected class PlayerEvents extends PlayerListener {

		@Override
		public void onPlayerJoin(PlayerJoinEvent event) {
			player_join = true;
			Player player = event.getPlayer();
			// force GM to create the player if they are not already listed.
			if (plugin.getWorldsHolder().getWorldData(player.getWorld().getName()).getUser(player.getName()) != null) {
				player_join = false;
				updatePermissions(event.getPlayer());
			} else
				player_join = false;
		}

		@Override
		public void onPlayerPortal(PlayerPortalEvent event) { // will portal into another world
			if (event.getTo() != null && !event.getFrom().getWorld().equals(event.getTo().getWorld())) { // only if world actually changed
				updatePermissions(event.getPlayer(), event.getTo().getWorld().getName());
			}
		}

		@Override
		public void onPlayerRespawn(PlayerRespawnEvent event) { // can be respawned in another world
			updatePermissions(event.getPlayer(), event.getRespawnLocation().getWorld().getName());
		}

		@Override
		public void onPlayerTeleport(PlayerTeleportEvent event) { // can be teleported into another world
			if (event.getTo() != null && !event.getFrom().getWorld().equals(event.getTo().getWorld())) { // only if world actually changed
				updatePermissions(event.getPlayer(), event.getTo().getWorld().getName());
			}
		}

		@Override
		public void onPlayerQuit(PlayerQuitEvent event) {
			if (!GroupManager.isLoaded())
				return;

			attachments.remove(event.getPlayer());
		}

		@Override
		public void onPlayerKick(PlayerKickEvent event) {
			attachments.remove(event.getPlayer());
		}
	}

	protected class BukkitEvents extends ServerListener {

		@Override
		public void onPluginEnable(PluginEnableEvent event) {
			if (!GroupManager.isLoaded())
				return;

			collectPermissions();
			updateAllPlayers();
		}

		@Override
		public void onPluginDisable(PluginDisableEvent event) {
			// collectPermissions();
			// updateAllPlayers();
		}
	}

}