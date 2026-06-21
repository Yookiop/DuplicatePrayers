package com.duplicateprayers;

import com.google.inject.Provides;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.ParamID;
import net.runelite.api.Point;
import net.runelite.api.Prayer;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetDrag;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import static net.runelite.api.widgets.WidgetConfig.DRAG;
import static net.runelite.api.widgets.WidgetConfig.DRAG_ON;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextMenuInput;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.menus.WidgetMenuOption;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
		name = "Duplicate Prayers",
		description = "Allows prayers to appear in multiple prayer book slots",
		tags = {"prayer", "duplicate", "reorder"}
)
public class DuplicatePrayersPlugin extends Plugin
{
	private static final int PRAYER_X_OFFSET = 37;
	private static final int PRAYER_Y_OFFSET = 37;
	private static final int PRAYER_COLUMN_COUNT = 5;
	private static final int MAX_VISIBLE_PRAYERS = 30;
	private static final int HIDE_OP = 3;
	private static final int DUPLICATE_OP = 2;
	private static final int REMOVE_DUPLICATE_OP = 3;
	private static final String HIDE = "Hide Prayer";
	private static final String UNHIDE = "Unhide Prayer";
	private static final int ACTIVATE_OP = 1;
	private static final String PRAYER_CONFIG_GROUP = "prayer";
	private static final String DUPLICATE = "Duplicate";
	private static final String REMOVE_DUPLICATE = "Remove duplicate";
	private static final String LOCK_REORDERING = "Disable prayer reordering";
	private static final String UNLOCK_REORDERING = "Enable prayer reordering";
	private static final String MANAGE_HIDDEN_PRAYERS = "Manage hidden prayers";

	private static final WidgetMenuOption FIXED_MANAGE_HIDDEN_PRAYERS = new WidgetMenuOption(MANAGE_HIDDEN_PRAYERS,
			"", InterfaceID.Toplevel.STONE5);

	private static final WidgetMenuOption RESIZABLE_MANAGE_HIDDEN_PRAYERS = new WidgetMenuOption(MANAGE_HIDDEN_PRAYERS,
			"", InterfaceID.ToplevelOsrsStretch.STONE5);

	private static final WidgetMenuOption RESIZABLE_BOTTOM_LINE_MANAGE_HIDDEN_PRAYERS = new WidgetMenuOption(MANAGE_HIDDEN_PRAYERS,
			"", InterfaceID.ToplevelPreEoc.STONE5);

	@Inject
	private Client client;

	@Inject
	private DuplicatePrayersConfig config;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private MenuManager menuManager;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	private final Map<Widget, Slot> duplicateWidgets = new HashMap<>();
	private final Map<Widget, Widget> duplicateRoots = new HashMap<>();
	private final Set<Integer> hiddenPrayerIds = new HashSet<>();
	private boolean prayerReordering;
	private int lastPrayerPoints = -1;
	private int lastHideToggleTick = -1;
	private int lastHideTogglePrayerId = -1;

