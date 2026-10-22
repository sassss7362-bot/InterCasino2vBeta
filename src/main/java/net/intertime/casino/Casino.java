package net.intertime.casino;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.IntConsumer;

public class Casino implements Listener {

    static final String PREFIX = "<gradient:#00e5ff:#7c4dff><bold>ɪɴᴛᴇʀᴛɪᴍᴇ</bold></gradient> <dark_gray>» ";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    // ---- Рулетка блоків: вугілля, мідь, залізо, золото, алмаз, смарагд
    static final Material[] R_MAT = {Material.COAL_BLOCK, Material.COPPER_BLOCK, Material.IRON_BLOCK,
            Material.GOLD_BLOCK, Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK};
    static final String[] R_NAME = {"Вугілля", "Мідь", "Залізо", "Золото", "Алмаз", "Смарагд"};
    static final String[] R_COLOR = {"<dark_gray>", "<#e07b53>", "<white>", "<yellow>", "<aqua>", "<green>"};
    static final double[] R_MULT_DEF = {0, 0.75, 1, 1.5, 2, 3};
    static final int[] R_W_DEF = {25, 22, 20, 16, 12, 5};
    static final Material[] R_PANE = {Material.GRAY_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE,
            Material.WHITE_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE,
            Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE};
    // множники та ваги (шанси) рулетки - беруться з config.yml
    private final double[] rMult = R_MULT_DEF.clone();
    private final int[] rW = R_W_DEF.clone();

    // ---- Слоти
    static final int[] S_W = {28, 24, 20, 14, 9, 5};
    static final double[] S_MULT = {3, 5, 8, 15, 40, 100};
    static final double S_PAIR = 1.3;

    // ---- Шахта: множник після N безпечних клітинок (2 бомби з 9)
    static final double[] MINES = {1.15, 1.5, 2.1, 3.2, 5.0, 10.0, 30.0};
    static final int[] GRID = {3, 4, 5, 12, 13, 14, 21, 22, 23};

    static final double COIN_MULT = 1.9;
    static final double DICE_MULT = 5.5;
    static final double CUPS_MULT = 2.7;

    enum Type {MAIN, BET, ROULETTE, SLOTS, DICE, COIN, CUPS, MINES}

    enum Game {
        ROULETTE("Рулетка блоків", Material.EMERALD_BLOCK, 11,
                "<gray>Вугілля, мідь, залізо, золото,", "<gray>алмаз або смарагд!", "<green>Множники від x0 до x3"),
        COIN("Орел і Решка", Material.GOLD_INGOT, 13,
                "<gray>Вгадай сторону монети", "<green>Виграш x1.9"),
        DICE("Кубик", Material.QUARTZ_BLOCK, 15,
                "<gray>Обери число від 1 до 6", "<green>Виграш x5.5"),
        CUPS("Три скрині", Material.CHEST, 29,
                "<gray>В одній з трьох скринь приз", "<green>Виграш x2.7"),
        SLOTS("Слоти", Material.NETHER_STAR, 31,
                "<gray>Збери 3 однакових блоки", "<green>Джекпот до x100"),
        MINES("Шахта", Material.TNT, 33,
                "<gray>9 клітинок і 2 бомби. Забирай", "<gray>виграш вчасно!", "<green>До x30");

        final String title;
        final Material icon;
        final int slot;
        final String[] desc;

        Game(String title, Material icon, int slot, String... desc) {
            this.title = title;
            this.icon = icon;
            this.slot = slot;
            this.desc = desc;
        }
    }

    static class Menu implements InventoryHolder {
        final Type type;
        final Game game;
        Currency cur = Currency.COINS;
        long bet;
        long custom; // своя ставка (кнопка +10 / Shift +100 000)
        boolean settled = true; // false = ставка ще "висить" (повернемо при закритті)
        boolean done;
        boolean busy;
        int picks;
        final boolean[] bomb = new boolean[9];
        final boolean[] open = new boolean[9];
        final Inventory inv;

