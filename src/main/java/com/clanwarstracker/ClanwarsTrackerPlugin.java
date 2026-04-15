package com.clanwarstracker;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.HitsplatID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Slf4j
@PluginDescriptor(
		name = "Clan Wars Multi Tracker",
		description = "Tracks multi-target PvP performance in Clan Wars, including ZGS special attack accuracy",
		tags = {"pvp", "clanwars", "tracker", "multi-target"}
)
public class ClanwarsTrackerPlugin extends Plugin
{
	private static final int ZGS_SPEC_ANIM      = 7638;
	private static final int ICE_BARRAGE_ANIM   = 1979;
	private static final int SPEC_RESOLVE_TICKS = 4;

	// Ice Barrage freezes for 32 ticks; after breaking early, 5 tick immunity applies.
	// Full immunity (if freeze runs out naturally) is 37 ticks from application.
	private static final int FREEZE_DURATION     = 32;
	private static final int IMMUNE_DURATION     = 37;
	private static final int FREEZE_BREAK_IMMUNE = 5;

	// OSRS uses Chebyshev distance for ranging. Ice Barrage max range = 10 tiles.
	private static final int MAX_FREEZE_RANGE = 10;

	private static final int ZGS_ITEM_ID    = 11808;
	private static final int ZGS_OR_ITEM_ID = 20368;

	// How many ticks between writing lifetime stats to disk (approx every ~30s)
	private static final int SAVE_INTERVAL_TICKS = 50;

	public enum Role { NONE, DEFENDER, MAGE }

	@Inject private Client client;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ClanwarsTrackerConfig config;
	@Inject private ConfigManager configManager;

	private ClanwarsTrackerPanel panel;
	private NavigationButton navButton;

	private final Map<String, Integer> damageMap = new HashMap<>();

	// ZGS: tracks spec outcomes per target
	private final Map<String, Integer> zgsHitsPerPlayer     = new HashMap<>();
	private final Map<String, Integer> zgsSplashesPerPlayer = new HashMap<>();

	// Barrage: tracks casts and successful freezes per target
	private final Map<String, Integer> barragecastsPerPlayer   = new HashMap<>();
	private final Map<String, Integer> barrageFreezesPerPlayer = new HashMap<>();

	// Pending ZGS specs waiting to resolve (hit or splash)
	private static class ZgsState
	{
		int ticksLeft;
		boolean resolved;
	}

	private final Map<String, ZgsState> pendingZgsSpec = new HashMap<>();

	// Freeze / immunity state per target name
	private final Map<String, Integer>    freezeEndTick   = new HashMap<>();
	private final Map<String, Integer>    immuneEndTick   = new HashMap<>();
	// Stores a WorldPoint snapshot (not a live Actor reference) of where the target
	// was when frozen, so we can do plane-aware early-break detection without
	// holding a stale Actor reference across floor transitions.
	private final Map<String, WorldPoint> frozenLocations = new HashMap<>();

	private Role currentRole = Role.NONE;

	private int lifetimeZgsSpecs       = 0;
	private int lifetimeZgsHits        = 0;
	private int lifetimeBarrageCasts   = 0;
	private int lifetimeBarrageFreezes = 0;

	// Dirty flag + tick counter to throttle disk writes
	private boolean statsDirty        = false;
	private int     ticksSinceLastSave = 0;

	// -------------------------------------------------------
	// Freeze helpers
	// -------------------------------------------------------

	private boolean isFrozen(String name)
	{
		return freezeEndTick.getOrDefault(name, 0) > client.getTickCount();
	}

	private boolean isImmune(String name)
	{
		return immuneEndTick.getOrDefault(name, 0) > client.getTickCount();
	}

	private void applyFreeze(String name, Actor actor)
	{
		int now = client.getTickCount();
		freezeEndTick.put(name, now + FREEZE_DURATION);
		immuneEndTick.put(name, now + IMMUNE_DURATION);
		// Snapshot the WorldPoint — plain value object, never goes stale
		frozenLocations.put(name, actor.getWorldLocation());
	}

