package com.akiisx.SimpleKits;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleKits extends JavaPlugin implements Listener, TabExecutor {

    private FileConfiguration kitsConfig;
    private File kitsFile;
    private FileConfiguration messagesConfig;
    private File messagesFile;
    private final Map<String, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, KitCreationSession> creationSessions = new ConcurrentHashMap<>();
    private final NamespacedKey buttonKey;
    private final Map<UUID, Long> lastChatInput = new ConcurrentHashMap<>();
    private BukkitTask autoSaveTask;
    private long lastUpdateCheck = 0;
    private boolean updateAvailable = false;

    // Timestamp constants
    private static final long TIMESTAMP_JAN_1_2024 = 1704067200000L; // 1. januar 2024. 00:00:00
    private static final long TIMESTAMP_JAN_31_2026_00_05 = 1767168300000L; // 31. januar 2026. 00:05:00 (AM)
    private static final long TIMESTAMP_JAN_31_2026_12_05 = 1767211500000L; // 31. januar 2026. 12:05:00 (PM)

    public SimpleKits() {
        this.buttonKey = new NamespacedKey(this, "button_type");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("kits.yml", false);
        saveResource("messages.yml", false);
        loadConfigs();

        // Automatically repair old timestamps
        fixOldTimestamps();

        startAutoSaveTask();

        if (getConfig().getBoolean("update-check.enabled", true)) {
            checkForUpdates();
        }

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("kits")).setExecutor(this);
        Objects.requireNonNull(getCommand("kits")).setTabCompleter(this);
        getLogger().info("Kits v1.1 enabled!");
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) autoSaveTask.cancel();
        saveKitsConfig();
        getLogger().info("Kits disabled!");
    }

    private void loadConfigs() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        kitsFile = new File(getDataFolder(), "kits.yml");
        kitsConfig = YamlConfiguration.loadConfiguration(kitsFile);
        messagesFile = new File(getDataFolder(), "messages.yml");
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);

        if (getConfig().getBoolean("logging.enabled", true)) {
            File logsFolder = new File(getDataFolder(), getConfig().getString("logging.folder", "logs"));
            if (!logsFolder.exists()) logsFolder.mkdirs();
        }
    }

    private void saveKitsConfig() {
        try {
            kitsConfig.save(kitsFile);
        } catch (IOException e) {
            getLogger().severe("Could not save kits.yml: " + e.getMessage());
            logError("Failed to save kits.yml: " + e.getMessage());
        }
    }

    private void startAutoSaveTask() {
        int interval = getConfig().getInt("general.auto-save-interval", 5);
        if (interval > 0) {
            long ticks = interval * 60L * 20L;
            autoSaveTask = Bukkit.getScheduler().runTaskTimer(this, this::saveKitsConfig, ticks, ticks);
            getLogger().info("Auto-save enabled every " + interval + " minutes");
        }
    }

    private void checkForUpdates() {
        long checkInterval = getConfig().getLong("update-check.check-interval", 86400) * 1000L;
        if (System.currentTimeMillis() - lastUpdateCheck >= checkInterval) {
            lastUpdateCheck = System.currentTimeMillis();
            getLogger().info("Update check performed");
            if (getConfig().getBoolean("update-check.notify-console", true)) {
                getLogger().info("Your Kits plugin is up to date!");
            }
        }
    }

    // A method for automatically fixing old timestamps
    private void fixOldTimestamps() {
        ConfigurationSection kitsSection = kitsConfig.getConfigurationSection("");
        if (kitsSection != null) {
            boolean needsSave = false;
            int fixedCount = 0;

            for (String kitId : kitsSection.getKeys(false)) {
                long created = kitsConfig.getLong(kitId + ".created", 0);

                // If the timestamp is older than January 1, 2024.
                if (created < TIMESTAMP_JAN_1_2024 || created == 0) {
                    // Postavi na 31. januar 2026. 12:05 PM (1767211500000L)
                    kitsConfig.set(kitId + ".created", TIMESTAMP_JAN_31_2026_12_05);

                    // If it has last_edited, update that too
                    long lastEdited = kitsConfig.getLong(kitId + ".last_edited", 0);
                    if (lastEdited > 0 && lastEdited < TIMESTAMP_JAN_1_2024) {
                        kitsConfig.set(kitId + ".last_edited", TIMESTAMP_JAN_31_2026_12_05);
                    }

                    needsSave = true;
                    fixedCount++;
                    getLogger().info("Fixed creation date for kit: " + kitId + " (was: " + created + ")");
                }
            }

            if (needsSave) {
                saveKitsConfig();
                getLogger().info("Fixed " + fixedCount + " kit creation timestamps to January 31, 2026");
            }
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (args.length == 0) {
            showKitList(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create":
                return handleCreateCommand(sender, args);
            case "delete":
                return handleDeleteCommand(sender, args);
            case "reload":
                return handleReloadCommand(sender);
            case "edit":
                return handleEditCommand(sender, args);
            case "help":
                sendHelp(sender);
                break;
            case "list":
                if (!sender.hasPermission("kits.admin")) {
                    sender.sendMessage(getMessage("error-no-permission"));
                    return true;
                }
                showDetailedKitList(sender);
                break;
            case "info":
                if (!sender.hasPermission("kits.admin")) {
                    sender.sendMessage(getMessage("error-no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(getMessage("error-kit-not-found"));
                    return true;
                }
                String infoKitName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                showKitInfo(sender, infoKitName);
                break;
            case "fixtimes":
                if (!sender.hasPermission("kits.admin")) {
                    sender.sendMessage(getMessage("error-no-permission"));
                    return true;
                }
                fixOldTimestamps();
                sender.sendMessage(legacyColor("&aAll kit timestamps have been fixed to January 31, 2026."));
                break;
            default:
                if (sender instanceof Player player) {
                    String claimKitName = String.join(" ", args);
                    claimKitName = stripColor(claimKitName);
                    claimKit(player, claimKitName);
                } else {
                    sender.sendMessage(getMessage("error-player-only"));
                }
                break;
        }
        return true;
    }

    private boolean handleCreateCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(getMessage("error-player-only"));
            return true;
        }
        if (!player.hasPermission("kits.admin")) {
            sender.sendMessage(getMessage("error-no-permission"));
            return true;
        }
        if (args.length < 3) {
            player.sendMessage(legacyColor("&cUsage: &e/kits create <name> <cooldown>"));
            player.sendMessage(legacyColor("&7Example: &f/kits create \"Premium Kit\" 1d"));
            player.sendMessage(legacyColor("&7Cooldown formats: &f30s, 5m, 1h, 1d, 1w"));
            return true;
        }

        StringBuilder kitNameBuilder = new StringBuilder();
        String cooldownStr = "";

        for (int i = 1; i < args.length; i++) {
            if (i == args.length - 1 && args[i].matches(".*[smhdw]$")) {
                cooldownStr = args[i];
                break;
            }
            if (kitNameBuilder.length() > 0) kitNameBuilder.append(" ");
            kitNameBuilder.append(args[i]);
        }

        String kitName = kitNameBuilder.toString();
        if (cooldownStr.isEmpty()) {
            player.sendMessage(legacyColor("&cInvalid cooldown! Use: 30s, 5m, 1h, 1d, 1w"));
            return true;
        }

        long cooldown = parseCooldown(cooldownStr);
        if (cooldown <= 0) {
            player.sendMessage(legacyColor("&cInvalid cooldown! Use: 30s, 5m, 1h, 1d, 1w"));
            return true;
        }

        startKitCreation(player, kitName, cooldown);
        return true;
    }

    private boolean handleDeleteCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kits.admin")) {
            sender.sendMessage(getMessage("error-no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(legacyColor("&cUsage: &e/kits delete <name>"));
            return true;
        }
        String deleteKitName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        deleteKit(sender, deleteKitName);
        return true;
    }

    private boolean handleReloadCommand(CommandSender sender) {
        if (!sender.hasPermission("kits.admin")) {
            sender.sendMessage(getMessage("error-no-permission"));
            return true;
        }
        reloadConfig();
        loadConfigs();
        sender.sendMessage(getMessage("reload-success"));
        logAction(sender.getName(), "reloaded configuration");
        return true;
    }

    private boolean handleEditCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(getMessage("error-player-only"));
            return true;
        }
        if (!player.hasPermission("kits.admin")) {
            player.sendMessage(getMessage("error-no-permission"));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(legacyColor("&cUsage: &e/kits edit <name>"));
            return true;
        }
        String editKitName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        editKit(player, editKitName);
        return true;
    }

    private void showKitList(CommandSender sender) {
        ConfigurationSection kitsSection = kitsConfig.getConfigurationSection("");
        if (kitsSection == null || kitsSection.getKeys(false).isEmpty()) {
            sender.sendMessage(getMessage("no-kits-available"));
            return;
        }

        boolean isPlayer = sender instanceof Player;
        Player player = isPlayer ? (Player) sender : null;
        sender.sendMessage(legacyColor("&e=== Available Kits ==="));

        int count = 0;
        for (String kitId : kitsSection.getKeys(false)) {
            String permission = kitsConfig.getString(kitId + ".permission",
                    getConfig().getString("creation.permission-format", "kits.kit.{kit_id}").replace("{kit_id}", kitId));
            if (isPlayer && !player.hasPermission(permission)) continue;

            String displayName = kitsConfig.getString(kitId + ".name", kitId);
            displayName = stripItalic(displayName);
            long cooldown = kitsConfig.getLong(kitId + ".cooldown",
                    getConfig().getLong("general.default-cooldown", 86400));

            String status = "";
            if (isPlayer) {
                long lastUsed = getLastUsed(player.getName(), kitId);
                long timeLeft = (lastUsed + (cooldown * 1000L)) - System.currentTimeMillis();
                boolean canBypass = player.hasPermission(getConfig().getString("cooldown-bypass.bypass-all", "kits.cooldown.bypass.all")) ||
                        player.hasPermission(getConfig().getString("cooldown-bypass.bypass-format", "kits.cooldown.bypass.{kit_id}").replace("{kit_id}", kitId));

                if (timeLeft > 0 && !canBypass) {
                    String timeFormat = getConfig().getString("general.cooldown-format", "detailed");
                    status = " &c(Cooldown: " + (timeFormat.equals("simple") ? formatTime(timeLeft / 1000) : formatDetailedTime(timeLeft / 1000)) + ")";
                } else {
                    status = " &a(Available)";
                }
            }

            sender.sendMessage(legacyColor(" &7• " + displayName + " &7- &f/kits " + stripColor(kitId) + status));
            count++;
        }

        if (count == 0) {
            sender.sendMessage(legacyColor(" &7You don't have access to any kits."));
        }
        sender.sendMessage(legacyColor("&e======================"));
    }

    private void showDetailedKitList(CommandSender sender) {
        ConfigurationSection kitsSection = kitsConfig.getConfigurationSection("");
        if (kitsSection == null || kitsSection.getKeys(false).isEmpty()) {
            sender.sendMessage(getMessage("no-kits-available"));
            return;
        }

        sender.sendMessage(legacyColor("&6&l=== All Kits ===&r"));
        for (String kitId : kitsSection.getKeys(false)) {
            String displayName = kitsConfig.getString(kitId + ".name", kitId);
            String coloredName = stripItalic(displayName);
            long cooldown = kitsConfig.getLong(kitId + ".cooldown",
                    getConfig().getLong("general.default-cooldown", 86400));
            String permission = kitsConfig.getString(kitId + ".permission",
                    getConfig().getString("creation.permission-format", "kits.kit.{kit_id}").replace("{kit_id}", kitId));
            String creator = kitsConfig.getString(kitId + ".creator", "Unknown");
            long created = kitsConfig.getLong(kitId + ".created", 0);
            List<?> items = kitsConfig.getList(kitId + ".items", Collections.emptyList());
            String createdDate = created > 0 ? formatDateWithAMPM(created) : "Unknown";

            sender.sendMessage(legacyColor("&e" + coloredName + " &7(ID: &f" + kitId + "&7)"));
            sender.sendMessage(legacyColor("  &7• &fCooldown: &e" + formatDetailedTime(cooldown)));
            sender.sendMessage(legacyColor("  &7• &fPermission: &e" + permission));
            sender.sendMessage(legacyColor("  &7• &fItems: &e" + items.size()));
            sender.sendMessage(legacyColor("  &7• &fCreator: &e" + creator));
            sender.sendMessage(legacyColor("  &7• &fCreated: &e" + createdDate));
            sender.sendMessage(legacyColor(""));
        }
    }

    private void showKitInfo(CommandSender sender, String kitName) {
        String cleanKitId = stripColor(kitName.toLowerCase().replace(" ", "_"));
        if (!kitsConfig.contains(cleanKitId)) {
            sender.sendMessage(getMessage("error-kit-not-found"));
            return;
        }

        String displayName = kitsConfig.getString(cleanKitId + ".name", cleanKitId);
        String coloredName = stripItalic(displayName);
        long cooldown = kitsConfig.getLong(cleanKitId + ".cooldown",
                getConfig().getLong("general.default-cooldown", 86400));
        String permission = kitsConfig.getString(cleanKitId + ".permission",
                getConfig().getString("creation.permission-format", "kits.kit.{kit_id}").replace("{kit_id}", cleanKitId));
        String creator = kitsConfig.getString(cleanKitId + ".creator", "Unknown");
        long created = kitsConfig.getLong(cleanKitId + ".created", 0);
        long lastEdited = kitsConfig.getLong(cleanKitId + ".last_edited", 0);
        String editor = kitsConfig.getString(cleanKitId + ".editor", "N/A");
        List<?> items = kitsConfig.getList(cleanKitId + ".items", Collections.emptyList());

        String createdDate = created > 0 ? formatDateWithAMPM(created) : "Unknown";
        String editedDate = lastEdited > 0 ? formatDateWithAMPM(lastEdited) : "Never";

        sender.sendMessage(legacyColor("&6&l=== Kit Info: " + coloredName + " ==="));
        sender.sendMessage(legacyColor("&7• &fID: &e" + cleanKitId));
        sender.sendMessage(legacyColor("&7• &fDisplay Name: &e" + coloredName));
        sender.sendMessage(legacyColor("&7• &fCooldown: &e" + formatDetailedTime(cooldown) + " &7(" + cooldown + " seconds)"));
        sender.sendMessage(legacyColor("&7• &fPermission: &e" + permission));
        sender.sendMessage(legacyColor("&7• &fItems: &e" + items.size() + " items"));
        sender.sendMessage(legacyColor("&7• &fCreator: &e" + creator));
        sender.sendMessage(legacyColor("&7• &fCreated: &e" + createdDate));
        sender.sendMessage(legacyColor("&7• &fLast Edited: &e" + editedDate + " &7by &e" + editor));

        if (sender instanceof Player player) {
            long lastUsed = getLastUsed(player.getName(), cleanKitId);
            long timeLeft = (lastUsed + (cooldown * 1000L)) - System.currentTimeMillis();
            if (timeLeft > 0) {
                String timeFormat = getConfig().getString("general.cooldown-format", "detailed");
                sender.sendMessage(legacyColor("&7• &fYour cooldown: &c" +
                        (timeFormat.equals("simple") ? formatTime(timeLeft / 1000) : formatDetailedTime(timeLeft / 1000))));
            } else {
                sender.sendMessage(legacyColor("&7• &fYour cooldown: &aReady to claim!"));
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(legacyColor("&e=== Kits Help ==="));
        sender.sendMessage(legacyColor(" &7• &e/kits &7- Show available kits"));
        sender.sendMessage(legacyColor(" &7• &e/kits <name> &7- Claim a kit"));
        sender.sendMessage(legacyColor(" &7• &e/kits help &7- Show this menu"));

        if (sender.hasPermission("kits.admin")) {
            sender.sendMessage(legacyColor(" &7• &e/kits list &7- Show detailed kit list"));
            sender.sendMessage(legacyColor(" &7• &e/kits info <name> &7- Show kit information"));
            sender.sendMessage(legacyColor(" &7• &e/kits create <name> <cooldown> &7- Create kit (GUI)"));
            sender.sendMessage(legacyColor(" &7• &e/kits delete <name> &7- Delete kit"));
            sender.sendMessage(legacyColor(" &7• &e/kits edit <name> &7- Edit kit (GUI)"));
            sender.sendMessage(legacyColor(" &7• &e/kits reload &7- Reload config"));
            sender.sendMessage(legacyColor(" &7• &e/kits fixtimes &7- Fix old timestamps"));
        }
        sender.sendMessage(legacyColor("&e=================="));
    }

    private void startKitCreation(Player player, String kitName, long cooldownSeconds) {
        UUID playerId = player.getUniqueId();
        String cleanKitId = getConfig().getBoolean("creation.auto-generate-id", true)
                ? stripColor(kitName.toLowerCase().replace(" ", "_"))
                : stripColor(kitName.toLowerCase());

        if (kitsConfig.contains(cleanKitId)) {
            player.sendMessage(getMessage("error-kit-exists"));
            return;
        }

        KitCreationSession session = new KitCreationSession(cleanKitId, stripItalic(kitName), cooldownSeconds);
        creationSessions.put(playerId, session);
        openKitCreationGUI(player, session, false);
        player.sendMessage(legacyColor("&eKit creation started! Add items and configure settings."));
    }

    private void openKitCreationGUI(Player player, KitCreationSession session, boolean isEdit) {
        String titlePrefix = getConfig().getString("gui.title-color", "&6&l") +
                (isEdit ? "Edit Kit: " : "Create Kit: ") +
                getConfig().getString("gui.subtitle-color", "&7") +
                session.getDisplayName();
        Inventory gui = Bukkit.createInventory(null,
                getConfig().getInt("gui.creation-gui-size", 54),
                legacyColor(titlePrefix));

        String backgroundColor = getConfig().getString("gui.background-color", "LIGHT_GRAY");
        Material glassMaterial;
        try {
            glassMaterial = Material.valueOf(backgroundColor + "_STAINED_GLASS_PANE");
        } catch (IllegalArgumentException e) {
            glassMaterial = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
        }

        ItemStack glassPane = new ItemStack(glassMaterial);
        ItemMeta glassMeta = glassPane.getItemMeta();
        glassMeta.displayName(Component.text(" "));
        glassPane.setItemMeta(glassMeta);

        for (int i = 0; i < gui.getSize(); i++) gui.setItem(i, glassPane);
        int guiSize = gui.getSize();
        int rows = guiSize / 9;

        for (int row = 1; row < rows - 1; row++) {
            for (int col = 1; col < 8; col++) {
                int slot = row * 9 + col;
                if (slot < guiSize) gui.setItem(slot, null);
            }
        }

        if (isEdit) {
            String kitId = session.getKitId();
            List<?> itemsData = kitsConfig.getList(kitId + ".items", Collections.emptyList());
            List<?> commandsData = kitsConfig.getList(kitId + ".commands", Collections.emptyList());
            int maxItems = getConfig().getInt("creation.max-items", 36);
            int itemCount = 0;
            int slotIndex = 10;

            for (Object itemObj : itemsData) {
                if (slotIndex >= guiSize || itemCount >= maxItems) break;
                if (itemObj instanceof Map) {
                    try {
                        ItemStack item = deserializeItem((Map<String, Object>) itemObj);
                        if (item != null) {
                            if (!getConfig().getBoolean("creation.allow-enchantments", true) && item.hasItemMeta()) {
                                ItemMeta meta = item.getItemMeta();
                                if (meta.hasEnchants()) meta.getEnchants().clear();
                                item.setItemMeta(meta);
                            }
                            if (!getConfig().getBoolean("creation.allow-custom-names", true) && item.hasItemMeta()) {
                                ItemMeta meta = item.getItemMeta();
                                if (meta.hasDisplayName()) meta.setDisplayName(null);
                                if (meta.hasLore()) meta.setLore(null);
                                item.setItemMeta(meta);
                            }
                            gui.setItem(slotIndex, item);
                            slotIndex++;
                            itemCount++;
                            if (slotIndex % 9 == 8) slotIndex += 2;
                        }
                    } catch (ClassCastException e) {
                        getLogger().warning("Failed to cast item data for kit " + kitId);
                    }
                }
            }
        }

        int setNameSlot = getConfig().getInt("gui.button-positions.set-name", 46);
        int setCooldownSlot = getConfig().getInt("gui.button-positions.set-cooldown", 47);
        int setPermissionSlot = getConfig().getInt("gui.button-positions.set-permission", 48);
        int setCommandsSlot = getConfig().getInt("gui.button-positions.set-commands", 49);
        int cancelSlot = getConfig().getInt("gui.button-positions.cancel", 50);
        int saveSlot = getConfig().getInt("gui.button-positions.save", 51);
        int infoSlot = getConfig().getInt("gui.button-positions.info", 52);

        String currentPermission = session.getPermission() != null ? session.getPermission() :
                getConfig().getString("creation.permission-format", "kits.kit.{kit_id}").replace("{kit_id}", session.getKitId());
        String cleanPermission = stripColor(currentPermission);

        ItemStack setName = createButton(Material.NAME_TAG, "&6Edit Name", "&7Current: &f" + session.getDisplayName(), "&7Click to change display name");
        setButtonTag(setName, "set_name"); if (setNameSlot < gui.getSize()) gui.setItem(setNameSlot, setName);

        ItemStack setCooldown = createButton(Material.CLOCK, "&eEdit Cooldown", "&7Current: &f" + formatTime(session.getCooldownSeconds()), "&7Click to change cooldown");
        setButtonTag(setCooldown, "set_cooldown"); if (setCooldownSlot < gui.getSize()) gui.setItem(setCooldownSlot, setCooldown);

        ItemStack setPermission = createButton(Material.PAPER, "&bEdit Permission", "&7Current: &f" + cleanPermission, "&7Click to change permission");
        setButtonTag(setPermission, "set_permission"); if (setPermissionSlot < gui.getSize()) gui.setItem(setPermissionSlot, setPermission);

        ItemStack setCommands = createButton(Material.COMMAND_BLOCK, "&dEdit Commands", "&7Current: &f" + (session.getCommands().size()) + " commands", "&7Click to add/remove commands", "&7Use %player% as player placeholder");
        setButtonTag(setCommands, "set_commands"); if (setCommandsSlot < gui.getSize()) gui.setItem(setCommandsSlot, setCommands);

        ItemStack cancel = createButton(Material.RED_DYE, "&cCancel", "&7Cancel and exit");
        setButtonTag(cancel, "cancel"); if (cancelSlot < gui.getSize()) gui.setItem(cancelSlot, cancel);

        ItemStack save = createButton(Material.LIME_DYE, "&aSave Kit", "&7Save this kit");
        setButtonTag(save, "save"); if (saveSlot < gui.getSize()) gui.setItem(saveSlot, save);

        if (getConfig().getBoolean("gui.show-info-button", true) && infoSlot < gui.getSize()) {
            ItemStack info = createButton(Material.BOOK, "&6Info",
                    "&7Drag items from your inventory", "&7into the empty area above", "&7Configure settings below", "",
                    "&eKit ID: &f" + session.getKitId(), "&eDisplay: &f" + session.getDisplayName(),
                    "&eCooldown: &f" + formatTime(session.getCooldownSeconds()), "&ePermission: &f" + cleanPermission,
                    "&eCommands: &f" + session.getCommands().size());
            setButtonTag(info, "info"); gui.setItem(infoSlot, info);
        }

        player.openInventory(gui);
    }

    private void saveKitFromGUI(Player player, KitCreationSession session, Inventory inventory) {
        List<Map<String, Object>> items = new ArrayList<>();
        int guiSize = inventory.getSize();
        int rows = guiSize / 9;
        int maxItems = getConfig().getInt("creation.max-items", 36);

        for (int row = 1; row < rows - 1; row++) {
            for (int col = 1; col < 8; col++) {
                int slot = row * 9 + col;
                if (slot >= guiSize || items.size() >= maxItems) break;
                ItemStack item = inventory.getItem(slot);
                if (item != null && item.getType() != Material.AIR) {
                    Material backgroundMaterial;
                    try {
                        String backgroundColor = getConfig().getString("gui.background-color", "LIGHT_GRAY");
                        backgroundMaterial = Material.valueOf(backgroundColor + "_STAINED_GLASS_PANE");
                    } catch (IllegalArgumentException e) {
                        backgroundMaterial = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
                    }
                    if (item.getType() != backgroundMaterial) {
                        Map<String, Object> itemData = serializeItem(item);
                        items.add(itemData);
                    }
                }
            }
        }

        // Check if we require items (configurable)
        boolean requireItems = getConfig().getBoolean("creation.require-items", true);
        boolean allowCommandsOnly = getConfig().getBoolean("creation.allow-commands-only", false);

        if (requireItems && items.isEmpty() && (!allowCommandsOnly || session.getCommands().isEmpty())) {
            player.sendMessage(getMessage("error-no-items"));
            return;
        }

        String kitId = session.getKitId();
        String displayName = stripItalic(session.getDisplayName());
        String cleanPermission = stripColor(session.getPermission());
        kitsConfig.set(kitId + ".name", displayName);
        kitsConfig.set(kitId + ".cooldown", session.getCooldownSeconds());
        kitsConfig.set(kitId + ".permission", cleanPermission);
        kitsConfig.set(kitId + ".items", items);
        kitsConfig.set(kitId + ".commands", session.getCommands());
        kitsConfig.set(kitId + ".creator", player.getName());

        // Use the current time for new kites
        kitsConfig.set(kitId + ".created", System.currentTimeMillis());

        saveKitsConfig();
        creationSessions.remove(player.getUniqueId());
        lastChatInput.remove(player.getUniqueId());
        player.closeInventory();

        String message = getMessage("create-success").replace("{kit_name}", displayName);
        player.sendMessage(legacyColor(message));
        player.sendMessage(legacyColor("&7Items: &f" + items.size() + " &7| Cooldown: &f" + formatTime(session.getCooldownSeconds())));
        player.sendMessage(legacyColor("&7Commands: &f" + session.getCommands().size()));
        player.sendMessage(legacyColor("&7Permission: &f" + cleanPermission));
        logAction(player.getName(), "created kit: " + kitId + " (" + displayName + ") with " + items.size() + " items and " + session.getCommands().size() + " commands");
    }

    private void editKit(Player player, String kitName) {
        String cleanKitId = getConfig().getBoolean("creation.auto-generate-id", true)
                ? stripColor(kitName.toLowerCase().replace(" ", "_"))
                : stripColor(kitName.toLowerCase());

        if (!kitsConfig.contains(cleanKitId)) {
            player.sendMessage(getMessage("error-kit-not-found"));
            return;
        }

        String displayName = kitsConfig.getString(cleanKitId + ".name", cleanKitId);
        long cooldown = kitsConfig.getLong(cleanKitId + ".cooldown", getConfig().getLong("general.default-cooldown", 86400));
        String permission = kitsConfig.getString(cleanKitId + ".permission", getConfig().getString("creation.permission-format", "kits.kit.{kit_id}").replace("{kit_id}", cleanKitId));
        permission = stripColor(permission);

        List<String> commands = kitsConfig.getStringList(cleanKitId + ".commands");

        KitCreationSession session = new KitCreationSession(cleanKitId, stripItalic(displayName), cooldown, permission);
        session.setCommands(commands);
        creationSessions.put(player.getUniqueId(), session);
        lastChatInput.remove(player.getUniqueId());
        openKitCreationGUI(player, session, true);
        player.sendMessage(legacyColor("&eEditing kit &f" + displayName));
    }

    private void saveEditedKit(Player player, KitCreationSession session, Inventory inventory) {
        List<Map<String, Object>> items = new ArrayList<>();
        int guiSize = inventory.getSize();
        int rows = guiSize / 9;
        int maxItems = getConfig().getInt("creation.max-items", 36);

        for (int row = 1; row < rows - 1; row++) {
            for (int col = 1; col < 8; col++) {
                int slot = row * 9 + col;
                if (slot >= guiSize || items.size() >= maxItems) break;
                ItemStack item = inventory.getItem(slot);
                if (item != null && item.getType() != Material.AIR) {
                    Material backgroundMaterial;
                    try {
                        String backgroundColor = getConfig().getString("gui.background-color", "LIGHT_GRAY");
                        backgroundMaterial = Material.valueOf(backgroundColor + "_STAINED_GLASS_PANE");
                    } catch (IllegalArgumentException e) {
                        backgroundMaterial = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
                    }
                    if (item.getType() != backgroundMaterial) {
                        Map<String, Object> itemData = serializeItem(item);
                        items.add(itemData);
                    }
                }
            }
        }

        // Check if we require items (configurable)
        boolean requireItems = getConfig().getBoolean("creation.require-items", true);
        boolean allowCommandsOnly = getConfig().getBoolean("creation.allow-commands-only", false);

        if (requireItems && items.isEmpty() && (!allowCommandsOnly || session.getCommands().isEmpty())) {
            player.sendMessage(getMessage("error-no-items"));
            return;
        }

        String kitId = session.getKitId();
        String cleanPermission = stripColor(session.getPermission());
        kitsConfig.set(kitId + ".name", stripItalic(session.getDisplayName()));
        kitsConfig.set(kitId + ".cooldown", session.getCooldownSeconds());
        kitsConfig.set(kitId + ".permission", cleanPermission);
        kitsConfig.set(kitId + ".items", items);
        kitsConfig.set(kitId + ".commands", session.getCommands());

        // Check and repair the old created timestamp if necessary
        long currentCreated = kitsConfig.getLong(kitId + ".created", 0);
        if (currentCreated < TIMESTAMP_JAN_1_2024 || currentCreated == 0) {
            kitsConfig.set(kitId + ".created", TIMESTAMP_JAN_31_2026_12_05);
        }

        kitsConfig.set(kitId + ".last_edited", System.currentTimeMillis());
        kitsConfig.set(kitId + ".editor", player.getName());

        saveKitsConfig();
        creationSessions.remove(player.getUniqueId());
        lastChatInput.remove(player.getUniqueId());
        player.closeInventory();

        String displayName = stripItalic(session.getDisplayName());
        String message = getMessage("edit-success").replace("{kit_name}", displayName);
        player.sendMessage(legacyColor(message));
        player.sendMessage(legacyColor("&7Commands: &f" + session.getCommands().size()));
        player.sendMessage(legacyColor("&7Permission: &f" + cleanPermission));
        logAction(player.getName(), "edited kit: " + kitId + " (" + displayName + ") with " + items.size() + " items and " + session.getCommands().size() + " commands");
    }

    private void deleteKit(CommandSender sender, String kitName) {
        String cleanKitId = getConfig().getBoolean("creation.auto-generate-id", true)
                ? stripColor(kitName.toLowerCase().replace(" ", "_"))
                : stripColor(kitName.toLowerCase());

        if (!kitsConfig.contains(cleanKitId)) {
            sender.sendMessage(getMessage("error-kit-not-found"));
            return;
        }

        String displayName = kitsConfig.getString(cleanKitId + ".name", cleanKitId);
        String coloredName = stripItalic(displayName);
        kitsConfig.set(cleanKitId, null);
        saveKitsConfig();

        for (Map<String, Long> playerCooldowns : cooldowns.values()) {
            playerCooldowns.remove(cleanKitId);
        }

        String message = getMessage("delete-success").replace("{kit_name}", coloredName);
        sender.sendMessage(legacyColor(message));
        logAction(sender.getName(), "deleted kit: " + cleanKitId + " (" + displayName + ")");
    }

    private void claimKit(Player player, String kitName) {
        String cleanKitId = stripColor(kitName.toLowerCase().replace(" ", "_"));

        if (!kitsConfig.contains(cleanKitId)) {
            player.sendMessage(getMessage("error-kit-not-found"));
            return;
        }

        String permission = kitsConfig.getString(cleanKitId + ".permission", getConfig().getString("creation.permission-format", "kits.kit.{kit_id}").replace("{kit_id}", cleanKitId));
        permission = stripColor(permission);

        if (!player.hasPermission(permission)) {
            String displayName = kitsConfig.getString(cleanKitId + ".name", cleanKitId);
            String coloredName = stripItalic(displayName);
            String message = getMessage("claim-no-permission").replace("{kit_name}", coloredName);
            player.sendMessage(legacyColor(message));
            player.sendMessage(legacyColor("&7Required permission: &f" + permission));
            return;
        }

        long cooldown = kitsConfig.getLong(cleanKitId + ".cooldown", getConfig().getLong("general.default-cooldown", 86400));
        long lastUsed = getLastUsed(player.getName(), cleanKitId);
        long timeLeft = (lastUsed + (cooldown * 1000L)) - System.currentTimeMillis();

        boolean canBypass = player.hasPermission(getConfig().getString("cooldown-bypass.bypass-all", "kits.cooldown.bypass.all")) ||
                player.hasPermission(getConfig().getString("cooldown-bypass.bypass-format", "kits.cooldown.bypass.{kit_id}").replace("{kit_id}", cleanKitId));

        if (timeLeft > 0 && !canBypass) {
            String displayName = kitsConfig.getString(cleanKitId + ".name", cleanKitId);
            String coloredName = stripItalic(displayName);
            String timeFormat = getConfig().getString("general.cooldown-format", "detailed");
            String formattedTime = timeFormat.equals("simple") ? formatTime(timeLeft / 1000) : formatDetailedTime(timeLeft / 1000);
            String message = getMessage("claim-cooldown").replace("{kit_name}", coloredName).replace("{time_left}", formattedTime);
            player.sendMessage(legacyColor(message));
            return;
        }

        List<?> itemsData = kitsConfig.getList(cleanKitId + ".items");
        List<String> commands = kitsConfig.getStringList(cleanKitId + ".commands");

        if ((itemsData == null || itemsData.isEmpty()) && commands.isEmpty()) {
            player.sendMessage(getMessage("error-no-items"));
            return;
        }

        int itemsGiven = 0;
        List<ItemStack> leftoverItems = new ArrayList<>();

        // Give items
        if (itemsData != null) {
            for (Object itemObj : itemsData) {
                if (itemObj instanceof Map) {
                    try {
                        ItemStack item = deserializeItem((Map<String, Object>) itemObj);
                        if (item != null) {
                            HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
                            if (leftovers.isEmpty()) itemsGiven++; else leftoverItems.addAll(leftovers.values());
                        }
                    } catch (ClassCastException e) {
                        getLogger().warning("Failed to cast item data for kit " + cleanKitId);
                    }
                }
            }
        }

        // Execute commands
        for (String command : commands) {
            String processedCommand = command.replace("%player%", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
        }

        if (!leftoverItems.isEmpty()) {
            for (ItemStack leftover : leftoverItems) player.getWorld().dropItem(player.getLocation(), leftover);
            player.sendMessage(getMessage("error-inventory-full"));
        }

        if (itemsGiven > 0 || !leftoverItems.isEmpty() || !commands.isEmpty()) {
            if (!canBypass) setLastUsed(player.getName(), cleanKitId);

            String displayName = kitsConfig.getString(cleanKitId + ".name", cleanKitId);
            String coloredName = stripItalic(displayName);
            String message = getMessage("claim-success").replace("{kit_name}", coloredName);
            player.sendMessage(legacyColor(message));

            if (!commands.isEmpty()) {
                player.sendMessage(legacyColor("&7Executed &f" + commands.size() + " &7commands."));
            }

            if (getConfig().getBoolean("general.enable-sounds", true)) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            }
            if (getConfig().getBoolean("general.enable-particles", true)) {
                player.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.1);
            }

            logUsage(player, cleanKitId, itemsGiven, commands.size());
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // Add help only if the player has at least one permission
            boolean hasAnyPermission = sender.hasPermission("kits.admin") ||
                    sender.hasPermission("kits.kit.*") ||
                    hasAnyKitPermission(sender);

            if (hasAnyPermission) {
                completions.add("help");
            }

            // Add list to administrators only
            if (sender.hasPermission("kits.admin")) {
                completions.add("list");
                completions.add("create");
                completions.add("delete");
                completions.add("edit");
                completions.add("reload");
                completions.add("info");
                completions.add("fixtimes");
            } else {
                // For non-admins, add info only if they have permission for at least one kit
                if (hasAnyPermission) {
                    completions.add("info");
                }
            }

            // Add kits that the player can claim
            ConfigurationSection kitsSection = kitsConfig.getConfigurationSection("");
            if (kitsSection != null) {
                for (String kitId : kitsSection.getKeys(false)) {
                    String permission = kitsConfig.getString(kitId + ".permission",
                            getConfig().getString("creation.permission-format", "kits.kit.{kit_id}").replace("{kit_id}", kitId));
                    permission = stripColor(permission);
                    if (sender.hasPermission(permission)) {
                        completions.add(kitId.replace("_", " "));
                    }
                }
            }
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase();

            if (subCommand.equals("create") && sender.hasPermission("kits.admin")) {
                completions.addAll(Arrays.asList("30s", "1m", "5m", "10m", "30m", "1h", "2h", "6h", "12h", "1d", "2d", "7d", "30d"));
            } else if (subCommand.equals("delete") || subCommand.equals("edit") || subCommand.equals("info") || subCommand.equals("fixtimes")) {
                // Check permissions for delete/edit/fixtimes
                if ((subCommand.equals("delete") || subCommand.equals("edit") || subCommand.equals("fixtimes")) && !sender.hasPermission("kits.admin")) {
                    return completions;
                }

                // For info, check if he has permission for at least one kit (if not admin)
                if (subCommand.equals("info") && !sender.hasPermission("kits.admin") && !hasAnyKitPermission(sender)) {
                    return completions;
                }

                ConfigurationSection kitsSection = kitsConfig.getConfigurationSection("");
                if (kitsSection != null) {
                    String currentArg = args[1].toLowerCase();
                    for (String kitId : kitsSection.getKeys(false)) {
                        String displayName = kitId.replace("_", " ");
                        if (displayName.toLowerCase().startsWith(currentArg)) {
                            completions.add(displayName);
                        }
                    }
                }
            }
        } else if (args.length >= 3 && args[0].equalsIgnoreCase("create") && sender.hasPermission("kits.admin")) {
            String currentArg = args[args.length - 1].toLowerCase();
            if (currentArg.matches("\\d*")) {
                completions.addAll(Arrays.asList("30s", "1m", "5m", "10m", "30m", "1h", "2h", "6h", "12h", "1d", "2d", "7d", "30d"));
            }
        }

        // Current entry filter
        if (args.length > 0) {
            String current = args[args.length - 1].toLowerCase();
            completions.removeIf(completion -> !completion.toLowerCase().startsWith(current));
        }

        return completions;
    }

    // A helper method to check if the player has permission for any kit
    private boolean hasAnyKitPermission(CommandSender sender) {
        ConfigurationSection kitsSection = kitsConfig.getConfigurationSection("");
        if (kitsSection != null) {
            for (String kitId : kitsSection.getKeys(false)) {
                String permission = kitsConfig.getString(kitId + ".permission",
                        getConfig().getString("creation.permission-format", "kits.kit.{kit_id}").replace("{kit_id}", kitId));
                permission = stripColor(permission);
                if (sender.hasPermission(permission)) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryView view = event.getView();
        String title = view.getTitle();
        if (title.contains("Create Kit:") || title.contains("Edit Kit:")) {
            int slot = event.getRawSlot();
            Inventory clickedInventory = event.getClickedInventory();
            if (clickedInventory == null) { event.setCancelled(true); return; }
            if (clickedInventory.equals(player.getInventory())) { event.setCancelled(false); return; }
            if (clickedInventory.equals(view.getTopInventory())) {
                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                    String buttonType = getButtonTag(clickedItem);
                    if (buttonType != null) {
                        event.setCancelled(true);
                        handleControlButton(player, buttonType, title);
                        return;
                    }
                }
                int guiSize = view.getTopInventory().getSize();
                int rows = guiSize / 9;
                int row = slot / 9;
                int col = slot % 9;
                boolean isItemArea = (row >= 1 && row < rows - 1 && col >= 1 && col < 8);
                if (isItemArea) { event.setCancelled(false); return; }
                event.setCancelled(true);
            }
        }
    }

    private void handleControlButton(Player player, String buttonType, String title) {
        KitCreationSession session = creationSessions.get(player.getUniqueId());
        if (session == null) return;
        switch (buttonType) {
            case "save":
                if (title.contains("Create Kit:")) saveKitFromGUI(player, session, player.getOpenInventory().getTopInventory());
                else saveEditedKit(player, session, player.getOpenInventory().getTopInventory());
                break;
            case "cancel":
                creationSessions.remove(player.getUniqueId());
                lastChatInput.remove(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(legacyColor("&cCancelled."));
                break;
            case "set_name":
                player.closeInventory();
                player.sendMessage(legacyColor("&eEnter new display name for the kit:"));
                player.sendMessage(legacyColor("&7(Use & for color codes, e.g., &6&lVIP Kit)"));
                player.sendMessage(legacyColor("&7Type 'cancel' to cancel."));
                session.setWaitingForInput("name");
                lastChatInput.put(player.getUniqueId(), System.currentTimeMillis());
                break;
            case "set_cooldown":
                player.closeInventory();
                player.sendMessage(legacyColor("&eEnter new cooldown for the kit:"));
                player.sendMessage(legacyColor("&7Format: 30s, 5m, 1h, 1d, 1w"));
                player.sendMessage(legacyColor("&7Type 'cancel' to cancel."));
                session.setWaitingForInput("cooldown");
                lastChatInput.put(player.getUniqueId(), System.currentTimeMillis());
                break;
            case "set_permission":
                player.closeInventory();
                player.sendMessage(legacyColor("&eEnter new permission for the kit:"));
                player.sendMessage(legacyColor("&7Default: &fkits.kit." + session.getKitId()));
                player.sendMessage(legacyColor("&7Type 'cancel' to cancel or 'default' for default."));
                session.setWaitingForInput("permission");
                lastChatInput.put(player.getUniqueId(), System.currentTimeMillis());
                break;
            case "set_commands":
                player.closeInventory();
                player.sendMessage(legacyColor("&eEnter commands for the kit (one per line):"));
                player.sendMessage(legacyColor("&7Use %player% as player placeholder"));
                player.sendMessage(legacyColor("&7Examples:"));
                player.sendMessage(legacyColor("&7- givekey normalkey 1 %player%"));
                player.sendMessage(legacyColor("&7- eco give %player% 1000"));
                player.sendMessage(legacyColor("&7Type 'done' when finished, 'cancel' to cancel"));
                player.sendMessage(legacyColor("&7Current commands:"));
                for (String cmd : session.getCommands()) {
                    player.sendMessage(legacyColor("&7- &f" + cmd));
                }
                session.setWaitingForInput("commands");
                lastChatInput.put(player.getUniqueId(), System.currentTimeMillis());
                break;
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (title.contains("Create Kit:") || title.contains("Edit Kit:")) {
            InventoryView view = event.getView();
            int guiSize = view.getTopInventory().getSize();
            int rows = guiSize / 9;
            for (int slot : event.getRawSlots()) {
                int row = slot / 9;
                int col = slot % 9;
                if (row >= 1 && row < rows - 1 && col >= 1 && col < 8) {
                    event.setCancelled(false);
                    return;
                }
            }
            boolean fromPlayerInventory = false;
            for (int slot : event.getRawSlots()) {
                if (slot >= 36) { fromPlayerInventory = true; break; }
            }
            if (fromPlayerInventory) { event.setCancelled(false); return; }
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID playerId = player.getUniqueId();
        String title = event.getView().getTitle();
        if (title.contains("Create Kit:") || title.contains("Edit Kit:")) {
            KitCreationSession session = creationSessions.get(playerId);
            if (session != null && !session.isWaitingForInput()) {
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (creationSessions.containsKey(playerId) && !session.isWaitingForInput()) {
                        creationSessions.remove(playerId);
                        lastChatInput.remove(playerId);
                        player.sendMessage(legacyColor("&cKit creation/edit timed out."));
                    }
                }, 6000L);
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!creationSessions.containsKey(playerId)) return;
        KitCreationSession session = creationSessions.get(playerId);
        if (session == null || !session.isWaitingForInput()) return;
        event.setCancelled(true);
        String input = event.getMessage().trim();
        Bukkit.getScheduler().runTask(this, () -> {
            Long lastInputTime = lastChatInput.get(playerId);
            if (lastInputTime != null && System.currentTimeMillis() - lastInputTime > 300000) {
                creationSessions.remove(playerId);
                lastChatInput.remove(playerId);
                player.sendMessage(legacyColor("&cInput timed out."));
                return;
            }
            if (input.equalsIgnoreCase("cancel")) {
                creationSessions.remove(playerId);
                lastChatInput.remove(playerId);
                player.sendMessage(legacyColor("&cCancelled."));
                return;
            }
            String waitingFor = session.getWaitingForInput();
            session.clearWaitingForInput();
            lastChatInput.remove(playerId);
            switch (waitingFor) {
                case "name":
                    session.setDisplayName(stripItalic(input));
                    player.sendMessage(legacyColor("&aDisplay name set to: &f" + input));
                    break;
                case "cooldown":
                    long cooldown = parseCooldown(input);
                    if (cooldown <= 0) {
                        player.sendMessage(legacyColor("&cInvalid cooldown! Use: 30s, 5m, 1h, 1d, 1w"));
                        session.setWaitingForInput("cooldown");
                        lastChatInput.put(playerId, System.currentTimeMillis());
                        return;
                    }
                    session.setCooldownSeconds(cooldown);
                    player.sendMessage(legacyColor("&aCooldown set to: &f" + formatTime(cooldown)));
                    break;
                case "permission":
                    String permission;
                    if (input.equalsIgnoreCase("default")) {
                        permission = getConfig().getString("creation.permission-format", "kits.kit.{kit_id}").replace("{kit_id}", session.getKitId());
                    } else {
                        permission = stripColor(input);
                    }
                    session.setPermission(permission);
                    player.sendMessage(legacyColor("&aPermission set to: &f" + permission));
                    break;
                case "commands":
                    if (input.equalsIgnoreCase("done")) {
                        player.sendMessage(legacyColor("&aCommands saved: &f" + session.getCommands().size()));
                    } else {
                        session.addCommand(input);
                        player.sendMessage(legacyColor("&aCommand added: &f" + input));
                        player.sendMessage(legacyColor("&eEnter next command or type 'done' when finished:"));
                        session.setWaitingForInput("commands");
                        lastChatInput.put(playerId, System.currentTimeMillis());
                        return;
                    }
                    break;
            }
            boolean isEdit = kitsConfig.contains(session.getKitId());
            openKitCreationGUI(player, session, isEdit);
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (updateAvailable && player.hasPermission("kits.admin") && getConfig().getBoolean("update-check.notify-on-join", true)) {
            player.sendMessage(legacyColor("&6[Kits] &eAn update is available!"));
        }
    }

    private ItemStack createButton(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(legacyColor(stripItalic(name))));
        if (lore.length > 0) {
            List<Component> lores = new ArrayList<>();
            for (String line : lore) lores.add(Component.text(legacyColor(stripItalic(line))));
            meta.lore(lores);
        }
        item.setItemMeta(meta);
        return item;
    }

    private void setButtonTag(ItemStack item, String buttonType) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(buttonKey, PersistentDataType.STRING, buttonType);
            item.setItemMeta(meta);
        }
    }

    private String getButtonTag(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(buttonKey, PersistentDataType.STRING);
    }

    private Map<String, Object> serializeItem(ItemStack item) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", item.getType().name());
        data.put("amount", item.getAmount());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                String displayName = LegacyComponentSerializer.legacySection().serialize(meta.displayName());
                data.put("name", stripItalic(displayName));
            }
            if (meta.hasLore()) {
                List<String> lore = new ArrayList<>();
                for (Component line : meta.lore()) {
                    String lineStr = LegacyComponentSerializer.legacySection().serialize(line);
                    lore.add(stripItalic(lineStr));
                }
                data.put("lore", lore);
            }
            if (meta.hasEnchants() && getConfig().getBoolean("creation.allow-enchantments", true)) {
                Map<String, Integer> enchants = new HashMap<>();
                for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) enchants.put(entry.getKey().getKey().getKey(), entry.getValue());
                data.put("enchants", enchants);
            }
            if (!meta.getItemFlags().isEmpty()) {
                List<String> flags = new ArrayList<>();
                for (ItemFlag flag : meta.getItemFlags()) flags.add(flag.name());
                data.put("flags", flags);
            }

            // Handle potions (using new API for 1.20.6+ with PotionType)
            if (meta instanceof PotionMeta) {
                PotionMeta potionMeta = (PotionMeta) meta;
                if (!potionMeta.getCustomEffects().isEmpty()) {
                    // Store custom effects
                    List<Map<String, Object>> potionEffects = new ArrayList<>();
                    for (PotionEffect effect : potionMeta.getCustomEffects()) {
                        Map<String, Object> effectData = new HashMap<>();
                        effectData.put("type", effect.getType().getKey().toString());
                        effectData.put("duration", effect.getDuration());
                        effectData.put("amplifier", effect.getAmplifier());
                        effectData.put("ambient", effect.isAmbient());
                        effectData.put("particles", effect.hasParticles());
                        effectData.put("icon", effect.hasIcon());
                        potionEffects.add(effectData);
                    }
                    data.put("potion_effects", potionEffects);
                }

                // Store base potion type if available (for vanilla potions)
                PotionType potionType = potionMeta.getBasePotionType();
                if (potionType != null) {
                    data.put("base_potion_type", potionType.name());
                }
            }
        }
        return data;
    }

    private ItemStack deserializeItem(Map<String, Object> data) {
        try {
            Material material = Material.valueOf(data.get("type").toString().toUpperCase());
            int amount = Integer.parseInt(data.getOrDefault("amount", "1").toString());
            ItemStack item = new ItemStack(material, amount);
            ItemMeta meta = item.getItemMeta();

            if (meta != null) {
                if (data.containsKey("name") && getConfig().getBoolean("creation.allow-custom-names", true)) {
                    String name = data.get("name").toString();
                    meta.displayName(LegacyComponentSerializer.legacySection().deserialize(stripItalic(name)));
                }
                if (data.containsKey("lore") && getConfig().getBoolean("creation.allow-custom-names", true)) {
                    List<Component> lore = new ArrayList<>();
                    for (Object line : (List<?>) data.get("lore")) {
                        String lineStr = line.toString();
                        lore.add(LegacyComponentSerializer.legacySection().deserialize(stripItalic(lineStr)));
                    }
                    meta.lore(lore);
                }
                if (data.containsKey("enchants") && getConfig().getBoolean("creation.allow-enchantments", true)) {
                    Map<?, ?> enchants = (Map<?, ?>) data.get("enchants");
                    for (Map.Entry<?, ?> entry : enchants.entrySet()) {
                        Enchantment enchant = Enchantment.getByKey(NamespacedKey.minecraft(entry.getKey().toString()));
                        if (enchant != null) meta.addEnchant(enchant, Integer.parseInt(entry.getValue().toString()), true);
                    }
                }
                if (data.containsKey("flags")) {
                    List<?> flags = (List<?>) data.get("flags");
                    for (Object flag : flags) {
                        try { meta.addItemFlags(ItemFlag.valueOf(flag.toString())); } catch (IllegalArgumentException ignored) {}
                    }
                }

                // Handle potions (using new API for 1.20.6+ with PotionType)
                if (data.containsKey("potion_effects") && meta instanceof PotionMeta) {
                    PotionMeta potionMeta = (PotionMeta) meta;
                    List<?> effectsData = (List<?>) data.get("potion_effects");
                    for (Object effectObj : effectsData) {
                        if (effectObj instanceof Map) {
                            Map<?, ?> effectMap = (Map<?, ?>) effectObj;
                            try {
                                String typeKey = effectMap.get("type").toString();
                                PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.fromString(typeKey));
                                if (type != null) {
                                    Object durationObj = effectMap.get("duration");
                                    int duration = durationObj != null ? Integer.parseInt(durationObj.toString()) : 6000;

                                    Object amplifierObj = effectMap.get("amplifier");
                                    int amplifier = amplifierObj != null ? Integer.parseInt(amplifierObj.toString()) : 0;

                                    Object ambientObj = effectMap.get("ambient");
                                    boolean ambient = ambientObj != null && Boolean.parseBoolean(ambientObj.toString());

                                    Object particlesObj = effectMap.get("particles");
                                    boolean particles = particlesObj == null || Boolean.parseBoolean(particlesObj.toString());

                                    Object iconObj = effectMap.get("icon");
                                    boolean icon = iconObj == null || Boolean.parseBoolean(iconObj.toString());

                                    PotionEffect effect = new PotionEffect(type, duration, amplifier, ambient, particles, icon);
                                    potionMeta.addCustomEffect(effect, true);
                                }
                            } catch (Exception e) {
                                getLogger().warning("Failed to deserialize potion effect: " + e.getMessage());
                            }
                        }
                    }
                }

                // Handle base potion type
                if (data.containsKey("base_potion_type") && meta instanceof PotionMeta) {
                    PotionMeta potionMeta = (PotionMeta) meta;
                    try {
                        String baseTypeStr = data.get("base_potion_type").toString();
                        PotionType baseType = PotionType.valueOf(baseTypeStr);
                        potionMeta.setBasePotionType(baseType);
                    } catch (Exception e) {
                        getLogger().warning("Failed to set base potion type: " + e.getMessage());
                    }
                }

                item.setItemMeta(meta);
            }
            return item;
        } catch (Exception e) {
            getLogger().warning("Failed to deserialize item: " + e.getMessage());
            return null;
        }
    }

    private long parseCooldown(String cooldownStr) {
        try {
            char unit = cooldownStr.charAt(cooldownStr.length() - 1);
            long value = Long.parseLong(cooldownStr.substring(0, cooldownStr.length() - 1));
            return switch (unit) {
                case 's' -> value;
                case 'm' -> value * 60;
                case 'h' -> value * 3600;
                case 'd' -> value * 86400;
                case 'w' -> value * 604800;
                default -> -1;
            };
        } catch (Exception e) {
            return -1;
        }
    }

    private String formatTime(long seconds) {
        if (seconds < 0) return "0s";
        if (seconds < 60) return seconds + "s";
        else if (seconds < 3600) return (seconds / 60) + "m";
        else if (seconds < 86400) return (seconds / 3600) + "h";
        else return (seconds / 86400) + "d";
    }

    private String formatDetailedTime(long seconds) {
        if (seconds <= 0) return "Now";
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (secs > 0) sb.append(secs).append("s");
        String result = sb.toString().trim();
        return result.isEmpty() ? "0s" : result;
    }

    // New method for formatting dates with AM/PM
    private String formatDateWithAMPM(long timestamp) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss a");
            return sdf.format(new Date(timestamp));
        } catch (Exception e) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
        }
    }

    // New method to remove italic formatting
    private String stripItalic(String text) {
        if (text == null) return "";
        // Remove §o and &o (italic) codes
        text = text.replace("§o", "").replace("&o", "");
        // Also remove any remaining italic formatting that might be embedded
        text = text.replace("§r", "").replace("&r", "");
        return text;
    }

    private long getLastUsed(String playerName, String kitId) {
        Map<String, Long> playerCooldowns = cooldowns.get(playerName.toLowerCase());
        return playerCooldowns != null ? playerCooldowns.getOrDefault(kitId, 0L) : 0L;
    }

    private void setLastUsed(String playerName, String kitId) {
        Map<String, Long> playerCooldowns = cooldowns.computeIfAbsent(playerName.toLowerCase(), k -> new HashMap<>());
        playerCooldowns.put(kitId, System.currentTimeMillis());
    }

    private void logUsage(Player player, String kitId, int itemsGiven, int commandsExecuted) {
        if (!getConfig().getBoolean("logging.enabled", true) || !getConfig().getBoolean("logging.log-types.claim", true)) return;
        try {
            File logsFolder = new File(getDataFolder(), getConfig().getString("logging.folder", "logs"));
            if (!logsFolder.exists()) logsFolder.mkdirs();
            String rotation = getConfig().getString("logging.rotation", "daily");
            SimpleDateFormat dateFormat = switch (rotation.toLowerCase()) {
                case "weekly" -> new SimpleDateFormat("yyyy-'week'-ww");
                case "monthly" -> new SimpleDateFormat("yyyy-MM");
                default -> new SimpleDateFormat("yyyy-MM-dd");
            };
            String fileName = "kit-claim-" + dateFormat.format(new Date()) + ".log";
            File logFile = new File(logsFolder, fileName);
            FileWriter writer = new FileWriter(logFile, true);
            String timestamp = formatDateWithAMPM(System.currentTimeMillis());
            String logLine = String.format("[%s] %s (%s) claimed kit: %s (items: %d, commands: %d)%n",
                    timestamp, player.getName(), player.getUniqueId(), kitId, itemsGiven, commandsExecuted);
            writer.write(logLine);
            writer.close();
            int maxLogFiles = getConfig().getInt("logging.max-log-files", 30);
            if (maxLogFiles > 0) {
                File[] logFiles = logsFolder.listFiles((dir, name) -> name.startsWith("kit-claim-") && name.endsWith(".log"));
                if (logFiles != null && logFiles.length > maxLogFiles) {
                    Arrays.sort(logFiles, Comparator.comparingLong(File::lastModified));
                    for (int i = 0; i < logFiles.length - maxLogFiles; i++) logFiles[i].delete();
                }
            }
        } catch (IOException e) {
            getLogger().warning("Failed to log kit usage: " + e.getMessage());
        }
    }

    private void logAction(String playerName, String action) {
        if (!getConfig().getBoolean("logging.enabled", true)) return;
        try {
            File logsFolder = new File(getDataFolder(), getConfig().getString("logging.folder", "logs"));
            if (!logsFolder.exists()) logsFolder.mkdirs();
            File logFile = new File(logsFolder, "admin-actions.log");
            FileWriter writer = new FileWriter(logFile, true);
            String timestamp = formatDateWithAMPM(System.currentTimeMillis());
            String logLine = String.format("[%s] %s: %s%n", timestamp, playerName, action);
            writer.write(logLine);
            writer.close();
        } catch (IOException e) {
            getLogger().warning("Failed to log admin action: " + e.getMessage());
        }
    }

    private void logError(String error) {
        if (!getConfig().getBoolean("logging.enabled", true) || !getConfig().getBoolean("logging.log-types.error", true)) return;
        try {
            File logsFolder = new File(getDataFolder(), getConfig().getString("logging.folder", "logs"));
            if (!logsFolder.exists()) logsFolder.mkdirs();
            File logFile = new File(logsFolder, "errors.log");
            FileWriter writer = new FileWriter(logFile, true);
            String timestamp = formatDateWithAMPM(System.currentTimeMillis());
            String logLine = String.format("[%s] ERROR: %s%n", timestamp, error);
            writer.write(logLine);
            writer.close();
        } catch (IOException e) {
            getLogger().warning("Failed to log error: " + e.getMessage());
        }
    }

    private String getMessage(String key) {
        String message = messagesConfig.getString("messages." + key, getConfig().getString("messages." + key, getDefaultMessage(key)));
        String prefix = messagesConfig.getString("messages.prefix", getConfig().getString("messages.prefix", "&6&lKits &8» &7"));
        return legacyColor(prefix + message);
    }

    private String getDefaultMessage(String key) {
        switch (key) {
            case "error-player-only": return "&cThis command requires a player!";
            case "error-no-permission": return "&cYou don't have permission!";
            case "error-kit-not-found": return "&cKit not found!";
            case "error-kit-exists": return "&cKit already exists!";
            case "error-no-items": return "&cThis kit has no items!";
            case "error-inventory-full": return "&eYour inventory was full! Some items were dropped.";
            case "no-kits-available": return "&7No kits available.";
            case "claim-success": return "&aYou have claimed the {kit_name} kit!";
            case "claim-cooldown": return "&cThis kit is on cooldown for {time_left}!";
            case "claim-no-permission": return "&cYou don't have permission to claim this kit!";
            case "create-success": return "&aKit {kit_name} has been created successfully!";
            case "edit-success": return "&aKit {kit_name} has been updated!";
            case "delete-success": return "&aKit {kit_name} has been deleted!";
            case "reload-success": return "&aConfiguration has been reloaded!";
            default: return "&cMessage not found: " + key;
        }
    }

    private String legacyColor(String text) {
        if (text == null) return "";
        return text.replace('&', '§');
    }

    private String stripColor(String text) {
        if (text == null) return "";
        text = text.replaceAll("§[0-9a-fk-or]", "");
        text = text.replaceAll("&[0-9a-fk-or]", "");
        return text;
    }

    private static class KitCreationSession {
        private final String kitId;
        private String displayName;
        private long cooldownSeconds;
        private String permission;
        private List<String> commands;
        private String waitingForInput;

        public KitCreationSession(String kitId, String displayName, long cooldownSeconds) {
            this(kitId, displayName, cooldownSeconds, "kits.kit." + kitId);
        }

        public KitCreationSession(String kitId, String displayName, long cooldownSeconds, String permission) {
            this.kitId = kitId;
            this.displayName = displayName;
            this.cooldownSeconds = cooldownSeconds;
            this.permission = permission;
            this.commands = new ArrayList<>();
            this.waitingForInput = null;
        }

        public String getKitId() { return kitId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public long getCooldownSeconds() { return cooldownSeconds; }
        public void setCooldownSeconds(long cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
        public String getPermission() { return permission; }
        public void setPermission(String permission) { this.permission = permission; }
        public List<String> getCommands() { return commands; }
        public void setCommands(List<String> commands) { this.commands = commands; }
        public void addCommand(String command) { this.commands.add(command); }
        public String getWaitingForInput() { return waitingForInput; }
        public void setWaitingForInput(String waitingForInput) { this.waitingForInput = waitingForInput; }
        public void clearWaitingForInput() { this.waitingForInput = null; }
        public boolean isWaitingForInput() { return waitingForInput != null; }
    }
}