        Menu(Type type, Game game, int size, Component title) {
            this.type = type;
            this.game = game;
            this.inv = Bukkit.createInventory(this, size, title);
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final InterCasino plugin;
    private final Random rnd = new Random();

    public Casino(InterCasino plugin) {
        this.plugin = plugin;
        loadRoulette();
    }

    private void loadRoulette() {
        List<Double> m = plugin.getConfig().getDoubleList("roulette.multipliers");
        List<Integer> w = plugin.getConfig().getIntegerList("roulette.weights");
        if (m.size() == 6 && w.size() == 6 && w.stream().allMatch(v -> v >= 0)
                && w.stream().mapToInt(Integer::intValue).sum() > 0) {
            for (int i = 0; i < 6; i++) {
                rMult[i] = m.get(i);
                rW[i] = w.get(i);
            }
        }
    }

    // ================================================================ утиліти

    static Component c(String s) {
        return MM.deserialize("<!italic>" + s);
    }

    static void msg(Player p, String s) {
        p.sendMessage(c(PREFIX + s));
    }

    static String fmt(long n) {
        return String.format(Locale.US, "%,d", n).replace(",", " ");
    }

    static String fmt(Currency cur, long n) {
        return fmt(n) + " " + cur.suffix;
    }

    static String x(double d) {
        return "x" + (d == (long) d ? String.valueOf((long) d) : String.valueOf(d));
    }

    static long calc(long bet, double mult) {
        return (long) Math.floor(bet * mult + 1e-9);
    }

    static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(c(name));
        if (lore.length > 0) im.lore(Arrays.stream(lore).map(Casino::c).toList());
        it.setItemMeta(im);
        return it;
    }

    static ItemStack glow(ItemStack it) {
        ItemMeta im = it.getItemMeta();
        im.setEnchantmentGlintOverride(true);
        it.setItemMeta(im);
        return it;
    }

    static Component title(String s) {
        return MM.deserialize("<gradient:#00e5ff:#7c4dff><bold>ɪɴᴛᴇʀᴛɪᴍᴇ</bold></gradient> <dark_gray>• <white>" + s);
    }

    private void play(Player p, Sound s, float vol, float pitch) {
        p.playSound(p.getLocation(), s, vol, pitch);
    }

    private void tick(Player p) {
        play(p, Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
    }

    private int pick(int[] w) {
        int t = 0;
        for (int v : w) t += v;
        int r = rnd.nextInt(t);
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r < 0) return i;
        }
        return w.length - 1;
    }

