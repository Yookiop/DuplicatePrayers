package com.duplicateprayers;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import net.runelite.api.ScriptID;
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
	private static final int DUPLICATE_OP = 3;
	private static final int REMOVE_DUPLICATE_OP = 4;
	private static final int ACTIVATE_OP = 1;
	private static final String PRAYER_CONFIG_GROUP = "prayer";
	private static final String DUPLICATE = "Duplicate";
	private static final String REMOVE_DUPLICATE = "Remove duplicate";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	private final Map<Widget, Slot> duplicateWidgets = new HashMap<>();
	private final Set<Widget> duplicateParents = new HashSet<>();

	@Override
	protected void startUp()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::redrawPrayers);
		}
	}

	@Override
	protected void shutDown()
	{
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

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		clientThread.invokeLater(this::redrawPrayers);
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		int scriptId = event.getScriptId();
		if (scriptId == ScriptID.PRAYER_UPDATEBUTTON || scriptId == ScriptID.PRAYER_REDRAW)
		{
			clientThread.invokeLater(this::rebuildPrayers);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
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

		event.consume();
		duplicatePrayer(prayerbook, prayerId);
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

	private void rebuildPrayers()
	{
		if (!isPrayerBookOpen())
		{
			return;
		}

		clearDuplicateWidgets();

		int prayerbook = client.getVarbitValue(VarbitID.PRAYERBOOK);
		EnumComposition prayerBookEnum = getPrayerBookEnum(prayerbook);
		List<Slot> slots = getPrayerSlots(prayerbook);
		boolean canDuplicate = visibleSlotCount(prayerbook, slots) < MAX_VISIBLE_PRAYERS;

		int index = 0;
		for (Slot slot : slots)
		{
			Widget original = getPrayerWidget(prayerBookEnum, slot.getPrayerId());
			if (original == null)
			{
				continue;
			}

			if (!slot.isDuplicate() && isHidden(prayerbook, slot.getPrayerId()))
			{
				original.setHidden(true);
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
				original.setAction(DUPLICATE_OP, canDuplicate ? DUPLICATE : null);
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

	private Widget createDuplicateWidget(Widget original, Slot slot, int x, int y, boolean canDuplicate)
	{
		Widget originalParent = original.getParent();
		Widget parent = originalParent != null ? originalParent : client.getWidget(InterfaceID.Prayerbook.UNIVERSE);
		Widget duplicate = parent.createChild(-1, WidgetType.LAYER);
		duplicateParents.add(parent);

		if (originalParent == null)
		{
			log.debug("Prayer {} had no parent; duplicate was attached to Prayerbook.UNIVERSE", slot.getPrayerId());
		}

		duplicate.setName(original.getName());
		duplicate.setSize(original.getWidth(), original.getHeight(), WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE);
		duplicate.setPos(x, y, WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP);
		duplicate.setClickMask(original.getClickMask() | DRAG | DRAG_ON);
		duplicate.setAction(0, getPrimaryAction(original));
		duplicate.setAction(DUPLICATE_OP, canDuplicate ? DUPLICATE : null);
		duplicate.setAction(REMOVE_DUPLICATE_OP, REMOVE_DUPLICATE);
		duplicate.setHasListener(true);
		duplicate.setOnOpListener((JavaScriptCallback) event ->
		{
			int op = event.getOp();
			if (op == ACTIVATE_OP)
			{
				activateOriginalPrayer(original);
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
		duplicate.revalidate();
		return duplicate;
	}

	private String getPrimaryAction(Widget original)
	{
		String[] actions = original.getActions();
		return actions != null && actions.length > 0 && actions[0] != null ? actions[0] : "Activate";
	}

	private void activateOriginalPrayer(Widget original)
	{
		Object[] listener = original.getOnOpListener();
		if (listener != null)
		{
			client.createScriptEventBuilder(listener)
				.setSource(original)
				.setOp(ACTIVATE_OP)
				.build()
				.run();
			return;
		}

		client.menuAction(original.getIndex(), original.getId(), MenuAction.CC_OP, ACTIVATE_OP, -1, getPrimaryAction(original), original.getName());
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
		Widget[] children = source.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			children = source.getStaticChildren();
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
		duplicateWidgets.clear();

		for (Widget parent : duplicateParents)
		{
			if (parent != null)
			{
				parent.deleteAllChildren();
			}
		}
		duplicateParents.clear();
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
