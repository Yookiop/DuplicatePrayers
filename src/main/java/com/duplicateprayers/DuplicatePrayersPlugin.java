package com.duplicateprayers;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.apache.commons.lang3.ArrayUtils;

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
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private MenuManager menuManager;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	private final Map<Widget, Slot> duplicateWidgets = new HashMap<>();
	private final Map<Widget, Widget> duplicateRoots = new HashMap<>();
	private boolean prayerReordering;
	private int lastPrayerPoints = -1;

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

		clientThread.invokeLater(this::redrawPrayers);
	}

	private void addPrayerTabMenus()
	{
		menuManager.addManagedCustomMenu(FIXED_MANAGE_HIDDEN_PRAYERS, e -> openHiddenPrayerManager());
		menuManager.addManagedCustomMenu(RESIZABLE_MANAGE_HIDDEN_PRAYERS, e -> openHiddenPrayerManager());
		menuManager.addManagedCustomMenu(RESIZABLE_BOTTOM_LINE_MANAGE_HIDDEN_PRAYERS, e -> openHiddenPrayerManager());
	}

	private void removePrayerTabMenus()
	{
		menuManager.removeManagedCustomMenu(FIXED_MANAGE_HIDDEN_PRAYERS);
		menuManager.removeManagedCustomMenu(RESIZABLE_MANAGE_HIDDEN_PRAYERS);
		menuManager.removeManagedCustomMenu(RESIZABLE_BOTTOM_LINE_MANAGE_HIDDEN_PRAYERS);
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

			syncDuplicateActiveState(duplicate, slot.getPrayerId());
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.PRAYER_REDRAW)
		{
			clientThread.invokeLater(() ->
			{
				if (isPrayerBookOpen() && duplicateWidgets.isEmpty())
				{
					rebuildPrayers();
				}
			});
		}

		if (event.getScriptId() == ScriptID.PRAYER_UPDATEBUTTON)
		{
			syncAllDuplicatePrayerStates();
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

		if (event.getMenuAction() != MenuAction.CC_OP || !DUPLICATE.equals(event.getMenuOption()))
		{
			return;
		}

		Widget widget = event.getWidget();
		if (widget == null || WidgetUtil.componentToInterface(widget.getId()) != InterfaceID.PRAYERBOOK)
		{
			return;
		}

		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		int prayerId = findPrayerIdFromComponent(prayerbook, widget);
		if (prayerId == -1)
		{
			return;
		}

		log.debug("Duplicating prayer: prayerbook={} prayerId={} widget={} param0={} param1={} id={} option={} target={}",
			prayerbook, prayerId, widget.getId(), event.getParam0(), event.getParam1(), event.getId(),
			event.getMenuOption(), event.getMenuTarget());

		event.consume();
		duplicatePrayer(prayerbook, prayerId);
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
		if (client.getMouseCurrentButton() != 0)
		{
			return;
		}

		Widget draggedWidget = client.getDraggedWidget();
		Widget draggedOnWidget = client.getDraggedOnWidget();
		if (draggedWidget == null || draggedOnWidget == null)
		{
			return;
		}

		int draggedGroupId = WidgetUtil.componentToInterface(draggedWidget.getId());
		int draggedOnGroupId = WidgetUtil.componentToInterface(draggedOnWidget.getId());
		if (draggedGroupId != InterfaceID.PRAYERBOOK || draggedOnGroupId != InterfaceID.PRAYERBOOK)
		{
			return;
		}

		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		List<Slot> slots = getPrayerSlots(prayerbook);
		Slot fromSlot = findSlotForWidget(prayerbook, draggedWidget);
		Slot toSlot = findSlotForWidget(prayerbook, draggedOnWidget);
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

		client.setDraggedOnWidget(null);

		slots.set(fromIdx, toSlot);
		slots.set(toIdx, fromSlot);
		setPrayerSlots(prayerbook, slots);
		rebuildPrayers();
	}

	private void duplicatePrayer(int prayerbook, int prayerId)
	{
		List<Slot> slots = getPrayerSlots(prayerbook);
		if (visibleSlotCount(prayerbook, slots) >= MAX_VISIBLE_PRAYERS)
		{
			return;
		}

		int sourceIdx = ArrayUtils.indexOf(slots.stream().mapToInt(Slot::getPrayerId).toArray(), prayerId);
		Slot duplicate = new Slot(prayerId, true, nextDuplicateId(slots));

		if (sourceIdx == -1 || sourceIdx + 1 >= slots.size())
		{
			slots.add(duplicate);
		}
		else
		{
			slots.add(sourceIdx + 1, duplicate);
		}

		setPrayerSlots(prayerbook, slots);
		rebuildPrayers();
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
		return Arrays.stream(prayerEnum.getKeys())
			.boxed()
			.sorted(Comparator.comparing(id ->
			{
				int prayerObjId = prayerEnum.getIntValue(id);
				ItemComposition prayerObj = client.getItemDefinition(prayerObjId);
				return prayerObj.getIntValue(ParamID.OC_PRAYER_LEVEL);
			}))
			.map(id -> new Slot(id, false, 0))
			.collect(Collectors.toCollection(ArrayList::new));
	}

	private List<Slot> normalizePrayerSlots(int prayerbook, EnumComposition prayerEnum, List<Slot> slots)
	{
		List<Slot> normalized = slots.stream()
			.filter(slot -> containsPrayerKey(prayerEnum, slot.getPrayerId()))
			.collect(Collectors.toCollection(ArrayList::new));

		for (Slot defaultSlot : defaultPrayerSlots(prayerEnum))
		{
			boolean hasOriginal = normalized.stream()
				.anyMatch(slot -> !slot.isDuplicate() && slot.getPrayerId() == defaultSlot.getPrayerId());
			if (!hasOriginal)
			{
				normalized.add(defaultSlot);
			}
		}

		normalized = trimOverflowDuplicates(prayerbook, normalized);

		if (!normalized.equals(slots))
		{
			setPrayerSlots(prayerbook, normalized);
		}

		return normalized;
	}

	private List<Slot> trimOverflowDuplicates(int prayerbook, List<Slot> slots)
	{
		List<Slot> trimmed = new ArrayList<>();
		int visible = 0;
		for (Slot slot : slots)
		{
			if (!slot.isDuplicate() && isHidden(prayerbook, slot.getPrayerId()))
			{
				trimmed.add(slot);
				continue;
			}

			if (visible < MAX_VISIBLE_PRAYERS)
			{
				trimmed.add(slot);
				++visible;
			}
			else if (!slot.isDuplicate())
			{
				trimmed.add(slot);
			}
		}
		return trimmed;
	}

	private int visibleSlotCount(int prayerbook, List<Slot> slots)
	{
		int visible = 0;
		for (Slot slot : slots)
		{
			if (!slot.isDuplicate() && isHidden(prayerbook, slot.getPrayerId()))
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
		if (hidden)
		{
			configManager.setConfiguration(PRAYER_CONFIG_GROUP,
				"prayer_hidden_book_" + prayerbook + "_" + prayerId, true);
		}
		else
		{
			configManager.unsetConfiguration(PRAYER_CONFIG_GROUP,
				"prayer_hidden_book_" + prayerbook + "_" + prayerId);
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
		List<Slot> slots = getPrayerSlots(prayerbook);
		for (int i = 0; i < slots.size(); ++i)
		{
			Slot slot = slots.get(i);
			if (!slot.isDuplicate() && slot.getPrayerId() == prayerId)
			{
				if (i + 1 < slots.size() && slots.get(i + 1).isDuplicate())
				{
					slots.remove(i + 1);
				}
				break;
			}
		}

		setHidden(prayerbook, prayerId, false);
		setPrayerSlots(prayerbook, slots);
		redrawPrayers();
		clientThread.invokeLater(this::rebuildPrayers);
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
		Slot duplicateSlot = duplicateWidgets.get(widget);
		if (duplicateSlot != null)
		{
			return duplicateSlot;
		}

		int prayerId = findPrayerIdFromComponent(prayerbook, widget);
		return prayerId == -1 ? null : new Slot(prayerId, false, 0);
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

		for (int prayerId : prayerBookEnum.getKeys())
		{
			Widget widget = getPrayerWidget(prayerBookEnum, prayerId);

			if (widget == null)
			{
				continue;
			}

			widget.setHidden(false);

			Widget activeBackground = widget.getChild(1);
			if (activeBackground != null)
			{
				activeBackground.setOpacity(0);
			}
		}
	}

	private void rebuildPrayers()
	{
		if (!isPrayerBookOpen())
		{
			return;
		}

		resetOriginalPrayerPositions();

		clearDuplicateWidgets();

		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		EnumComposition prayerBookEnum = getPrayerBookEnum(prayerbook);
		List<Slot> slots = getPrayerSlots(prayerbook);
		if (!prayerReordering)
		{
			clearPrayerReorderActions(prayerBookEnum);
		}

		List<Slot> renderSlots = prayerReordering
			? prioritizeOriginalPrayerSlots(slots)
			: slots;
		boolean canDuplicate = true;

		int index = 0;
		for (int slotIndex = 0; slotIndex < renderSlots.size(); ++slotIndex)
		{
			Slot slot = renderSlots.get(slotIndex);
			Widget original = getPrayerWidget(prayerBookEnum, slot.getPrayerId());
			if (original == null)
			{
				continue;
			}

			boolean hidden = !slot.isDuplicate() && isHidden(prayerbook, slot.getPrayerId());
			if (hidden && !prayerReordering)
			{
				original.setHidden(true);
				if (slotIndex + 1 >= renderSlots.size() || !renderSlots.get(slotIndex + 1).isDuplicate())
				{
					++index;
				}
				continue;
			}

			if (index >= MAX_VISIBLE_PRAYERS)
			{
				if (!slot.isDuplicate())
				{
					original.setHidden(true);
				}
				continue;
			}

			int x = index % PRAYER_COLUMN_COUNT;
			int y = index / PRAYER_COLUMN_COUNT;
			int widgetX = x * PRAYER_X_OFFSET;
			int widgetY = y * PRAYER_Y_OFFSET;

			if (slot.isDuplicate())
			{
				Widget duplicate = createDuplicateWidget(original, slot, widgetX, widgetY, canDuplicate);
				duplicateWidgets.put(duplicate, slot);
			}
			else
			{
				original.setHidden(false);
				if (prayerReordering)
				{
					original.setAction(HIDE_OP, hidden ? "Unhide" : "Hide");
					if (hidden && original.getChild(1) != null)
					{
						original.getChild(1).setOpacity(200);
					}
				}
				else
				{
					original.setAction(HIDE_OP, null);
				}
				original.setAction(DUPLICATE_OP, DUPLICATE);
				original.setClickMask(original.getClickMask() | DRAG | DRAG_ON);
				original.setPos(widgetX, widgetY);
				original.revalidate();
			}

			++index;
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

	private List<Slot> prioritizeOriginalPrayerSlots(List<Slot> slots)
	{
		List<Slot> prioritized = new ArrayList<>();
		int originalsRemaining = (int) slots.stream()
			.filter(slot -> !slot.isDuplicate())
			.count();

		for (Slot slot : slots)
		{
			if (prioritized.size() >= MAX_VISIBLE_PRAYERS)
			{
				break;
			}

			if (slot.isDuplicate() && originalsRemaining > MAX_VISIBLE_PRAYERS - prioritized.size())
			{
				continue;
			}

			prioritized.add(slot);
			if (!slot.isDuplicate())
			{
				--originalsRemaining;
			}
		}

		return prioritized;
	}

	private Widget createDuplicateWidget(Widget original, Slot slot, int x, int y, boolean canDuplicate)
	{
		Widget duplicate = getDuplicateRoot(original).createChild(-1, WidgetType.LAYER);

		duplicate.setName(original.getName() + " (duplicate " + slot.getDuplicateId() + ")");
		duplicate.setSize(original.getWidth(), original.getHeight(), WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE);
		duplicate.setPos(x, y, WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP);
		duplicate.setClickMask(original.getClickMask() | DRAG | DRAG_ON);
		duplicate.setAction(0, getPrimaryAction(original, slot.getPrayerId()));
		duplicate.setAction(DUPLICATE_OP, canDuplicate ? DUPLICATE : null);
		duplicate.setAction(REMOVE_DUPLICATE_OP, REMOVE_DUPLICATE);
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
			else if (op == DUPLICATE_OP + 1)
			{
				duplicatePrayer(client.getVarbitValue(VarbitID.PRAYERBOOK), slot.getPrayerId());
			}
			else if (op == REMOVE_DUPLICATE_OP + 1)
			{
				removeDuplicate(client.getVarbitValue(VarbitID.PRAYERBOOK), slot);
			}
		});

		copyChildren(original, duplicate);
		syncDuplicateActiveState(duplicate, slot.getPrayerId());
		duplicate.revalidate();
		return duplicate;
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

	private void syncDuplicateActiveState(Widget duplicate, int prayerId)
	{
		Prayer prayer = getPrayer(prayerId);
		if (prayer == null)
		{
			return;
		}

		boolean active = client.isPrayerActive(prayer);
		Widget activeBackground = duplicate.getChild(0);
		if (activeBackground != null)
		{
			activeBackground.setHidden(!active);
			activeBackground.revalidate();
		}

		duplicate.setAction(0, active ? "Deactivate" : "Activate");
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
		Prayer prayer = getPrayer(prayerId);
		if (prayer != null)
		{
			return client.isPrayerActive(prayer) ? "Deactivate" : "Activate";
		}

		String[] actions = original.getActions();
		return actions != null && actions.length > 0 && actions[0] != null ? actions[0] : "Activate";
	}

	private void activateOriginalPrayer(Widget original, int prayerId)
	{
		String option = getPrimaryAction(original, prayerId);
		String target = original.getName();
		int param0 = -1;
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

	@Provides
	DuplicatePrayersConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DuplicatePrayersConfig.class);
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
}
