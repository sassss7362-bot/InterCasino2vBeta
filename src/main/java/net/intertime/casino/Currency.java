package net.intertime.casino;

import org.bukkit.Material;

/** Валюти для ставок: внутрішні монети, алмази та незеритові злитки. */
public enum Currency {
    COINS("Монети", "монет", Material.GOLD_NUGGET, new long[]{100, 500, 1000, 5000, 10000, 50000, 100000}),
    DIAMOND("Алмази", "алм.", Material.DIAMOND, new long[]{4, 8, 16, 32, 64, 128, 256}),
    NETHERITE("Незерит", "незер.", Material.NETHERITE_INGOT, new long[]{4, 8, 12, 16, 24, 32, 64});

    public final String title;
    public final String suffix;
    public final Material icon;
    public final long[] bets;

    Currency(String title, String suffix, Material icon, long[] bets) {
        this.title = title;
        this.suffix = suffix;
        this.icon = icon;
        this.bets = bets;
    }
}