	private void checkFreezeBreaks()
	{
		if (frozenLocations.isEmpty()) return;
		Player local = client.getLocalPlayer();
		if (local == null) return;

		Iterator<Map.Entry<String, WorldPoint>> it = frozenLocations.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<String, WorldPoint> entry = it.next();
			String     name      = entry.getKey();
			WorldPoint frozenAt  = entry.getValue();

			// Clean up entries whose freeze has already expired naturally
			if (!isFrozen(name))
			{
				it.remove();
				continue;
			}

			// Only attempt an early break if we are on the same plane as the target.
			// If we're on a different floor we cannot observe the target moving, so
			// the freeze timer should keep running undisturbed.
			if (local.getWorldLocation().getPlane() != frozenAt.getPlane())
			{
				continue;
			}

			int dx = Math.abs(local.getWorldLocation().getX() - frozenAt.getX());
			int dy = Math.abs(local.getWorldLocation().getY() - frozenAt.getY());
			boolean outOfRange = Math.max(dx, dy) > MAX_FREEZE_RANGE;

			if (outOfRange)
			{
				freezeEndTick.remove(name);
				immuneEndTick.put(name, client.getTickCount() + FREEZE_BREAK_IMMUNE);
				it.remove();
			}
		}
	}

	// -------------------------------------------------------
	// Equipment role detection
	// -------------------------------------------------------

	private boolean isZgsEquipped()
	{
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null) return false;
		Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon == null) return false;
		return weapon.getId() == ZGS_ITEM_ID || weapon.getId() == ZGS_OR_ITEM_ID;
	}

	// -------------------------------------------------------
	// Startup / Shutdown
	// -------------------------------------------------------

	@Override
	protected void startUp() throws Exception
	{
		panel = new ClanwarsTrackerPanel(this);
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/clanwarstracker/Bandages.png");
		navButton = NavigationButton.builder()
				.tooltip("Clanwars Tracker")
				.icon(icon)
				.priority(5)
				.panel(panel)
				.build();
		clientToolbar.addNavigation(navButton);
		loadLifetimeStats();
		panel.updateSummary(currentRole, lifetimeZgsSpecs, lifetimeZgsHits,
				lifetimeBarrageCasts, lifetimeBarrageFreezes);
	}

	@Override
	protected void shutDown() throws Exception
	{
		// Always flush to disk on shutdown so no stats are lost
		if (statsDirty)
		{
			flushLifetimeStats();
		}
		clientToolbar.removeNavigation(navButton);
		damageMap.clear();
		zgsHitsPerPlayer.clear();
		zgsSplashesPerPlayer.clear();
		barragecastsPerPlayer.clear();
		barrageFreezesPerPlayer.clear();
		pendingZgsSpec.clear();
		freezeEndTick.clear();
		immuneEndTick.clear();
		frozenLocations.clear();
	}

	// -------------------------------------------------------
	// Config persistence — throttled to avoid per-hitsplat disk writes
	// -------------------------------------------------------

	private void loadLifetimeStats()
	{
		lifetimeZgsSpecs       = getInt("lifetimeZgsSpecs");
		lifetimeZgsHits        = getInt("lifetimeZgsHits");
		lifetimeBarrageCasts   = getInt("lifetimeBarrageCasts");
		lifetimeBarrageFreezes = getInt("lifetimeBarrageFreezes");
	}

	/**
	 * Mark stats as needing a save. Actual write happens in onGameTick
	 * on a timer so we never call setConfiguration more than once every
	 * SAVE_INTERVAL_TICKS ticks (~30 seconds).
	 */
	private void markStatsDirty()
	{
		statsDirty = true;
	}

	private void flushLifetimeStats()
	{
		setInt("lifetimeZgsSpecs",       lifetimeZgsSpecs);
		setInt("lifetimeZgsHits",        lifetimeZgsHits);
		setInt("lifetimeBarrageCasts",   lifetimeBarrageCasts);
		setInt("lifetimeBarrageFreezes", lifetimeBarrageFreezes);
		statsDirty = false;
		ticksSinceLastSave = 0;
	}

	private int getInt(String key)
	{
		String val = configManager.getConfiguration("clanwarstracker", key);
		if (val == null) return 0;
		try { return Integer.parseInt(val); }
		catch (NumberFormatException e) { return 0; }
	}

	private void setInt(String key, int value)
	{
		configManager.setConfiguration("clanwarstracker", key, value);
	}

	// -------------------------------------------------------
	// Equipment changed — update role when weapon changes
	// -------------------------------------------------------

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.EQUIPMENT.getId()) return;

		if (isZgsEquipped())
		{
			currentRole = Role.DEFENDER;
		}
		else if (currentRole == Role.DEFENDER)
		{
			// ZGS was unequipped — revert to NONE so role can be reassigned
			// (e.g. player switches to staff mid-fight)
			currentRole = Role.NONE;
		}

		panel.updateSummary(currentRole, lifetimeZgsSpecs, lifetimeZgsHits,
				lifetimeBarrageCasts, lifetimeBarrageFreezes);
	}

	// -------------------------------------------------------
	// Game Tick
	// -------------------------------------------------------

	@Subscribe
	public void onGameTick(GameTick event)
	{
		checkFreezeBreaks();

		// Tick down pending ZGS specs; unresolved ones become splashes
		Iterator<Map.Entry<String, ZgsState>> it = pendingZgsSpec.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<String, ZgsState> entry = it.next();
			ZgsState state = entry.getValue();
			state.ticksLeft--;

			if (state.ticksLeft <= 0)
			{
				if (!state.resolved)
				{
					zgsSplashesPerPlayer.merge(entry.getKey(), 1, Integer::sum);
					panel.updateDamage(
							new HashMap<>(damageMap),
							new HashMap<>(zgsHitsPerPlayer),
							new HashMap<>(zgsSplashesPerPlayer),
							new HashMap<>(barragecastsPerPlayer),
							new HashMap<>(barrageFreezesPerPlayer),
							currentRole);
				}
				it.remove();
			}
		}

		// Throttled disk write — flush dirty stats roughly every 30 seconds
		if (statsDirty)
		{
			ticksSinceLastSave++;
			if (ticksSinceLastSave >= SAVE_INTERVAL_TICKS)
			{
				flushLifetimeStats();
			}
		}
	}

	// -------------------------------------------------------
	// Animation — ZGS spec activated or barrage cast
	// -------------------------------------------------------

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (!(event.getActor() instanceof Player)) return;
		Player player = (Player) event.getActor();
		if (player != client.getLocalPlayer()) return;

		int anim = player.getAnimation();

		if (anim == ZGS_SPEC_ANIM)
		{
			currentRole = Role.DEFENDER;
			Actor target = client.getLocalPlayer().getInteracting();
			if (target instanceof Player && target != client.getLocalPlayer())
			{
				String targetName = target.getName();
				if (targetName != null)
				{
					ZgsState state = new ZgsState();
					state.ticksLeft = SPEC_RESOLVE_TICKS;
					state.resolved  = false;

					pendingZgsSpec.put(targetName, state);
					lifetimeZgsSpecs++;
					markStatsDirty();

					panel.updateSummary(currentRole, lifetimeZgsSpecs, lifetimeZgsHits,
							lifetimeBarrageCasts, lifetimeBarrageFreezes);
				}
			}
		}
		else if (anim == ICE_BARRAGE_ANIM)
		{
			currentRole = Role.MAGE;
			lifetimeBarrageCasts++;
			markStatsDirty();

			Actor target = client.getLocalPlayer().getInteracting();
			if (target instanceof Player && target != client.getLocalPlayer())
			{
				String targetName = target.getName();
				if (targetName != null)
				{
					barragecastsPerPlayer.merge(targetName, 1, Integer::sum);
					panel.updateDamage(
							new HashMap<>(damageMap),
							new HashMap<>(zgsHitsPerPlayer),
							new HashMap<>(zgsSplashesPerPlayer),
							new HashMap<>(barragecastsPerPlayer),
							new HashMap<>(barrageFreezesPerPlayer),
							currentRole);
				}
			}

			panel.updateSummary(currentRole, lifetimeZgsSpecs, lifetimeZgsHits,
					lifetimeBarrageCasts, lifetimeBarrageFreezes);
		}
	}

	// -------------------------------------------------------
	// Hitsplat
	// -------------------------------------------------------

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor target = event.getActor();
		if (client.getLocalPlayer() == null) return;
		if (target == null) return;
		if (!event.getHitsplat().isMine()) return;
		if (!(target instanceof Player)) return;
		if (target == client.getLocalPlayer()) return;

		// Health ratio 0 = target is already dead; ignore trailing hitsplats
		if (target.getHealthRatio() == 0) return;

		int hitsplatType = event.getHitsplat().getHitsplatType();
		if (hitsplatType != HitsplatID.DAMAGE_ME &&
				hitsplatType != HitsplatID.DAMAGE_ME_CYAN &&
				hitsplatType != HitsplatID.DAMAGE_ME_ORANGE &&
				hitsplatType != HitsplatID.DAMAGE_ME_WHITE &&
				hitsplatType != HitsplatID.DAMAGE_ME_YELLOW) return;

		String targetName = target.getName();
		if (targetName == null) return;

		damageMap.merge(targetName, event.getHitsplat().getAmount(), Integer::sum);

		// ZGS: any hitsplat within the resolve window = spec landed
		if (currentRole == Role.DEFENDER)
		{
			ZgsState state = pendingZgsSpec.get(targetName);
			if (state != null && !state.resolved)
			{
				state.resolved = true;
				zgsHitsPerPlayer.merge(targetName, 1, Integer::sum);
				lifetimeZgsHits++;
				markStatsDirty();

				panel.updateSummary(currentRole, lifetimeZgsSpecs, lifetimeZgsHits,
						lifetimeBarrageCasts, lifetimeBarrageFreezes);
			}
		}

		// Barrage: hitsplat on a non-frozen, non-immune target = new freeze landed
		if (currentRole == Role.MAGE)
		{
			boolean wasFrozen = isFrozen(targetName);
			boolean immune    = isImmune(targetName);

			if (!wasFrozen && !immune)
			{
				barrageFreezesPerPlayer.merge(targetName, 1, Integer::sum);
				lifetimeBarrageFreezes++;
				markStatsDirty();

				panel.updateSummary(currentRole, lifetimeZgsSpecs, lifetimeZgsHits,
						lifetimeBarrageCasts, lifetimeBarrageFreezes);
			}

			if (!wasFrozen)
			{
				applyFreeze(targetName, target);
			}
		}

		// Snapshot all maps before handing to the panel (EDT safety)
		panel.updateDamage(
				new HashMap<>(damageMap),
				new HashMap<>(zgsHitsPerPlayer),
				new HashMap<>(zgsSplashesPerPlayer),
				new HashMap<>(barragecastsPerPlayer),
				new HashMap<>(barrageFreezesPerPlayer),
				currentRole);
	}

	// -------------------------------------------------------
	// Death
	// -------------------------------------------------------

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof Player)) return;
		if (actor == client.getLocalPlayer()) return;
		String name = actor.getName();
		if (name == null) return;
		if (damageMap.containsKey(name))
		{
			damageMap.put(name, 0);
			panel.updateDamage(
					new HashMap<>(damageMap),
					new HashMap<>(zgsHitsPerPlayer),
					new HashMap<>(zgsSplashesPerPlayer),
					new HashMap<>(barragecastsPerPlayer),
					new HashMap<>(barrageFreezesPerPlayer),
					currentRole);
		}
	}

	// -------------------------------------------------------
	// Panel reset methods (called from EDT via button clicks)
	// -------------------------------------------------------

	public void resetTarget(String targetName)
	{
		damageMap.put(targetName, 0);
		panel.updateDamage(
				new HashMap<>(damageMap),
				new HashMap<>(zgsHitsPerPlayer),
				new HashMap<>(zgsSplashesPerPlayer),
				new HashMap<>(barragecastsPerPlayer),
				new HashMap<>(barrageFreezesPerPlayer),
				currentRole);
	}

	public void resetSpecData(String targetName)
	{
		zgsHitsPerPlayer.remove(targetName);
		zgsSplashesPerPlayer.remove(targetName);
		barragecastsPerPlayer.remove(targetName);
		barrageFreezesPerPlayer.remove(targetName);
		freezeEndTick.remove(targetName);
		immuneEndTick.remove(targetName);
		frozenLocations.remove(targetName);
		panel.updateDamage(
				new HashMap<>(damageMap),
				new HashMap<>(zgsHitsPerPlayer),
				new HashMap<>(zgsSplashesPerPlayer),
				new HashMap<>(barragecastsPerPlayer),
				new HashMap<>(barrageFreezesPerPlayer),
				currentRole);
	}

	public void resetAll()
	{
		damageMap.clear();
		zgsHitsPerPlayer.clear();
		zgsSplashesPerPlayer.clear();
		barragecastsPerPlayer.clear();
		barrageFreezesPerPlayer.clear();
		currentRole = Role.NONE;
		pendingZgsSpec.clear();
		freezeEndTick.clear();
		immuneEndTick.clear();
		frozenLocations.clear();
		lifetimeZgsSpecs       = 0;
		lifetimeZgsHits        = 0;
		lifetimeBarrageCasts   = 0;
		lifetimeBarrageFreezes = 0;
		flushLifetimeStats(); // explicit flush — user asked for a full reset
		panel.updateDamage(
				new HashMap<>(damageMap),
				new HashMap<>(zgsHitsPerPlayer),
				new HashMap<>(zgsSplashesPerPlayer),
				new HashMap<>(barragecastsPerPlayer),
				new HashMap<>(barrageFreezesPerPlayer),
				currentRole);
		panel.updateSummary(currentRole, lifetimeZgsSpecs, lifetimeZgsHits,
				lifetimeBarrageCasts, lifetimeBarrageFreezes);
	}

	@Provides
	ClanwarsTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClanwarsTrackerConfig.class);
	}
}