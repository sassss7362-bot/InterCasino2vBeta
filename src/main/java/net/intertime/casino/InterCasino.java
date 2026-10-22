package net.intertime.casino;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class InterCasino extends JavaPlugin {

    private final Map<UUID, Long> coins = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;
    private Casino casino;
    private VaultBridge vault;
    private boolean vaultTried;

    public long rateDiamond;
    public long rateNetherite;
    public long startBalance;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rateDiamond = getConfig().getLong("exchange.diamond", 100);
        rateNetherite = getConfig().getLong("exchange.netherite", 1000);
        startBalance = getConfig().getLong("start-balance", 1000);

        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        if (data.isConfigurationSection("coins")) {
            for (String k : data.getConfigurationSection("coins").getKeys(false)) {
                try {
                    coins.put(UUID.fromString(k), data.getLong("coins." + k));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        casino = new Casino(this);
        getServer().getPluginManager().registerEvents(casino, this);
        getServer().getScheduler().runTaskTimer(this, this::saveData, 6000L, 6000L);
        getLogger().info("InterTime Casino увімкнено!");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    public void saveData() {
        data.set("coins", null);
        for (Map.Entry<UUID, Long> en : coins.entrySet()) {
            data.set("coins." + en.getKey(), en.getValue());
        }
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Не вдалось зберегти data.yml: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- економіка

    /** Економіка сервера (Vault/Essentials). null - працюють внутрішні монети. */
    private VaultBridge vault() {
        if (!vaultTried) {
            vaultTried = true;
            String mode = getConfig().getString("economy", "auto").toLowerCase();
            if (!mode.equals("internal")) {
                vault = VaultBridge.create();
                getLogger().info(vault != null
                        ? "Економіка казино: сервер (Vault)"
                        : "Vault не знайдено - використовуються внутрішні монети");
            }
        }
        return vault;
    }

    public long coins(Player p) {
        VaultBridge v = vault();
        if (v != null) return v.balance(p);
        return coins.computeIfAbsent(p.getUniqueId(), k -> startBalance);
    }

    public long balance(Player p, Currency c) {
        if (c == Currency.COINS) return coins(p);
        long n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && it.getType() == c.icon) n += it.getAmount();
        }
        return n;
    }

    /** Забирає суму. Повертає false, якщо коштів не вистачає. */
    public boolean take(Player p, Currency c, long amount) {
        if (amount <= 0) return true;
        if (balance(p, c) < amount) return false;
        if (c == Currency.COINS) {
            VaultBridge v = vault();
            if (v != null) return v.withdraw(p, amount);
            coins.put(p.getUniqueId(), coins(p) - amount);
            return true;
        }
        long left = amount;
        ItemStack[] cont = p.getInventory().getStorageContents();
        for (int i = 0; i < cont.length && left > 0; i++) {
            ItemStack it = cont[i];
            if (it == null || it.getType() != c.icon) continue;
            int rem = (int) Math.min(left, it.getAmount());
            left -= rem;
            if (rem >= it.getAmount()) cont[i] = null;
            else it.setAmount(it.getAmount() - rem);
        }
        p.getInventory().setStorageContents(cont);
        return true;
    }

    public void give(Player p, Currency c, long amount) {
        if (amount <= 0) return;
        if (c == Currency.COINS) {
            VaultBridge v = vault();
            if (v != null) {
                v.deposit(p, amount);
                return;
            }
            coins.put(p.getUniqueId(), coins(p) + amount);
            return;
        }
        long left = amount;
        while (left > 0) {
            int stack = (int) Math.min(64, left);
            left -= stack;
            Map<Integer, ItemStack> over = p.getInventory().addItem(new ItemStack(c.icon, stack));
            for (ItemStack rest : over.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
        }
    }

    public void addCoins(UUID id, long amount) {
        VaultBridge v = vault();
        if (v != null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            if (amount >= 0) v.deposit(op, amount);
            else v.withdraw(op, -amount);
            return;
        }
        long cur = coins.getOrDefault(id, startBalance);
        coins.put(id, Math.max(0, cur + amount));
    }

    // ---------------------------------------------------------------- команди

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length > 0) {
            switch (a[0].toLowerCase()) {
                case "rules", "book", "правила" -> {
                    if (s instanceof Player p) casino.openRules(p);
                    return true;
                }
                case "balance", "bal", "баланс" -> {
                    if (s instanceof Player p) {
                        Casino.msg(p, "<gray>Баланс: <gold>" + Casino.fmt(Currency.COINS, coins(p)));
                    }
                    return true;
                }
                case "give", "take" -> {
                    if (!s.hasPermission("intertime.casino.admin")) {
                        s.sendMessage("Немає прав.");
                        return true;
                    }
                    if (a.length < 3) {
                        s.sendMessage("Використання: /casino " + a[0] + " <гравець> <сума>");
                        return true;
                    }
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t == null) {
                        s.sendMessage("Гравця не знайдено.");
                        return true;
                    }
                    try {
                        long n = Long.parseLong(a[2]);
                        addCoins(t.getUniqueId(), a[0].equalsIgnoreCase("give") ? n : -n);
                        s.sendMessage("Готово. Баланс " + t.getName() + ": " + coins(t));
                    } catch (NumberFormatException ex) {
                        s.sendMessage("Сума має бути числом.");
                    }
                    return true;
                }
                default -> {
                }
            }
        }
        if (!(s instanceof Player p)) {
            s.sendMessage("Тільки для гравців.");
            return true;
        }
        casino.openMain(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        if (a.length == 1) {
            List<String> l = new ArrayList<>(List.of("rules", "balance"));
            if (s.hasPermission("intertime.casino.admin")) l.addAll(List.of("give", "take"));
            return l.stream().filter(x -> x.startsWith(a[0].toLowerCase())).toList();
        }
        if (a.length == 2 && s.hasPermission("intertime.casino.admin")
                && (a[0].equalsIgnoreCase("give") || a[0].equalsIgnoreCase("take"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
