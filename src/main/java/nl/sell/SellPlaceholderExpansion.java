package nl.sell;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

import java.util.Locale;

/** PlaceholderAPI-expansion voor SellPlugin. Werkt direct in TAB via PAPI. */
public final class SellPlaceholderExpansion extends PlaceholderExpansion {

    private final SellPlugin plugin;

    public SellPlaceholderExpansion(SellPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "sell";
    }

    @Override
    public String getAuthor() {
        return "TobiasSMP";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) return "0";
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "balance", "money", "balance_raw" -> String.format(Locale.US, "%.2f", plugin.getBalance(player));
            case "balance_formatted", "money_formatted" -> String.format(Locale.US, "%,.2f", plugin.getBalance(player));
            case "balance_short", "money_short" -> shortMoney(plugin.getBalance(player));
            default -> null;
        };
    }

    private String shortMoney(double value) {
        double abs = Math.abs(value);
        if (abs >= 1_000_000_000) return String.format(Locale.US, "%.2fB", value / 1_000_000_000D);
        if (abs >= 1_000_000) return String.format(Locale.US, "%.2fM", value / 1_000_000D);
        if (abs >= 1_000) return String.format(Locale.US, "%.2fK", value / 1_000D);
        return String.format(Locale.US, "%.2f", value);
    }
}