	@Override
	protected void startUp()
	{
		addPrayerTabMenus();

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::redrawPrayers);
		}
	}

	@Override
	protected void shutDown()
	{
		removePrayerTabMenus();

		clientThread.invokeLater(() ->
		{
			clearDuplicateWidgets();
			redrawPrayers();
		});
	}

	@Override
	public void resetConfiguration()
	{
		for (var key : configManager.getConfigurationKeys(DuplicatePrayersConfig.GROUP + ".prayer_slots_book_"))
		{
			String[] str = key.split("\\.", 2);
			if (str.length == 2)
			{
				configManager.unsetConfiguration(str[0], str[1]);
			}
		}

		clientThread.invokeLater(() ->
		{
			clearDuplicateWidgets();
			rebuildPrayers();
		});
	}

	private void addPrayerTabMenus()
	{
		// Optie verwijderd op verzoek
	}

	private void removePrayerTabMenus()
	{
		// Optie verwijderd op verzoek
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		clientThread.invokeLater(this::redrawPrayers);
	}

	private void syncAllDuplicatePrayerStates()
	{
		for (Map.Entry<Widget, Slot> entry : duplicateWidgets.entrySet())
		{
			Widget duplicate = entry.getKey();
			Slot slot = entry.getValue();

			if (duplicate == null || duplicate.isSelfHidden())
			{
				continue;
			}

			syncDuplicateActiveState(duplicate, slot);
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.PRAYER_REDRAW)
		{
			if (isPrayerBookOpen())
			{
				rebuildPrayers();
			}
		}

		if (event.getScriptId() == ScriptID.PRAYER_UPDATEBUTTON)
		{
			if (prayerReordering && isPrayerBookOpen())
			{
				rebuildPrayers();
			}
			else
			{
				syncAllDuplicatePrayerStates();
			}
		}
	}

	private void logPrayerUpdate(int scriptId)
	{
		int prayerPoints = client.getBoostedSkillLevel(Skill.PRAYER);
		if (scriptId == ScriptID.PRAYER_UPDATEBUTTON && prayerPoints != lastPrayerPoints)
		{
			log.debug("Prayer update button fired after prayer point change: previous={} current={}",
					lastPrayerPoints, prayerPoints);
		}
		else
		{
			log.debug("Prayer script fired: scriptId={} currentPrayer={}", scriptId, prayerPoints);
		}
		lastPrayerPoints = prayerPoints;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		logPrayerClick(event);

		if (UNLOCK_REORDERING.equals(event.getMenuOption()))
		{
			prayerReordering = true;
			clientThread.invokeLater(this::rebuildPrayers);
			return;
		}
		else if (LOCK_REORDERING.equals(event.getMenuOption()))
		{
			prayerReordering = false;
			clientThread.invokeLater(this::rebuildPrayers);
			return;
		}

		if (event.getMenuAction() != MenuAction.CC_OP) return;

		Widget widget = event.getWidget();
		if (widget == null || WidgetUtil.componentToInterface(widget.getId()) != InterfaceID.PRAYERBOOK) return;

		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		int prayerId = findPrayerIdFromComponent(prayerbook, widget);
		if (prayerId == -1) return;

		String option = event.getMenuOption();

		if ("Hide".equals(option) || "Unhide".equals(option))
		{
			if (!prayerReordering)
			{
				return;
			}

			event.consume();
			if ("Hide".equals(option))
			{
				setHidden(prayerbook, prayerId, true);
			}
			else
			{
				unhidePrayer(prayerbook, prayerId);
			}
			return;
		}

		// Check voor nieuwe unieke strings
		if (HIDE.equals(option) || UNHIDE.equals(option))
		{
			if (!prayerReordering)
			{
				return;
			}

			event.consume();
			if (HIDE.equals(option))
			{
				setHidden(prayerbook, prayerId, true);
			}
			else
			{
				unhidePrayer(prayerbook, prayerId);
			}
			// Rebuild zorgt voor correcte visuele staat
			return;
		}

		if (DUPLICATE.equals(option))
		{
			List<Slot> slots = getPrayerSlots(prayerbook);
			if (!prayerReordering
					|| isHidden(prayerbook, prayerId)
					|| hasDuplicateForPrayer(slots, prayerId)
					|| !hasAvailableHiddenSlot(prayerbook, slots))
			{
				return;
			}

			log.debug("Duplicating prayer: prayerbook={} prayerId={} widget={} param0={} param1={} id={} option={} target={}",
					prayerbook, prayerId, widget.getId(), event.getParam0(), event.getParam1(), event.getId(),
					event.getMenuOption(), event.getMenuTarget());

			event.consume();
			duplicatePrayer(prayerbook, prayerId);
		}
	}

	private void updateHiddenPrayerStyles()
	{
		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		EnumComposition prayerBookEnum = getPrayerBookEnum(prayerbook);

		for (Slot slot : getPrayerSlots(prayerbook))
		{
			Widget w = getPrayerWidget(prayerBookEnum, slot.getPrayerId());
			if (w != null)
			{
				boolean hidden = isHidden(prayerbook, slot.getPrayerId());
				// FIX: Verberg de widget echt als hij hidden is, anders toont hij altijd
				w.setHidden(hidden);
				w.setOpacity(hidden ? 150 : 0);
				w.setHasListener(!hidden);
			}
		}
	}

	private void logPrayerClick(MenuOptionClicked event)
	{
		Widget widget = event.getWidget();
		if (widget == null || event.getMenuAction() != MenuAction.CC_OP)
		{
			return;
		}

		int groupId = WidgetUtil.componentToInterface(widget.getId());
		if (groupId != InterfaceID.PRAYERBOOK)
		{
			return;
		}

		log.debug("Prayer widget click: widget={} index={} param0={} param1={} id={} itemId={} option={} target={} actions={}",
				widget.getId(), widget.getIndex(), event.getParam0(), event.getParam1(), event.getId(), event.getItemId(),
				event.getMenuOption(), event.getMenuTarget(), Arrays.toString(widget.getActions()));
	}

	@Subscribe
	public void onWidgetDrag(WidgetDrag event)
	{
		if (!prayerReordering || client.getMouseCurrentButton() != 0)
		{
			return;
		}

		Widget draggedWidget = client.getDraggedWidget();
		Widget draggedOnWidget = client.getDraggedOnWidget();
		if (draggedWidget == null)
		{
			return;
		}

		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		Slot draggedDuplicateSlot = findDuplicateSlotForWidget(draggedWidget);
		Slot draggedOnDuplicateSlot = draggedOnWidget == null ? null : findDuplicateSlotForWidget(draggedOnWidget);
		int draggedGroupId = WidgetUtil.componentToInterface(draggedWidget.getId());
		if (draggedDuplicateSlot == null && draggedGroupId != InterfaceID.PRAYERBOOK)
		{
			return;
		}

		if (draggedOnWidget != null)
		{
			int draggedOnGroupId = WidgetUtil.componentToInterface(draggedOnWidget.getId());
			if (draggedOnDuplicateSlot == null && draggedOnGroupId != InterfaceID.PRAYERBOOK)
			{
				return;
			}
		}

		List<Slot> slots = getPrayerSlots(prayerbook);
		Slot fromSlot = draggedDuplicateSlot != null ? draggedDuplicateSlot : findSlotForWidget(prayerbook, draggedWidget);
		Slot toSlot = draggedOnDuplicateSlot != null ? draggedOnDuplicateSlot
				: draggedOnWidget == null ? null : findSlotForWidget(prayerbook, draggedOnWidget);
		if (toSlot == null)
		{
			toSlot = findSlotAtMousePosition(prayerbook, slots, fromSlot);
		}

		if (fromSlot == null || toSlot == null)
		{
			return;
		}

		int fromIdx = slots.indexOf(fromSlot);
		int toIdx = slots.indexOf(toSlot);
		if (fromIdx == -1 || toIdx == -1)
		{
			return;
		}

		int sourceIdx = linkedPairTargetIndex(prayerbook, slots, fromIdx);
		int targetIdx = linkedPairTargetIndex(prayerbook, slots, toIdx);
		if (sourceIdx == -1 || targetIdx == -1)
		{
			return;
		}

		client.setDraggedOnWidget(null);
		if (!swapSlotUnits(prayerbook, slots, sourceIdx, targetIdx))
		{
			rebuildPrayers();
			return;
		}

		setPrayerSlots(prayerbook, slots);
		setPrayerOrderFromSlots(prayerbook, slots);
		rebuildPrayers();
	}

	private int linkedPairTargetIndex(int prayerbook, List<Slot> slots, int slotIdx)
	{
		return isLinkedHiddenSlot(prayerbook, slots, slotIdx) ? slotIdx - 1 : slotIdx;
	}

	private Slot findSlotAtMousePosition(int prayerbook, List<Slot> slots, Slot ignoredSlot)
	{
		Point mouse = client.getMouseCanvasPosition();
		if (mouse == null)
		{
			return null;
		}

		EnumComposition prayerBookEnum = getPrayerBookEnum(prayerbook);
		for (int i = 0; i < slots.size(); ++i)
		{
			Slot slot = slots.get(i);
			if (slot.equals(ignoredSlot))
			{
				continue;
			}

			if (isHiddenSlot(prayerbook, slot) && isLinkedHiddenSlot(prayerbook, slots, i))
			{
				continue;
			}

			if (slot.isDuplicate())
			{
				if (duplicateSlotContainsMouse(slot, mouse))
				{
					return slot;
				}
				continue;
			}

			Widget widget = getPrayerWidget(prayerBookEnum, slot.getPrayerId());
			if (widgetContainsMouse(widget, mouse))
			{
				return slot;
			}
		}

		return null;
	}

	private boolean duplicateSlotContainsMouse(Slot slot, Point mouse)
	{
		for (Map.Entry<Widget, Slot> entry : duplicateWidgets.entrySet())
		{
			if (slot.equals(entry.getValue()) && widgetContainsMouse(entry.getKey(), mouse))
			{
				return true;
			}
		}

		return false;
	}

	private boolean widgetContainsMouse(Widget widget, Point mouse)
	{
		if (widget == null || widget.isSelfHidden())
		{
			return false;
		}

		Rectangle bounds = widget.getBounds();
		return bounds != null && bounds.contains(mouse.getX(), mouse.getY());
	}

	private boolean swapSlotUnits(int prayerbook, List<Slot> slots, int sourceIdx, int targetIdx)
	{
		if (sourceIdx == targetIdx)
		{
			return false;
		}

		int sourceUnitEnd = sourceIdx + targetSlotLength(prayerbook, slots, sourceIdx);
		int targetUnitEnd = targetIdx + targetSlotLength(prayerbook, slots, targetIdx);
		if (sourceIdx < targetUnitEnd && targetIdx < sourceUnitEnd)
		{
			return false;
		}

		List<Slot> sourceUnit = new ArrayList<>(slots.subList(sourceIdx, sourceUnitEnd));
		List<Slot> targetUnit = new ArrayList<>(slots.subList(targetIdx, targetUnitEnd));
		List<Slot> swapped = new ArrayList<>(slots.size());

		if (sourceIdx < targetIdx)
		{
			swapped.addAll(slots.subList(0, sourceIdx));
			swapped.addAll(targetUnit);
			swapped.addAll(slots.subList(sourceUnitEnd, targetIdx));
			swapped.addAll(sourceUnit);
			swapped.addAll(slots.subList(targetUnitEnd, slots.size()));
		}
		else
		{
			swapped.addAll(slots.subList(0, targetIdx));
			swapped.addAll(sourceUnit);
			swapped.addAll(slots.subList(targetUnitEnd, sourceIdx));
			swapped.addAll(targetUnit);
			swapped.addAll(slots.subList(sourceUnitEnd, slots.size()));
		}

		slots.clear();
		slots.addAll(swapped);
		return true;
	}

	private int targetSlotLength(int prayerbook, List<Slot> slots, int targetIdx)
	{
		Slot targetSlot = slots.get(targetIdx);
		return targetSlot.isDuplicate() && findLinkedHiddenSlotIndex(prayerbook, slots, targetIdx) != -1 ? 2 : 1;
	}

	private void setPrayerOrderFromSlots(int prayerbook, List<Slot> slots)
	{
		String order = slots.stream()
				.filter(slot -> !slot.isDuplicate())
				.map(slot -> Integer.toString(slot.getPrayerId()))
				.collect(Collectors.joining(","));
		configManager.setConfiguration(PRAYER_CONFIG_GROUP, "prayer_order_book_" + prayerbook, order);
	}

	private void duplicatePrayer(int prayerbook, int prayerId)
	{
		if (isHidden(prayerbook, prayerId))
		{
			return;
		}

		List<Slot> slots = getPrayerSlots(prayerbook);
		if (hasDuplicateForPrayer(slots, prayerId))
		{
			return;
		}

		int hiddenSlotIdx = findAvailableHiddenSlotIndex(prayerbook, slots);
		if (hiddenSlotIdx == -1)
		{
			return;
		}

		Slot duplicate = new Slot(prayerId, true, nextDuplicateId(slots));
		slots.add(hiddenSlotIdx, duplicate);

		setPrayerSlots(prayerbook, slots);
		rebuildPrayers();
	}

	private int findAvailableHiddenSlotIndex(int prayerbook, List<Slot> slots)
	{
		for (int i = 0; i < slots.size(); ++i)
		{
			if (isHiddenSlot(prayerbook, slots.get(i)) && !isLinkedHiddenSlot(prayerbook, slots, i))
			{
				return i;
			}
		}

		return -1;
	}

	private boolean hasAvailableHiddenSlot(int prayerbook, List<Slot> slots)
	{
		return findAvailableHiddenSlotIndex(prayerbook, slots) != -1;
	}

	private int findHiddenSlotIndex(int prayerbook, List<Slot> slots, int prayerId)
	{
		for (int i = 0; i < slots.size(); ++i)
		{
			Slot slot = slots.get(i);
			if (isHiddenSlot(prayerbook, slot) && slot.getPrayerId() == prayerId)
			{
				return i;
			}
		}

		return -1;
	}

	private int findOriginalSlotIndex(List<Slot> slots, int prayerId)
	{
		for (int i = 0; i < slots.size(); ++i)
		{
			Slot slot = slots.get(i);
			if (!slot.isDuplicate() && slot.getPrayerId() == prayerId)
			{
				return i;
			}
		}

		return -1;
	}

	private boolean containsOriginalSlot(List<Slot> slots, int prayerId)
	{
		return findOriginalSlotIndex(slots, prayerId) != -1;
	}

	private int nextDuplicateId(List<Slot> slots)
	{
		return slots.stream()
				.mapToInt(Slot::getDuplicateId)
				.max()
				.orElse(0) + 1;
	}

	private List<Slot> getPrayerSlots(int prayerbook)
	{
		EnumComposition prayerEnum = getPrayerBookEnum(prayerbook);
		String config = configManager.getConfiguration(DuplicatePrayersConfig.GROUP, "prayer_slots_book_" + prayerbook);
		if (config == null || config.isBlank())
		{
			return defaultPrayerSlots(prayerEnum);
		}

		List<Slot> slots = Arrays.stream(config.split(","))
				.map(Slot::parse)
				.collect(Collectors.toCollection(ArrayList::new));

		return normalizePrayerSlots(prayerbook, prayerEnum, slots);
	}

	private void setPrayerSlots(int prayerbook, List<Slot> slots)
	{
		String config = slots.stream()
				.map(Slot::serialize)
				.collect(Collectors.joining(","));
		configManager.setConfiguration(DuplicatePrayersConfig.GROUP, "prayer_slots_book_" + prayerbook, config);
	}

	private List<Slot> defaultPrayerSlots(EnumComposition prayerEnum)
	{
		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		return Arrays.stream(getPrayerOrder(prayerbook, prayerEnum))
				.boxed()
				.map(id -> new Slot(id, false, 0))
				.collect(Collectors.toCollection(ArrayList::new));
	}

	private List<Slot> normalizePrayerSlots(int prayerbook, EnumComposition prayerEnum, List<Slot> slots) {
		List<Slot> normalized = new ArrayList<>();
		Set<Integer> addedOriginals = new HashSet<>();
		Set<Integer> addedDuplicates = new HashSet<>();

		for (Slot slot : slots)
		{
			if (!containsPrayerKey(prayerEnum, slot.getPrayerId()))
			{
				continue;
			}

			if (slot.isDuplicate())
			{
				if (!isHidden(prayerbook, slot.getPrayerId())
						&& !addedDuplicates.contains(slot.getPrayerId()))
				{
					normalized.add(slot);
					addedDuplicates.add(slot.getPrayerId());
				}
				continue;
			}

			if (addedOriginals.add(slot.getPrayerId()))
			{
				normalized.add(slot);
			}
		}

		for (int prayerId : getPrayerOrder(prayerbook, prayerEnum))
		{
			if (addedOriginals.add(prayerId))
			{
				normalized.add(new Slot(prayerId, false, 0));
			}
		}

		normalized = normalizeLinkedDuplicateSlots(prayerbook, normalized);
		normalized = trimOverflowDuplicates(prayerbook, normalized);

		if (!normalized.equals(slots)) {
			setPrayerSlots(prayerbook, normalized);
		}

		return normalized;
	}

	private List<Slot> normalizeLinkedDuplicateSlots(int prayerbook, List<Slot> slots)
	{
		List<Slot> normalized = new ArrayList<>(slots);
		for (int i = 0; i < normalized.size(); ++i)
		{
			Slot slot = normalized.get(i);
			if (!slot.isDuplicate())
			{
				continue;
			}

			int linkedHiddenIdx = findLinkedHiddenSlotIndex(prayerbook, normalized, i);
			int availableHiddenIdx = findAvailableHiddenSlotIndex(prayerbook, normalized);
			if (linkedHiddenIdx != -1 && (availableHiddenIdx == -1 || availableHiddenIdx > i))
			{
				continue;
			}

			if (availableHiddenIdx == -1)
			{
				normalized.remove(i--);
				continue;
			}

			Slot duplicateSlot = normalized.remove(i);
			if (i < availableHiddenIdx)
			{
				--availableHiddenIdx;
			}

			normalized.add(availableHiddenIdx, duplicateSlot);
			i = availableHiddenIdx;
		}

		return normalized;
	}

	private int[] getPrayerOrder(int prayerbook, EnumComposition prayerEnum)
	{
		int[] defaultOrder = defaultPrayerOrder(prayerEnum);
		String config = configManager.getConfiguration(PRAYER_CONFIG_GROUP, "prayer_order_book_" + prayerbook);
		if (config != null && !config.isBlank())
		{
			int[] configuredOrder = Arrays.stream(config.split(","))
					.mapToInt(Integer::parseInt)
					.filter(prayerId -> containsPrayerKey(prayerEnum, prayerId))
					.toArray();

			List<Integer> order = Arrays.stream(configuredOrder)
					.boxed()
					.collect(Collectors.toCollection(ArrayList::new));
			for (int prayerId : defaultOrder)
			{
				if (!order.contains(prayerId))
				{
					order.add(prayerId);
				}
			}

			return order.stream()
					.mapToInt(i -> i)
					.toArray();
		}

		return defaultOrder;
	}

	private int[] defaultPrayerOrder(EnumComposition prayerEnum)
	{
		return Arrays.stream(prayerEnum.getKeys())
				.boxed()
				.sorted(Comparator.comparing(id ->
				{
					int prayerObjId = prayerEnum.getIntValue(id);
					ItemComposition prayerObj = client.getItemDefinition(prayerObjId);
					return prayerObj.getIntValue(ParamID.OC_PRAYER_LEVEL);
				}))
				.mapToInt(i -> i)
				.toArray();
	}

	private List<Slot> trimOverflowDuplicates(int prayerbook, List<Slot> slots)
	{
		List<Slot> trimmed = new ArrayList<>();
		int visible = 0;
		for (int i = 0; i < slots.size(); ++i)
		{
			Slot slot = slots.get(i);
			if (isHiddenSlot(prayerbook, slot))
			{
				trimmed.add(slot);
				continue;
			}

			if (!slot.isDuplicate())
			{
				trimmed.add(slot);
				++visible;
			}
			else if (findLinkedHiddenSlotIndex(prayerbook, slots, i) != -1
					&& !hasDuplicateForPrayer(trimmed, slot.getPrayerId())
					&& visible < MAX_VISIBLE_PRAYERS)
			{
				trimmed.add(slot);
				++visible;
			}
		}
		return trimmed;
	}

	private int visibleSlotCount(int prayerbook, List<Slot> slots)
	{
		int visible = 0;
		for (Slot slot : slots)
		{
			if (isHiddenSlot(prayerbook, slot))
			{
				continue;
			}
			++visible;
		}
		return visible;
	}

	private boolean isHidden(int prayerbook, int prayerId)
	{
		Boolean hidden = configManager.getConfiguration(PRAYER_CONFIG_GROUP,
				"prayer_hidden_book_" + prayerbook + "_" + prayerId, boolean.class);
		return hidden == Boolean.TRUE;
	}

	private void setHidden(int prayerbook, int prayerId, boolean hidden)
	{
		setHiddenConfig(prayerbook, prayerId, hidden);
		updatePrayerHiddenStyle(prayerbook, prayerId, hidden);
		rebuildPrayers();
	}

	private void setHiddenConfig(int prayerbook, int prayerId, boolean hidden)
	{
		String key = "prayer_hidden_book_" + prayerbook + "_" + prayerId;
		if (hidden)
		{
			configManager.setConfiguration(PRAYER_CONFIG_GROUP, key, true);
		}
		else
		{
			configManager.unsetConfiguration(PRAYER_CONFIG_GROUP, key);
		}
	}

	private void openHiddenPrayerManager()
	{
		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		EnumComposition prayerBookEnum = getPrayerBookEnum(prayerbook);
		ChatboxTextMenuInput menu = chatboxPanelManager.openTextMenuInput("Manage hidden prayers");
		int hiddenCount = 0;

		for (Slot slot : defaultPrayerSlots(prayerBookEnum))
		{
			int prayerId = slot.getPrayerId();
			if (!isHidden(prayerbook, prayerId))
			{
				continue;
			}

			String prayerName = getPrayerName(prayerBookEnum, prayerId);
			menu.option("Unhide " + prayerName, () -> unhidePrayer(prayerbook, prayerId));
			++hiddenCount;
		}

		if (hiddenCount == 0)
		{
			menu.option("No hidden prayers", () -> {});
		}

		menu.build();
	}

	private void unhidePrayer(int prayerbook, int prayerId)
	{
		setHiddenConfig(prayerbook, prayerId, false);
		updatePrayerHiddenStyle(prayerbook, prayerId, false);
		List<Slot> slots = getPrayerSlots(prayerbook);
		setPrayerSlots(prayerbook, slots);
		rebuildPrayers();
	}

	private String getPrayerName(EnumComposition prayerBookEnum, int prayerId)
	{
		int prayerObjId = prayerBookEnum.getIntValue(prayerId);
		return client.getItemDefinition(prayerObjId).getName();
	}

	private boolean containsPrayerKey(EnumComposition prayerEnum, int prayerId)
	{
		for (int key : prayerEnum.getKeys())
		{
			if (key == prayerId)
			{
				return true;
			}
		}
		return false;
	}

	private EnumComposition getPrayerBookEnum(int prayerbook)
	{
		if (prayerbook == 1)
		{
			return client.getEnum(EnumID.PRAYERS_RUINOUS);
		}

		boolean deadeye = client.getVarbitValue(VarbitID.PRAYER_DEADEYE_UNLOCKED) != 0;
		boolean vigour = client.getVarbitValue(VarbitID.PRAYER_MYSTIC_VIGOUR_UNLOCKED) != 0;

		if (deadeye && vigour)
		{
			return client.getEnum(EnumID.PRAYERS_NORMAL_DEADEYE_MYSTIC_VIGOUR);
		}
		else if (deadeye)
		{
			return client.getEnum(EnumID.PRAYERS_NORMAL_DEADEYE);
		}
		else if (vigour)
		{
			return client.getEnum(EnumID.PRAYERS_NORMAL_MYSTIC_VIGOUR);
		}
		else
		{
			return client.getEnum(EnumID.PRAYERS_NORMAL);
		}
	}

	private int findPrayerIdFromComponent(int prayerbook, Widget component)
	{
		EnumComposition prayers = getPrayerBookEnum(prayerbook);
		int[] keys = prayers.getKeys();
		int[] vals = prayers.getIntVals();
		for (int i = 0; i < keys.length; ++i)
		{
			ItemComposition prayer = client.getItemDefinition(vals[i]);
			if (prayer.getIntValue(ParamID.OC_PRAYER_COMPONENT) == component.getId())
			{
				return keys[i];
			}
		}
		return -1;
	}

	private Slot findSlotForWidget(int prayerbook, Widget widget)
	{
		Slot duplicateSlot = findDuplicateSlotForWidget(widget);
		if (duplicateSlot != null)
		{
			return duplicateSlot;
		}

		int prayerId = findPrayerIdFromComponent(prayerbook, widget);
		return prayerId == -1 ? null : new Slot(prayerId, false, 0);
	}

	private Slot findDuplicateSlotForWidget(Widget widget)
	{
		Widget current = widget;
		while (current != null)
		{
			Slot duplicateSlot = duplicateWidgets.get(current);
			if (duplicateSlot != null)
			{
				return duplicateSlot;
			}

			current = current.getParent();
		}

		return null;
	}

	private void redrawPrayers()
	{
		Widget w = client.getWidget(InterfaceID.PRAYERBOOK, 0);
		if (w != null)
		{
			Object[] listener = w.getOnVarTransmitListener();
			if (listener != null)
			{
				client.runScript(listener);
			}
		}
	}

	private void resetOriginalPrayerPositions()
	{
		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		EnumComposition prayerBookEnum = getPrayerBookEnum(prayerbook);

		if (prayerBookEnum == null)
		{
			return;
		}

		for (int prayerId : prayerBookEnum.getKeys())
		{
			Widget widget = getPrayerWidget(prayerBookEnum, prayerId);
			if (widget != null)
			{
				widget.setHidden(true); // Verbergt alle originele prayers voor de herbouw
			}
		}
	}

	private void rebuildPrayers()
	{
		if (!isPrayerBookOpen()) return;

		resetOriginalPrayerPositions();
		clearDuplicateWidgets();

		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		EnumComposition prayerBookEnum = getPrayerBookEnum(prayerbook);

		List<Slot> slots = getPrayerSlots(prayerbook);

		Set<Integer> renderedOriginals = new HashSet<>();
		List<DuplicateRender> duplicateRenders = new ArrayList<>();
		int index = 0;

		for (int slotIndex = 0; slotIndex < slots.size(); ++slotIndex)
		{
			Slot slot = slots.get(slotIndex);
			boolean hidden = isHiddenSlot(prayerbook, slot);
			if (hidden)
			{
				boolean coveredByLinkedDuplicate = isLinkedHiddenSlot(prayerbook, slots, slotIndex);

				if (index >= MAX_VISIBLE_PRAYERS)
				{
					break;
				}

				Widget hiddenWidget = getPrayerWidget(prayerBookEnum, slot.getPrayerId());
				if (hiddenWidget != null)
				{
					if (prayerReordering)
					{
						int hiddenIndex = coveredByLinkedDuplicate ? Math.max(0, index - 1) : index;
						int x = (hiddenIndex % PRAYER_COLUMN_COUNT) * PRAYER_X_OFFSET;
						int y = (hiddenIndex / PRAYER_COLUMN_COUNT) * PRAYER_Y_OFFSET;

						hiddenWidget.setHidden(false);
						hiddenWidget.setOpacity(150);
						setPrayerIconOpacity(hiddenWidget, true);
						hiddenWidget.setPos(x, y);
						hiddenWidget.setAction(0, null);
						hiddenWidget.setAction(DUPLICATE_OP, null);
						hiddenWidget.setAction(HIDE_OP, coveredByLinkedDuplicate ? null : "Unhide");
						hiddenWidget.setClickMask(coveredByLinkedDuplicate ? 0 : (hiddenWidget.getClickMask() & ~DRAG) | DRAG_ON);
						hiddenWidget.setHasListener(true);
						hiddenWidget.revalidate();
					}
					else
					{
						setPrayerIconOpacity(hiddenWidget, false);
						hiddenWidget.setHidden(true);
					}
				}

				if (!coveredByLinkedDuplicate)
				{
					++index;
				}
				continue;
			}

			// Originele prayer check
			if (!slot.isDuplicate())
			{
				if (renderedOriginals.contains(slot.getPrayerId())) continue;
				renderedOriginals.add(slot.getPrayerId());
			}

			Widget original = getPrayerWidget(prayerBookEnum, slot.getPrayerId());
			if (original == null) continue;

			if (index >= MAX_VISIBLE_PRAYERS)
			{
				break;
			}

			int x = (index % PRAYER_COLUMN_COUNT) * PRAYER_X_OFFSET;
			int y = (index / PRAYER_COLUMN_COUNT) * PRAYER_Y_OFFSET;

			if (slot.isDuplicate())
			{
				duplicateRenders.add(new DuplicateRender(original, slot, x, y));
			}
			else
			{
				// Altijd tonen, maar verander de opacity/transparantie als hij verborgen is
				original.setHidden(false);
				original.setOpacity(hidden ? 150 : 0); // 150 is semi-transparant, 0 is volledig zichtbaar
				setPrayerIconOpacity(original, false);
				original.setPos(x, y);

				// Verberg de primaire "Activate"-actie voor verborgen prayers zodat ze niet
				// per ongeluk geactiveerd kunnen worden. De widget blijft klikbaar zodat de
				// native Hide/Unhide-optie en onze Duplicate-optie beschikbaar blijven.
				original.setAction(0, hidden ? null : getPrimaryAction(original, slot.getPrayerId()));
				original.setAction(DUPLICATE_OP, prayerReordering
						&& hasAvailableHiddenSlot(prayerbook, slots)
						&& !hasDuplicateForPrayer(slots, slot.getPrayerId()) ? DUPLICATE : null);
				original.setAction(HIDE_OP, prayerReordering ? "Hide" : null);
				original.setHasListener(true);
				original.revalidate();
			}
			index++;
		}

		for (DuplicateRender duplicateRender : duplicateRenders)
		{
			Widget duplicate = createDuplicateWidget(duplicateRender.getOriginal(), duplicateRender.getSlot(),
					duplicateRender.getX(), duplicateRender.getY(), true);
			if (duplicate != null)
			{
				registerDuplicateWidgetTree(duplicate, duplicateRender.getSlot());
			}
		}
	}

	private Widget getPrayerWidget(EnumComposition prayerBookEnum, int prayerId)
	{
		int prayerObjId = prayerBookEnum.getIntValue(prayerId);
		ItemComposition prayerObj = client.getItemDefinition(prayerObjId);
		return client.getWidget(prayerObj.getIntValue(ParamID.OC_PRAYER_COMPONENT));
	}

	private void clearPrayerReorderActions(EnumComposition prayerBookEnum)
	{
		for (int prayerId : prayerBookEnum.getKeys())
		{
			Widget prayerWidget = getPrayerWidget(prayerBookEnum, prayerId);
			if (prayerWidget == null)
			{
				continue;
			}

			prayerWidget.setAction(HIDE_OP, null);
		}
	}

	private String normalizePrayerName(String name)
	{
		return name.replaceAll("<[^>]*>", "")
				.replaceAll("[^A-Za-z0-9]+", " ")
				.trim()
				.toLowerCase();
	}

	private Widget createDuplicateWidget(Widget original, Slot slot, int x, int y, boolean canDuplicate)
	{
		Widget duplicate = getDuplicateRoot(original).createChild(-1, WidgetType.LAYER);

		duplicate.setName(original.getName() + " (duplicate " + slot.getDuplicateId() + ")");
		duplicate.setSize(original.getWidth(), original.getHeight(), WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE);
		duplicate.setPos(x, y, WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP);

		int clickMask = original.getClickMask();
		if (prayerReordering)
		{
			clickMask |= (DRAG | DRAG_ON);
		}
		else
		{
			clickMask &= ~(DRAG | DRAG_ON);
		}
		duplicate.setClickMask(clickMask);

		duplicate.setAction(0, getPrimaryAction(original, slot.getPrayerId()));
		duplicate.setAction(DUPLICATE_OP, null);
		duplicate.setAction(REMOVE_DUPLICATE_OP, prayerReordering ? REMOVE_DUPLICATE : null);
		duplicate.setHasListener(true);
		duplicate.setOnOpListener((JavaScriptCallback) event ->
		{
			int op = event.getOp();
			log.debug("Duplicate prayer op: prayerId={} duplicateId={} op={} duplicateWidget={} originalWidget={}",
					slot.getPrayerId(), slot.getDuplicateId(), op, duplicate.getId(), original.getId());

			if (op == ACTIVATE_OP)
			{
				activateOriginalPrayer(original, slot.getPrayerId());
			}
			else if (prayerReordering && op == REMOVE_DUPLICATE_OP + 1)
			{
				removeDuplicate(client.getVarbitValue(VarbitID.PRAYERBOOK), slot);
			}
		});

		copyChildren(original, duplicate);
		configureDuplicateWidgetTree(duplicate, slot, clickMask);
		syncDuplicateActiveState(duplicate, slot);
		duplicate.revalidate();
		return duplicate;
	}

	private void configureDuplicateWidgetTree(Widget widget, Slot slot, int clickMask)
	{
		widget.setClickMask(clickMask);
		widget.setHasListener(true);
		widget.setAction(0, getPrimaryActionForSlot(slot));
		widget.setAction(DUPLICATE_OP, null);
		widget.setAction(REMOVE_DUPLICATE_OP, prayerReordering ? REMOVE_DUPLICATE : null);
		widget.setOnOpListener((JavaScriptCallback) event ->
		{
			int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
			int op = event.getOp();
			if (op == ACTIVATE_OP)
			{
				EnumComposition prayerBookEnum = getPrayerBookEnum(prayerbook);
				Widget original = getPrayerWidget(prayerBookEnum, slot.getPrayerId());
				if (original != null)
				{
					activateOriginalPrayer(original, slot.getPrayerId());
				}
			}
			else if (prayerReordering && op == REMOVE_DUPLICATE_OP + 1)
			{
				removeDuplicate(prayerbook, slot);
			}
		});

		Widget[] dynamicChildren = widget.getDynamicChildren();
		if (dynamicChildren != null)
		{
			for (Widget child : dynamicChildren)
			{
				if (child != null)
				{
					configureDuplicateWidgetTree(child, slot, clickMask);
				}
			}
		}

		Widget[] staticChildren = widget.getStaticChildren();
		if (staticChildren != null)
		{
			for (Widget child : staticChildren)
			{
				if (child != null)
				{
					configureDuplicateWidgetTree(child, slot, clickMask);
				}
			}
		}
	}

	private void registerDuplicateWidgetTree(Widget widget, Slot slot)
	{
		duplicateWidgets.put(widget, slot);

		Widget[] dynamicChildren = widget.getDynamicChildren();
		if (dynamicChildren != null)
		{
			for (Widget child : dynamicChildren)
			{
				if (child != null)
				{
					registerDuplicateWidgetTree(child, slot);
				}
			}
		}

		Widget[] staticChildren = widget.getStaticChildren();
		if (staticChildren != null)
		{
			for (Widget child : staticChildren)
			{
				if (child != null)
				{
					registerDuplicateWidgetTree(child, slot);
				}
			}
		}
	}

	private Widget getDuplicateRoot(Widget original)
	{
		Widget parent = original.getParent();
		if (parent == null)
		{
			parent = client.getWidget(InterfaceID.Prayerbook.UNIVERSE);
			log.debug("Prayer {} had no parent; duplicate root was attached to Prayerbook.UNIVERSE", original.getId());
		}

		Widget duplicateRoot = duplicateRoots.get(parent);
		if (duplicateRoot == null || duplicateRoot.getParent() != parent)
		{
			duplicateRoot = parent.createChild(-1, WidgetType.LAYER);
			duplicateRoot.setSize(0, 0, WidgetSizeMode.MINUS, WidgetSizeMode.MINUS);
			duplicateRoot.setPos(0, 0, WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP);
			duplicateRoot.setClickMask(0);
			duplicateRoot.clearActions();
			duplicateRoot.setHasListener(false);
			duplicateRoots.put(parent, duplicateRoot);
		}

		duplicateRoot.setHidden(false);
		duplicateRoot.revalidate();
		return duplicateRoot;
	}

	private void syncDuplicateActiveState(Widget duplicate, Slot slot)
	{
		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		EnumComposition prayerBookEnum = getPrayerBookEnum(prayerbook);
		Widget original = getPrayerWidget(prayerBookEnum, slot.getPrayerId());

		if (original == null)
		{
			return;
		}

		// 1. Synchroniseer de hover tekst (Activate / Deactivate) exact met het origineel
		String primaryAction = getPrimaryAction(original, slot.getPrayerId());
		duplicate.setAction(0, primaryAction);

		// 2. Synchroniseer de visuele laag (sprites/hidden states van de iconen en achtergronden)
		Widget[] origChildren = original.getDynamicChildren();
		Widget[] dupChildren = duplicate.getDynamicChildren();

		if (origChildren != null && dupChildren != null)
		{
			for (int i = 0; i < Math.min(origChildren.length, dupChildren.length); i++)
			{
				Widget origChild = origChildren[i];
				Widget dupChild = dupChildren[i];

				if (origChild != null && dupChild != null)
				{
					dupChild.setHidden(origChild.isSelfHidden());
					dupChild.setSpriteId(origChild.getSpriteId());
				}
			}
		}
	}

	private void updatePrayerHiddenStyle(int prayerbook, int prayerId, boolean hidden)
	{
		EnumComposition prayerBookEnum = getPrayerBookEnum(prayerbook);
		Widget prayerWidget = getPrayerWidget(prayerBookEnum, prayerId);
		if (prayerWidget != null)
		{
			setPrayerIconOpacity(prayerWidget, hidden && prayerReordering);
		}
	}

	private void setPrayerIconOpacity(Widget prayerWidget, boolean hidden)
	{
		Widget prayerIcon = prayerWidget.getChild(1);
		if (prayerIcon != null)
		{
			prayerIcon.setOpacity(hidden ? 200 : 0);
		}
	}

	private Prayer getPrayer(int prayerId)
	{
		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		EnumComposition prayerBookEnum = getPrayerBookEnum(prayerbook);
		int prayerObjId = prayerBookEnum.getIntValue(prayerId);
		if (prayerObjId == -1)
		{
			return null;
		}

		String prayerName = normalizePrayerName(client.getItemDefinition(prayerObjId).getName());
		for (Prayer prayer : Prayer.values())
		{
			String enumName = normalizePrayerName(prayer.name().replace('_', ' '));
			if (enumName.equals(prayerName))
			{
				return prayer;
			}

			if (enumName.startsWith("rp ") && enumName.substring(3).equals(prayerName))
			{
				return prayer;
			}
		}

		return null;
	}

	private String getPrimaryAction(Widget original, int prayerId)
	{
		String[] actions = original.getActions();
		return actions != null && actions.length > 0 && actions[0] != null ? actions[0] : "Activate";
	}

	private String getPrimaryActionForSlot(Slot slot)
	{
		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		EnumComposition prayerBookEnum = getPrayerBookEnum(prayerbook);
		Widget original = getPrayerWidget(prayerBookEnum, slot.getPrayerId());
		return original == null ? "Activate" : getPrimaryAction(original, slot.getPrayerId());
	}

	private void activateOriginalPrayer(Widget original, int prayerId)
	{
		String option = getPrimaryAction(original, prayerId);
		String target = original.getName();
		int param0 = original.getIndex();
		int param1 = original.getId();

		log.debug("Activating original prayer from duplicate: widget={} index={} param0={} param1={} op={} option={} target={} actions={} hasListener={}",
				original.getId(), original.getIndex(), param0, param1, ACTIVATE_OP, option, target,
				Arrays.toString(original.getActions()), original.hasListener());

		client.menuAction(param0, param1, MenuAction.CC_OP, ACTIVATE_OP, -1, option, target);
	}

	private void removeDuplicate(int prayerbook, Slot slot)
	{
		List<Slot> slots = getPrayerSlots(prayerbook);
		if (slots.remove(slot))
		{
			setPrayerSlots(prayerbook, slots);
			rebuildPrayers();
		}
	}

	private void copyChildren(Widget source, Widget target)
	{
		Widget[] children = source.getStaticChildren();
		if (children == null || children.length == 0)
		{
			children = source.getDynamicChildren();
		}

		if (children == null)
		{
			return;
		}

		for (Widget child : children)
		{
			if (child == null)
			{
				continue;
			}

			Widget copy = target.createChild(-1, child.getType());
			copyWidgetVisuals(child, copy);
			copyChildren(child, copy);
			copy.revalidate();
		}
	}

	private void copyWidgetVisuals(Widget source, Widget target)
	{
		target.setHidden(source.isSelfHidden());
		target.clearActions();
		target.setClickMask(0);
		target.setHasListener(false);
		target.setName(source.getName());
		target.setText(source.getText());
		target.setTextColor(source.getTextColor());
		target.setTextShadowed(source.getTextShadowed());
		target.setFontId(source.getFontId());
		target.setXTextAlignment(source.getXTextAlignment());
		target.setYTextAlignment(source.getYTextAlignment());
		target.setLineHeight(source.getLineHeight());
		target.setOpacity(source.getOpacity());
		target.setFilled(source.isFilled());
		target.setSpriteId(source.getSpriteId());
		target.setSpriteTiling(source.getSpriteTiling());
		target.setModelType(source.getModelType());
		target.setModelId(source.getModelId());
		target.setAnimationId(source.getAnimationId());
		target.setRotationX(source.getRotationX());
		target.setRotationY(source.getRotationY());
		target.setRotationZ(source.getRotationZ());
		target.setModelZoom(source.getModelZoom());
		target.setItemId(source.getItemId());
		target.setItemQuantity(source.getItemQuantity());
		target.setItemQuantityMode(source.getItemQuantityMode());
		target.setBorderType(source.getBorderType());
		target.setFlippedHorizontally(source.isFlippedHorizontally());
		target.setFlippedVertically(source.isFlippedVertically());
		target.setSize(source.getWidth(), source.getHeight(), WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE);
		target.setPos(source.getRelativeX(), source.getRelativeY(), WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP);
	}

	private void clearDuplicateWidgets()
	{
		for (Widget duplicate : duplicateWidgets.keySet())
		{
			disableWidgetTree(duplicate);
		}
		duplicateWidgets.clear();

		for (Widget duplicateRoot : duplicateRoots.values())
		{
			if (duplicateRoot != null)
			{
				disableWidgetTree(duplicateRoot);
				duplicateRoot.deleteAllChildren();
				duplicateRoot.setHidden(true);
				duplicateRoot.revalidate();
			}
		}
	}

	private void disableWidgetTree(Widget widget)
	{
		widget.clearActions();
		widget.setClickMask(0);
		widget.setHasListener(false);
		widget.setHidden(true);

		Widget[] children = widget.getDynamicChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				if (child != null)
				{
					disableWidgetTree(child);
				}
			}
		}

		children = widget.getStaticChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				if (child != null)
				{
					disableWidgetTree(child);
				}
			}
		}
	}

	private boolean isPrayerBookOpen()
	{
		return client.getWidget(InterfaceID.PRAYERBOOK, 0) != null
				&& client.getWidget(InterfaceID.Prayerbook.UNIVERSE) != null;
	}

	private boolean isHiddenSlot(int prayerbook, Slot slot)
	{
		return !slot.isDuplicate() && isHidden(prayerbook, slot.getPrayerId());
	}

	private boolean isLinkedHiddenSlot(int prayerbook, List<Slot> slots, int hiddenIdx)
	{
		return hiddenIdx > 0
				&& isHiddenSlot(prayerbook, slots.get(hiddenIdx))
				&& slots.get(hiddenIdx - 1).isDuplicate();
	}

	private int findLinkedHiddenSlotIndex(int prayerbook, List<Slot> slots, int duplicateIdx)
	{
		int hiddenIdx = duplicateIdx + 1;
		if (hiddenIdx < slots.size() && isHiddenSlot(prayerbook, slots.get(hiddenIdx)))
		{
			return hiddenIdx;
		}

		return -1;
	}

	private int countVisibleOriginals(int prayerbook, List<Slot> slots)
	{
		int count = 0;
		for (Slot slot : slots)
		{
			if (!slot.isDuplicate() && !isHidden(prayerbook, slot.getPrayerId()))
			{
				++count;
			}
		}
		return count;
	}

	private int countDuplicates(List<Slot> slots)
	{
		int count = 0;
		for (Slot slot : slots)
		{
			if (slot.isDuplicate())
			{
				++count;
			}
		}
		return count;
	}

	private boolean hasDuplicateForPrayer(List<Slot> slots, int prayerId)
	{
		for (Slot slot : slots)
		{
			if (slot.isDuplicate() && slot.getPrayerId() == prayerId)
			{
				return true;
			}
		}
		return false;
	}

	@Provides
	DuplicatePrayersConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DuplicatePrayersConfig.class);
	}

	@Value
	private static class DuplicateRender
	{
		Widget original;
		Slot slot;
		int x;
		int y;
	}

	@Value
	private static class Slot
	{
		int prayerId;
		boolean duplicate;
		int duplicateId;

		private String serialize()
		{
			return prayerId + ":" + (duplicate ? duplicateId : 0);
		}

		private static Slot parse(String serialized)
		{
			String[] parts = serialized.split(":", 2);
			int prayerId = Integer.parseInt(parts[0]);
			int duplicateId = parts.length == 2 ? Integer.parseInt(parts[1]) : 0;
			return new Slot(prayerId, duplicateId != 0, duplicateId);
		}
	}
	private boolean isPrayerHidden(int prayerbook, int prayerId)
	{
		// Gebruik exact dezelfde logica als in je setHidden() methode
		Boolean hidden = configManager.getConfiguration(PRAYER_CONFIG_GROUP,
				"prayer_hidden_book_" + prayerbook + "_" + prayerId, boolean.class);
		return hidden == Boolean.TRUE;
	}
}