    private void fill(Inventory inv) {
        ItemStack edge = item(Material.CYAN_STAINED_GLASS_PANE, " ");
        ItemStack dark = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            inv.setItem(i, (i < 9 || i >= size - 9) ? edge : dark);
        }
    }

    private Menu menu(Type t, Game g, int size, String name) {
        Menu m = new Menu(t, g, size, title(name));
        fill(m.inv);
        return m;
    }

    /** Анімація з поступовим сповільненням. */
    private void anim(int steps, IntConsumer frame, Runnable end) {
        anim(steps, 2, 4.0, frame, end);
    }

    private void anim(int steps, long base, double k, IntConsumer frame, Runnable end) {
        animStep(0, steps, base, k, frame, end);
    }

    private void animStep(int i, int steps, long base, double k, IntConsumer frame, Runnable end) {
        if (i >= steps) {
            end.run();
            return;
        }
        frame.accept(i);
        long d = base + (long) (i * i / (steps * k));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> animStep(i + 1, steps, base, k, frame, end), d);
    }

    // ================================================================ головне меню

    public void openMain(Player p) {
        Menu m = menu(Type.MAIN, null, 45, "Казино");
        fillMain(m.inv, p);
        p.openInventory(m.inv);
        play(p, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.5f);
    }

    private void fillMain(Inventory inv, Player p) {
        inv.setItem(4, item(Material.NETHER_STAR, "<gradient:#00e5ff:#7c4dff><bold>ɪɴᴛᴇʀᴛɪᴍᴇ ᴄᴀꜱɪɴᴏ",
                "<gray>Обери гру та удачі!"));
        for (Game g : Game.values()) {
            String[] lore = new String[g.desc.length + 2];
            System.arraycopy(g.desc, 0, lore, 0, g.desc.length);
            lore[g.desc.length] = " ";
            lore[g.desc.length + 1] = "<yellow>▶ Натисни щоб грати";
            inv.setItem(g.slot, item(g.icon, "<aqua><bold>" + g.title, lore));
        }
        inv.setItem(38, item(Material.GOLD_INGOT, "<gold><bold>Твій баланс",
                "<gray>Монети: <gold>" + fmt(Currency.COINS, plugin.coins(p)),
                "<gray>Алмази: <aqua>" + fmt(Currency.DIAMOND, plugin.balance(p, Currency.DIAMOND)),
                "<gray>Незерит: <dark_purple>" + fmt(Currency.NETHERITE, plugin.balance(p, Currency.NETHERITE))));
        inv.setItem(40, item(Material.BOOK, "<gold><bold>Правила казино",
                "<gray>Натисни щоб прочитати книгу", "<dark_gray>Команда: /casino rules"));
        inv.setItem(42, item(Material.EMERALD, "<green><bold>Обмін монет",
                "<gray>ЛКМ: алмази -> монети (" + plugin.rateDiamond + " за 1)",
                "<gray>ПКМ: незерит -> монети (" + plugin.rateNetherite + " за 1)",
                "<gray>Shift+ЛКМ: монети -> алмази",
                "<gray>Shift+ПКМ: монети -> незерит"));
    }

    private String roulettePage() {
        String[] col = {"<dark_gray>", "<#8b4513>", "<black>", "<gold>", "<dark_aqua>", "<dark_green>"};
        StringBuilder sb = new StringBuilder("<dark_aqua><bold>Рулетка блоків</bold>\n\n");
        for (int i = 0; i < 6; i++) {
            sb.append(col[i]).append(R_NAME[i]).append(" ").append(x(rMult[i])).append("\n");
        }
        if (rMult[0] == 0) sb.append("\n<black>Випало вугілля - ставка згорає.");
        return sb.toString();
    }

    // ================================================================ книга з правилами

    public void openRules(Player p) {
        Book book = Book.book(
                c("<gold>Правила казино"),
                c("InterTime"),
                c("<dark_aqua><bold>InterTime Казино</bold>\n\n<black>Ласкаво просимо! Тут 6 міні-ігор.\n\nСтавити можна <gold>монетами</gold>, <dark_aqua>алмазами</dark_aqua> або <dark_purple>незеритом</dark_purple>."),
                c(roulettePage()),
                c("<dark_aqua><bold>Прості ігри</bold>\n\n<black>Орел і Решка: <dark_green>x1.9</dark_green>\nКубик (вгадай число): <dark_green>x5.5</dark_green>\nТри скрині: <dark_green>x2.7</dark_green>"),
                c("<dark_aqua><bold>Слоти</bold>\n\n<black>3 однакових блоки:\nВугілля x3, Мідь x5,\nЗалізо x8, Золото x15,\nАлмаз x40, Смарагд x100\n\nПара: <dark_green>x1.3"),
                c("<dark_aqua><bold>Шахта</bold>\n\n<black>9 клітинок, 2 бомби.\nВідкривай клітинки - множник росте: x1.15, x1.5, x2.1, x3.2, x5, x10, x30.\n\nНатисни смарагд щоб забрати виграш!"),
                c("<dark_aqua><bold>Обмін та команди</bold>\n\n<black>/casino - меню\n/casino rules - правила\n/casino balance - баланс\n\nОбмін алмазів та незериту на монети є в головному меню.\n\n<red>Грай відповідально!")
        );
        p.openBook(book);
    }

    // ================================================================ меню ставок

    private void openBet(Player p, Game g) {
        Menu m = menu(Type.BET, g, 36, g.title);
        drawBet(m, p);
        p.openInventory(m.inv);
    }

    private void drawBet(Menu m, Player p) {
        Inventory inv = m.inv;
        fill(inv);
        inv.setItem(4, item(m.game.icon, "<aqua><bold>" + m.game.title, m.game.desc));
        Currency[] cs = Currency.values();
        int[] cslots = {11, 13, 15};
        for (int i = 0; i < 3; i++) {
            Currency cu = cs[i];
            boolean sel = cu == m.cur;
            ItemStack it = item(cu.icon, (sel ? "<green>✔ " : "<gray>") + cu.title,
                    "<gray>Баланс: <white>" + fmt(cu, plugin.balance(p, cu)),
                    sel ? "<green>Обрано" : "<yellow>Натисни щоб обрати");
            if (sel) glow(it);
            inv.setItem(cslots[i], it);
        }
        long bal = plugin.balance(p, m.cur);
        for (int i = 0; i < 7; i++) {
            long b = m.cur.bets[i];
            boolean ok = bal >= b;
            ItemStack it = item(m.cur.icon, (ok ? "<green>" : "<red>") + "Ставка: " + fmt(m.cur, b),
                    ok ? "<yellow>Натисни щоб грати" : "<red>Недостатньо коштів");
            it.setAmount((int) (m.cur == Currency.COINS ? 1 : Math.min(64, Math.max(1, b))));
            inv.setItem(19 + i, it);
        }
        inv.setItem(31, item(Material.ARROW, "<red>← Назад"));

        long step = m.cur == Currency.COINS ? 10 : 1;
        long big = m.cur == Currency.COINS ? 100_000 : 16;
        inv.setItem(29, item(m.cur.icon, "<aqua><bold>Своя ставка: <white>" + fmt(m.cur, m.custom),
                "<gray>ЛКМ: <green>+" + fmt(step), "<gray>Shift+ЛКМ: <green>+" + fmt(big),
                "<gray>ПКМ: <red>-" + fmt(step), "<gray>Shift+ПКМ: <red>-" + fmt(big),
                "<gray>Q: <yellow>скинути"));
        boolean ok = m.custom > 0 && bal >= m.custom;
        inv.setItem(33, item(Material.LIME_DYE, ok ? "<green><bold>Грати за " + fmt(m.cur, m.custom) : "<gray>Грати",
                ok ? "<yellow>Натисни щоб почати" : "<red>Спочатку набери ставку кнопкою ліворуч"));
    }

    private void startGame(Player p, Game g, Currency cu, long bet) {
        switch (g) {
            case ROULETTE -> roulette(p, cu, bet);
            case SLOTS -> slots(p, cu, bet);
            case COIN -> openCoin(p, cu, bet);
            case DICE -> openDice(p, cu, bet);
            case CUPS -> openCups(p, cu, bet);
            case MINES -> openMines(p, cu, bet);
        }
    }

    // ================================================================ події

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (m.busy) return;
        int s = e.getSlot();

        if (m.done) {
            if (s == m.inv.getSize() - 9) openMain(p);
            else if (s == m.inv.getSize() - 1) replay(p, m);
            return;
        }

        switch (m.type) {
            case MAIN -> {
                for (Game g : Game.values()) {
                    if (g.slot == s) {
                        tick(p);
                        openBet(p, g);
                        return;
                    }
                }
                if (s == 40) {
                    p.closeInventory();
                    openRules(p);
                } else if (s == 42) {
                    exchange(p, e.getClick());
                    fillMain(m.inv, p);
                }
            }
            case BET -> {
                Currency[] cs = Currency.values();
                int[] cslots = {11, 13, 15};
                for (int i = 0; i < 3; i++) {
                    if (s == cslots[i]) {
                        m.cur = cs[i];
                        m.custom = 0;
                        drawBet(m, p);
                        tick(p);
                        return;
                    }
                }
                ClickType ct = e.getClick();
                if (s == 29) {
                    long step = m.cur == Currency.COINS ? 10 : 1;
                    long big = m.cur == Currency.COINS ? 100_000 : 16;
                    switch (ct) {
                        case LEFT -> m.custom += step;
                        case SHIFT_LEFT -> m.custom += big;
                        case RIGHT -> m.custom = Math.max(0, m.custom - step);
                        case SHIFT_RIGHT -> m.custom = Math.max(0, m.custom - big);
                        case DROP, CONTROL_DROP -> m.custom = 0;
                        default -> {
                        }
                    }
                    m.custom = Math.min(m.custom, plugin.balance(p, m.cur));
                    drawBet(m, p);
                    tick(p);
                    return;
                }
                if (s == 33) {
                    long bet = m.custom;
                    if (bet <= 0 || !plugin.take(p, m.cur, bet)) {
                        play(p, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                        msg(p, bet <= 0 ? "<red>Спочатку набери ставку." : "<red>Недостатньо коштів для такої ставки.");
                        return;
                    }
                    startGame(p, m.game, m.cur, bet);
                    return;
                }
                if (s >= 19 && s <= 25) {
                    long bet = m.cur.bets[s - 19];
                    if (!plugin.take(p, m.cur, bet)) {
                        play(p, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                        msg(p, "<red>Недостатньо коштів для такої ставки.");
                        return;
                    }
                    startGame(p, m.game, m.cur, bet);
                } else if (s == 31) {
                    openMain(p);
                }
            }
            case COIN -> {
                if (s == 11) runCoin(p, m, 0);
                else if (s == 15) runCoin(p, m, 1);
            }
            case DICE -> {
                if (s >= 10 && s <= 15) runDice(p, m, s - 9);
            }
            case CUPS -> {
                if (s == 11) runCups(p, m, 0);
                else if (s == 13) runCups(p, m, 1);
                else if (s == 15) runCups(p, m, 2);
            }
            case MINES -> minesClick(p, m, s);
            default -> {
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        if (m.settled) return;
        m.settled = true;
        if (!(e.getPlayer() instanceof Player p)) return;
        if (m.type == Type.MINES && m.picks > 0) {
            long pay = calc(m.bet, MINES[m.picks - 1]);
            plugin.give(p, m.cur, pay);
            msg(p, "<yellow>Гру закрито - виграш автоматично забрано: <gold>" + fmt(m.cur, pay));
        } else {
            plugin.give(p, m.cur, m.bet);
            msg(p, "<yellow>Гру скасовано, ставку повернуто: <gold>" + fmt(m.cur, m.bet));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.saveData();
    }

    private void replay(Player p, Menu m) {
        if (!plugin.take(p, m.cur, m.bet)) {
            play(p, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            msg(p, "<red>Недостатньо коштів для повтору ставки.");
            return;
        }
        startGame(p, m.game, m.cur, m.bet);
    }

    // ================================================================ завершення гри

    private void finish(Player p, Menu m, long pay) {
        m.busy = false;
        m.done = true;
        long net = pay - m.bet;
        String g = "<gray>" + m.game.title + ": ";
        if (net > 0) {
            msg(p, "<green>Виграш! " + g + "<green>+" + fmt(m.cur, net) + " <dark_gray>(виплата " + fmt(m.cur, pay) + ")");
            play(p, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        } else if (net == 0) {
            msg(p, "<yellow>Ставку повернуто. " + g + "<yellow>" + fmt(m.cur, pay));
            play(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
        } else {
            msg(p, "<red>Програш. " + g + "<red>-" + fmt(m.cur, -net));
            play(p, Sound.ENTITY_VILLAGER_NO, 1f, 0.9f);
        }
        if (pay > 0 && pay >= m.bet * 10) {
            Bukkit.getServer().broadcast(c(PREFIX + "<yellow>" + p.getName() + " <gray>виграв <gold>"
                    + fmt(m.cur, pay) + " <gray>у грі <aqua>" + m.game.title + "<gray>!"));
        }
        m.inv.setItem(m.inv.getSize() - 9, item(Material.ARROW, "<red>← В меню"));
        m.inv.setItem(m.inv.getSize() - 1, item(Material.LIME_DYE, "<green>Ще раз",
                "<gray>Ставка: <white>" + fmt(m.cur, m.bet)));
    }

    private void betInfo(Menu m, int slot) {
        ItemStack it = item(m.cur.icon, "<yellow>Ставка: <white>" + fmt(m.cur, m.bet));
        it.setAmount((int) (m.cur == Currency.COINS ? 1 : Math.min(64, Math.max(1, m.bet))));
        m.inv.setItem(slot, it);
    }

    // ================================================================ 1. Рулетка блоків

    private ItemStack symbol(int i, boolean center, boolean showMult) {
        ItemStack it = showMult
                ? item(R_MAT[i], R_COLOR[i] + "<bold>" + R_NAME[i] + " <white>" + x(rMult[i]),
                "<gray>Множник: <white>" + x(rMult[i]))
                : item(R_MAT[i], R_COLOR[i] + "<bold>" + R_NAME[i]);
        return center ? glow(it) : it;
    }

    private void roulette(Player p, Currency cu, long bet) {
        Menu m = menu(Type.ROULETTE, Game.ROULETTE, 36, "Рулетка блоків");
        m.cur = cu;
        m.bet = bet;
        int w = pick(rW);
        long pay = calc(bet, rMult[w]);
        plugin.give(p, cu, pay);

        int steps = 34 + rnd.nextInt(7);
        int[] strip = new int[steps + 9];
        for (int i = 0; i < strip.length; i++) strip[i] = pick(rW);
        strip[steps + 4] = w;

        // стрілки-вказівники над і під центральним слотом
        m.inv.setItem(4, item(Material.HOPPER, "<yellow><bold>▼"));
        m.inv.setItem(22, item(Material.END_ROD, "<yellow><bold>▲"));

        // таблиця виплат
        int total = 0;
        for (int v : rW) total += v;
        for (int i = 0; i < 6; i++) {
            m.inv.setItem(28 + i, item(R_MAT[i], R_COLOR[i] + "<bold>" + R_NAME[i],
                    "<gray>Множник: <white>" + x(rMult[i]),
                    "<gray>Шанс: <white>" + String.format(Locale.US, "%.1f", rW[i] * 100.0 / total) + "%",
                    "<gray>Виплата: <white>" + fmt(cu, calc(bet, rMult[i]))));
        }
        betInfo(m, 34);

        m.busy = true;
        p.openInventory(m.inv);
        final int st = steps;
        anim(steps + 1, 1, 5.0, i -> {
            for (int k = 0; k < 9; k++) m.inv.setItem(9 + k, symbol(strip[i + k], k == 4, true));
            play(p, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.8f + 1.2f * i / st);
        }, () -> {
            ItemStack pane = item(R_PANE[w], " ");
            for (int k = 0; k < 9; k++) {
                if (k == 4) continue;
                m.inv.setItem(k, pane);
                m.inv.setItem(18 + k, pane);
            }
            msg(p, "<gray>Випало: " + R_COLOR[w] + "<bold>" + R_NAME[w] + " <gray>(" + x(rMult[w])
                    + ") <dark_gray>| <gray>Ставка " + fmt(cu, bet) + " -> виплата <white>" + fmt(cu, pay));
            finish(p, m, pay);
        });
    }

    // ================================================================ 2. Слоти

    private void slots(Player p, Currency cu, long bet) {
        Menu m = menu(Type.SLOTS, Game.SLOTS, 27, "Слоти");
        m.cur = cu;
        m.bet = bet;
        int[] r = {pick(S_W), pick(S_W), pick(S_W)};
        double mult = 0;
        if (r[0] == r[1] && r[1] == r[2]) mult = S_MULT[r[0]];
        else if (r[0] == r[1] || r[1] == r[2] || r[0] == r[2]) mult = S_PAIR;
        long pay = calc(bet, mult);
        plugin.give(p, cu, pay);

        betInfo(m, 4);
        String[] lore = new String[8];
        for (int i = 0; i < 6; i++) lore[i] = R_COLOR[i] + R_NAME[i] + " <gray>x3 = <white>" + x(S_MULT[i]);
        lore[6] = "<gray>Пара = <white>" + x(S_PAIR);
        lore[7] = "<gray>Інакше - програш";
        m.inv.setItem(22, item(Material.PAPER, "<gold><bold>Виплати", lore));

        int[] slotIdx = {11, 13, 15};
        int[] stop = {10, 16, 22};
        m.busy = true;
        p.openInventory(m.inv);
        anim(24, i -> {
            for (int j = 0; j < 3; j++) {
                int sym = i >= stop[j] ? r[j] : rnd.nextInt(6);
                m.inv.setItem(slotIdx[j], symbol(sym, i >= stop[j], false));
            }
            tick(p);
        }, () -> finish(p, m, pay));
    }

    // ================================================================ 3. Орел і Решка

    private void openCoin(Player p, Currency cu, long bet) {
        Menu m = menu(Type.COIN, Game.COIN, 27, "Орел і Решка");
        m.cur = cu;
        m.bet = bet;
        m.settled = false;
        betInfo(m, 4);
        m.inv.setItem(11, item(Material.GOLD_BLOCK, "<yellow><bold>Орел", "<gray>Натисни щоб обрати"));
        m.inv.setItem(15, item(Material.IRON_BLOCK, "<white><bold>Решка", "<gray>Натисни щоб обрати"));
        m.inv.setItem(13, item(Material.SUNFLOWER, "<gray>Обери сторону"));
        p.openInventory(m.inv);
    }

    private void runCoin(Player p, Menu m, int choice) {
        m.settled = true;
        m.busy = true;
        boolean win = rnd.nextDouble() < 0.5;
        int res = win ? choice : 1 - choice;
        long pay = win ? calc(m.bet, COIN_MULT) : 0;
        plugin.give(p, m.cur, pay);
        anim(16, i -> {
            setFace(m, i % 2);
            tick(p);
        }, () -> {
            setFace(m, res);
            msg(p, "<gray>Випало: " + (res == 0 ? "<yellow><bold>Орел" : "<white><bold>Решка"));
            finish(p, m, pay);
        });
    }

    private void setFace(Menu m, int face) {
        m.inv.setItem(13, face == 0
                ? item(Material.GOLD_BLOCK, "<yellow><bold>Орел")
                : item(Material.IRON_BLOCK, "<white><bold>Решка"));
    }

    // ================================================================ 4. Кубик

    private void openDice(Player p, Currency cu, long bet) {
        Menu m = menu(Type.DICE, Game.DICE, 27, "Кубик");
        m.cur = cu;
        m.bet = bet;
        m.settled = false;
        betInfo(m, 4);
        for (int i = 1; i <= 6; i++) {
            ItemStack it = item(Material.QUARTZ_BLOCK, "<aqua><bold>Число " + i, "<gray>Натисни щоб обрати");
            it.setAmount(i);
            m.inv.setItem(9 + i, it);
        }
        m.inv.setItem(22, item(Material.PAPER, "<gray>Обери число", "<gray>Виграш: <green>" + x(DICE_MULT)));
        p.openInventory(m.inv);
    }

    private void runDice(Player p, Menu m, int choice) {
        m.settled = true;
        m.busy = true;
        int roll = 1 + rnd.nextInt(6);
        long pay = roll == choice ? calc(m.bet, DICE_MULT) : 0;
        plugin.give(p, m.cur, pay);
        anim(14, i -> {
            showDie(m, 1 + rnd.nextInt(6));
            tick(p);
        }, () -> {
            showDie(m, roll);
            msg(p, "<gray>Ти обрав <white>" + choice + "<gray>, випало <aqua><bold>" + roll);
            finish(p, m, pay);
        });
    }

    private void showDie(Menu m, int n) {
        ItemStack it = item(Material.WHITE_CONCRETE, "<white><bold>Випало: " + n);
        it.setAmount(n);
        m.inv.setItem(22, it);
    }

    // ================================================================ 5. Три скрині

    private void openCups(Player p, Currency cu, long bet) {
        Menu m = menu(Type.CUPS, Game.CUPS, 27, "Три скрині");
        m.cur = cu;
        m.bet = bet;
        m.settled = false;
        betInfo(m, 4);
        int[] sl = {11, 13, 15};
        for (int i = 0; i < 3; i++) {
            m.inv.setItem(sl[i], item(Material.CHEST, "<gold><bold>Скриня " + (i + 1), "<gray>Натисни щоб відкрити"));
        }
        p.openInventory(m.inv);
    }

    private void runCups(Player p, Menu m, int choice) {
        m.settled = true;
        m.busy = true;
        boolean win = rnd.nextInt(3) == 0;
        int prize;
        if (win) prize = choice;
        else {
            int o = rnd.nextInt(2);
            prize = o >= choice ? o + 1 : o;
        }
        long pay = win ? calc(m.bet, CUPS_MULT) : 0;
        plugin.give(p, m.cur, pay);
        int[] sl = {11, 13, 15};
        play(p, Sound.BLOCK_CHEST_OPEN, 1f, 1f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (int i = 0; i < 3; i++) {
                ItemStack it = i == prize
                        ? item(Material.DIAMOND_BLOCK, "<aqua><bold>ПРИЗ! " + x(CUPS_MULT))
                        : item(Material.COAL_BLOCK, "<dark_gray>Пусто");
                if (i == choice) glow(it);
                m.inv.setItem(sl[i], it);
            }
            finish(p, m, pay);
        }, 15L);
    }

    // ================================================================ 6. Шахта

    private void openMines(Player p, Currency cu, long bet) {
        Menu m = menu(Type.MINES, Game.MINES, 27, "Шахта");
        m.cur = cu;
        m.bet = bet;
        m.settled = false;
        int placed = 0;
        while (placed < 2) {
            int i = rnd.nextInt(9);
            if (!m.bomb[i]) {
                m.bomb[i] = true;
                placed++;
            }
        }
        for (int s : GRID) m.inv.setItem(s, item(Material.STONE, "<gray>Клітинка", "<yellow>Натисни щоб відкрити"));
        minesInfo(m);
        p.openInventory(m.inv);
    }

    private void minesInfo(Menu m) {
        String next = m.picks < MINES.length ? x(MINES[m.picks]) : "-";
        String now = m.picks > 0 ? x(MINES[m.picks - 1]) : "x1";
        m.inv.setItem(10, item(Material.PAPER, "<yellow><bold>Шахта",
                "<gray>Ставка: <white>" + fmt(m.cur, m.bet),
                "<gray>Відкрито: <white>" + m.picks + "/7",
                "<gray>Зараз: <green>" + now,
                "<gray>Наступний: <aqua>" + next,
                "<red>Бомб: 2 з 9"));
        if (m.picks > 0) {
            m.inv.setItem(16, item(Material.EMERALD_BLOCK, "<green><bold>Забрати " + fmt(m.cur, calc(m.bet, MINES[m.picks - 1])),
                    "<gray>Множник: <white>" + now));
        } else {
            m.inv.setItem(16, item(Material.BARRIER, "<gray>Відкрий клітинку"));
        }
    }

    private void minesClick(Player p, Menu m, int s) {
        if (s == 16) {
            if (m.picks > 0) cashMines(p, m);
            return;
        }
        int idx = -1;
        for (int i = 0; i < 9; i++) if (GRID[i] == s) idx = i;
        if (idx < 0 || m.open[idx]) return;

        if (m.bomb[idx]) {
            m.settled = true;
            revealBombs(m);
            m.inv.setItem(GRID[idx], glow(item(Material.TNT, "<red><bold>БУМ!")));
            play(p, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
            finish(p, m, 0);
            return;
        }
        m.open[idx] = true;
        m.picks++;
        m.inv.setItem(s, item(Material.EMERALD, "<green>Безпечно"));
        play(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f + m.picks * 0.1f);
        if (m.picks >= MINES.length) cashMines(p, m);
        else minesInfo(m);
    }

    private void cashMines(Player p, Menu m) {
        m.settled = true;
        long pay = calc(m.bet, MINES[m.picks - 1]);
        plugin.give(p, m.cur, pay);
        revealBombs(m);
        m.inv.setItem(16, item(Material.BARRIER, "<gray>Гру завершено"));
        finish(p, m, pay);
    }

    private void revealBombs(Menu m) {
        for (int i = 0; i < 9; i++) {
            if (m.bomb[i] && !m.open[i]) m.inv.setItem(GRID[i], item(Material.TNT, "<red>Бомба"));
        }
    }

    // ================================================================ обмін

    private void exchange(Player p, ClickType ct) {
        switch (ct) {
            case LEFT -> toCoins(p, Currency.DIAMOND, plugin.rateDiamond);
            case RIGHT -> toCoins(p, Currency.NETHERITE, plugin.rateNetherite);
            case SHIFT_LEFT -> fromCoins(p, Currency.DIAMOND, plugin.rateDiamond);
            case SHIFT_RIGHT -> fromCoins(p, Currency.NETHERITE, plugin.rateNetherite);
            default -> {
            }
        }
    }

    private void toCoins(Player p, Currency cu, long rate) {
        long n = plugin.balance(p, cu);
        if (n <= 0) {
            msg(p, "<red>У тебе немає предметів для обміну.");
            return;
        }
        plugin.take(p, cu, n);
        plugin.addCoins(p.getUniqueId(), n * rate);
        msg(p, "<green>Обміняно <white>" + n + " <green>на <gold>" + fmt(Currency.COINS, n * rate));
        play(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    private void fromCoins(Player p, Currency cu, long rate) {
        long n = plugin.coins(p) / rate;
        if (n <= 0) {
            msg(p, "<red>Замало монет для обміну (потрібно " + rate + " за 1).");
            return;
        }
        plugin.take(p, Currency.COINS, n * rate);
        plugin.give(p, cu, n);
        msg(p, "<green>Обміняно <gold>" + fmt(Currency.COINS, n * rate) + " <green>на <white>" + n + " <green>" + cu.suffix);
        play(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }
}
