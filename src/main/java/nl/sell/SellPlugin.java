package nl.sell;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SellPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Items die nooit verkocht kunnen worden (niet verkrijgbaar of met data). */
    private static final Set<String> BLOCKED = Set.of(
            "AIR", "CAVE_AIR", "VOID_AIR", "BEDROCK", "BARRIER", "LIGHT", "STRUCTURE_VOID",
            "STRUCTURE_BLOCK", "JIGSAW", "DEBUG_STICK", "KNOWLEDGE_BOOK", "SPAWNER", "TRIAL_SPAWNER",
            "VAULT", "REINFORCED_DEEPSLATE", "END_PORTAL_FRAME", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK",
            "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART", "PLAYER_HEAD", "POTION", "SPLASH_POTION",
            "LINGERING_POTION", "TIPPED_ARROW", "ENCHANTED_BOOK", "FILLED_MAP", "WRITTEN_BOOK",
            "PETRIFIED_OAK_SLAB", "TEST_BLOCK", "TEST_INSTANCE_BLOCK", "FARMLAND");

    // prijzen worden ook vanuit de netwerk-thread gelezen, dus volatile + nooit aanpassen na het bouwen
    private volatile Map<Material, Double> prices = new EnumMap<>(Material.class);
    private volatile double priceScale = 1.0;

    private final Map<UUID, Double> soldTotals = new ConcurrentHashMap<>();
    private final Map<UUID, Double> multiplierCache = new ConcurrentHashMap<>();
    private final Set<UUID> creative = ConcurrentHashMap.newKeySet();
    private volatile boolean dirty = false;

    private List<double[]> levels = new ArrayList<>();
    private File dataFile;
    private Economy economy;
    private Object worthLore;

    private static class SellHolder implements InventoryHolder {
        private Inventory inv;
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    // ------------------------------------------------------------------ start / stop

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("Geen Vault economy gevonden (installeer bv. EssentialsX). Plugin uitgeschakeld.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = rsp.getProvider();

        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        loadLevels();
        loadPrices();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("sell") != null) getCommand("sell").setExecutor(this);

        setupWorthLore();

        for (Player p : Bukkit.getOnlinePlayers()) refreshPlayer(p);
        // multiplier-cache verversen (permissies kunnen wijzigen) en data opslaan
        getServer().getScheduler().runTaskTimer(this, this::refreshAll, 200L, 600L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> { if (dirty) saveData(); }, 1200L, 6000L);
    }

    @Override
    public void onDisable() {
        if (worthLore != null) {
            try { WorthLore.unregister(worthLore); } catch (Throwable ignored) { }
        }
        if (dataFile != null) saveData();
    }

    private void setupWorthLore() {
        if (!getConfig().getBoolean("worth-lore.enabled", true)) return;
        if (!getServer().getPluginManager().isPluginEnabled("packetevents")) {
            getLogger().warning("PacketEvents niet gevonden: de waarde in de item-tooltip is uitgeschakeld.");
            return;
        }
        try {
            worthLore = WorthLore.register(this);
            getLogger().info("Worth-tooltip actief (via PacketEvents).");
        } catch (Throwable t) {
            getLogger().warning("Kon worth-tooltip niet starten: " + t);
        }
    }

    // ------------------------------------------------------------------ data

    private void loadData() {
        soldTotals.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection s = y.getConfigurationSection("sold");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try { soldTotals.put(UUID.fromString(k), s.getDouble(k)); } catch (IllegalArgumentException ignored) { }
        }
    }

    private synchronized void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : soldTotals.entrySet()) y.set("sold." + e.getKey(), e.getValue());
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
            dirty = false;
        } catch (IOException e) {
            getLogger().warning("Kon data.yml niet opslaan: " + e.getMessage());
        }
    }

    private void loadLevels() {
        levels = new ArrayList<>();
        ConfigurationSection s = getConfig().getConfigurationSection("levels");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                try { levels.add(new double[]{Double.parseDouble(k), s.getDouble(k)}); } catch (NumberFormatException ignored) { }
            }
        }
        if (levels.isEmpty()) levels.add(new double[]{0, 1.0});
        levels.sort(Comparator.comparingDouble(a -> a[0]));
    }

    private static boolean isSellableType(Material m) {
        String n = m.name();
        return m.isItem() && !m.isAir()
                && !n.startsWith("LEGACY_") && !n.startsWith("INFESTED_")
                && !n.endsWith("_SPAWN_EGG") && !BLOCKED.contains(n);
    }

    private void loadPrices() {
        File file = new File(getDataFolder(), "prices.yml");
        if (!file.exists()) saveResource("prices.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("prices");
        if (sec == null) sec = yml.createSection("prices");

        Map<Material, Double> map = new EnumMap<>(Material.class);
        boolean changed = false;
        int removed = 0;
        for (String key : new ArrayList<>(sec.getKeys(false))) {
            Material m = Material.getMaterial(key);
            if (m == null || !isSellableType(m)) {
                sec.set(key, null);
                removed++;
                changed = true;
                continue;
            }
            map.put(m, sec.getDouble(key));
        }

        // Alle ontbrekende items automatisch toevoegen zodat echt elk item erin staat
        double fallback = getConfig().getDouble("fallback-price", 1.0);
        int added = 0;
        for (Material m : Material.values()) {
            if (!isSellableType(m) || map.containsKey(m)) continue;
            sec.set(m.name(), fallback);
            map.put(m, fallback);
            added++;
            changed = true;
        }

        if (changed) {
            try {
                yml.save(file);
            } catch (IOException e) {
                getLogger().warning("Kon prices.yml niet opslaan: " + e.getMessage());
            }
        }
        priceScale = Math.max(0, getConfig().getDouble("price-scale", 1.0));
        prices = map;
        getLogger().info(map.size() + " prijzen geladen (" + added
                + " automatisch toegevoegd met fallback-prijs, " + removed + " onbekende verwijderd).");
    }

    // ------------------------------------------------------------------ multiplier

    private double progressMultiplier(double sold) {
        double m = 1.0;
        for (double[] l : levels) if (sold >= l[0]) m = l[1];
        return Math.min(m, getConfig().getDouble("max-multiplier", 3.0));
    }

    /** {drempel, multiplier} van het volgende level, of null bij max. */
    private double[] nextLevel(double sold) {
        double current = progressMultiplier(sold);
        double cap = getConfig().getDouble("max-multiplier", 3.0);
        for (double[] l : levels) {
            if (l[0] > sold && Math.min(l[1], cap) > current) return new double[]{l[0], Math.min(l[1], cap)};
        }
        return null;
    }

    private double rankBonus(Player p) {
        double best = 0;
        ConfigurationSection s = getConfig().getConfigurationSection("multipliers");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                if (p.hasPermission("sell.multiplier." + k)) best = Math.max(best, s.getDouble(k, 0));
            }
        }
        return best;
    }

    private double globalMultiplier() {
        return Math.max(0, getConfig().getDouble("global-multiplier", 1.0));
    }

    private double sold(UUID id) {
        return soldTotals.getOrDefault(id, 0.0);
    }

    private double totalMultiplier(Player p) {
        return (progressMultiplier(sold(p.getUniqueId())) + rankBonus(p)) * globalMultiplier();
    }

    private void refreshPlayer(Player p) {
        multiplierCache.put(p.getUniqueId(), totalMultiplier(p));
        if (p.getGameMode() == GameMode.CREATIVE) creative.add(p.getUniqueId());
        else creative.remove(p.getUniqueId());
    }

    private void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) refreshPlayer(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        refreshPlayer(e.getPlayer());
        Bukkit.getScheduler().runTask(this, () -> e.getPlayer().updateInventory());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        multiplierCache.remove(e.getPlayer().getUniqueId());
        creative.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (e.getNewGameMode() == GameMode.CREATIVE) creative.add(id);
        else creative.remove(id);
    }

    // ------------------------------------------------------------------ prijzen

    /** Prijs per stuk (zonder multiplier), of -1 als het item niet verkocht kan worden. */
    private double unitPrice(ItemStack item, boolean full) {
        Double base = prices.get(item.getType());
        if (base == null || base <= 0) return -1;

        double factor = 1.0;
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants()) return -1;
            if (meta instanceof PotionMeta || meta instanceof EnchantmentStorageMeta
                    || meta instanceof BookMeta || meta instanceof MapMeta || meta instanceof SkullMeta) return -1;
            if (full) {
                if (meta instanceof BundleMeta bm && bm.hasItems()) return -1;
                if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()
                        && bsm.getBlockState() instanceof Container c && !c.getInventory().isEmpty()) return -1;
            }
            int max = item.getType().getMaxDurability();
            if (max > 0 && meta instanceof Damageable d) {
                factor = Math.max(0.05, 1.0 - (double) d.getDamage() / max);
            }
        }
        return base * priceScale * factor;
    }

    // ---- gebruikt door WorthLore (netwerk-thread, dus alleen thread-safe dingen) ----

    boolean shouldShowLore(UUID id) {
        return multiplierCache.containsKey(id) && !creative.contains(id);
    }

    double displayUnitPrice(ItemStack item, UUID id) {
        double unit = unitPrice(item, false);
        if (unit < 0) return -1;
        return unit * multiplierCache.getOrDefault(id, 1.0);
    }

    Component loreLine(String key, double unit, double stack) {
        String s = getConfig().getString("worth-lore." + key, "");
        return MM.deserialize(s.replace("{price}", fmt(unit)).replace("{stack}", fmt(stack)));
    }

    // ------------------------------------------------------------------ command

    private Component msg(String key, String... replacements) {
        String s = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) s = s.replace(replacements[i], replacements[i + 1]);
        return MM.deserialize(s);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        if (sub.equals("reload")) {
            if (!sender.hasPermission("sell.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            reloadConfig();
            loadLevels();
            loadPrices();
            refreshAll();
            sender.sendMessage(msg("reloaded"));
            return true;
        }
        if (sub.equals("global")) {
            if (!sender.hasPermission("sell.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            try {
                double v = Double.parseDouble(args[1]);
                getConfig().set("global-multiplier", Math.max(0, v));
                saveConfig();
                refreshAll();
                sender.sendMessage(msg("global-set", "{multiplier}", fmt(v)));
            } catch (Exception e) {
                sender.sendMessage(msg("usage-global"));
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Alleen spelers kunnen dit gebruiken.");
            return true;
        }
        if (!player.hasPermission("sell.use")) { player.sendMessage(msg("no-permission")); return true; }

        if (sub.equals("multiplier")) {
            double sold = sold(player.getUniqueId());
            player.sendMessage(msg("multiplier",
                    "{multiplier}", fmt(totalMultiplier(player)),
                    "{progress}", fmt(progressMultiplier(sold)),
                    "{bonus}", fmt(rankBonus(player)),
                    "{global}", fmt(globalMultiplier())));
            double[] next = nextLevel(sold);
            if (next == null) {
                player.sendMessage(msg("progress-max"));
            } else {
                player.sendMessage(msg("progress",
                        "{sold}", fmt(sold), "{next}", fmt(next[1]), "{needed}", fmt(next[0])));
            }
            return true;
        }
        if (sub.equals("worth")) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) { player.sendMessage(msg("hold-item")); return true; }
            double unit = unitPrice(hand, true);
            if (unit < 0) { player.sendMessage(msg("not-sellable")); return true; }
            double mult = totalMultiplier(player);
            player.sendMessage(msg("worth",
                    "{item}", hand.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    "{price}", fmt(unit * mult),
                    "{multiplier}", fmt(mult),
                    "{stack}", fmt(unit * mult * hand.getAmount())));
            return true;
        }

        int rows = Math.max(1, Math.min(6, getConfig().getInt("rows", 6)));
        SellHolder holder = new SellHolder();
        holder.inv = Bukkit.createInventory(holder, rows * 9, MM.deserialize(getConfig().getString("title", "Sell")));
        player.openInventory(holder.inv);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        List<String> out = new ArrayList<>(List.of("worth", "multiplier"));
        if (sender.hasPermission("sell.admin")) out.addAll(List.of("reload", "global"));
        out.removeIf(s -> !s.startsWith(args[0].toLowerCase(Locale.ROOT)));
        return out;
    }

    // ------------------------------------------------------------------ verkopen

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SellHolder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        double base = 0;
        int count = 0;
        for (ItemStack item : event.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            double unit = unitPrice(item, true);
            if (unit < 0) {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                leftover.values().forEach(i -> player.getWorld().dropItem(player.getLocation(), i));
                continue;
            }
            base += unit * item.getAmount();
            count += item.getAmount();
        }
        event.getInventory().clear();

        if (base <= 0) {
            player.updateInventory();
            return;
        }

        UUID id = player.getUniqueId();
        double before = progressMultiplier(sold(id));
        double mult = totalMultiplier(player);
        double money = Math.round(base * mult * 100.0) / 100.0;

        economy.depositPlayer(player, money);
        soldTotals.merge(id, base, Double::sum);   // level telt het bedrag zonder multiplier
        dirty = true;

        player.sendMessage(msg("sold",
                "{items}", String.valueOf(count),
                "{money}", String.format(Locale.US, "%,.2f", money),
                "{multiplier}", fmt(mult)));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);

        double after = progressMultiplier(sold(id));
        if (after > before) {
            player.sendMessage(msg("levelup", "{progress}", fmt(after)));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
        refreshPlayer(player);
        player.updateInventory();
    }

    private static String fmt(double d) {
        String s = String.format(Locale.US, "%,.2f", d);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }
}
