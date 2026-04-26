package dynamicShadows;

import arc.graphics.Color;
import mindustry.gen.*;
import mindustry.world.Block;

public class LightSourceScanner {
    private static final float[] FIRE = { 1f, 0.44f, 0.13f }, ELEC = { 0.67f, 0.78f, 1f },
            NUCLEAR = { 0.63f, 1f, 0.5f }, WARM = { 1f, 0.91f, 0.63f }, RED_IND = { 1f, 0.27f, 0f };
    private static final float MIN_RADIUS = 12f;

    public static LightEntry classify(Block b, Building build) {
        if (b instanceof mindustry.world.blocks.storage.CoreBlock) return null;
        if (b.emitLight && b.lightRadius > MIN_RADIUS) {
            Color c = b.lightColor != null ? b.lightColor : Color.white;
            float intensity = b.lightRadius > 120f ? 0.7f : 0.85f;
            LightEntry le = mk(build.x, build.y, b.lightRadius, c.r, c.g, c.b, intensity);
            if (b instanceof mindustry.world.blocks.power.PowerNode || b instanceof mindustry.world.blocks.power.LightBlock) {
                le.isStrongLight = true;
            }
            return le;
        }
        String n = b.name.toLowerCase();
        float baseR = 40f + b.size * 10f;
        if (has(n, "combustion", "thermal", "incinerator", "furnace", "smelter", "kiln", "forge", "burner", "blast",
                "boiler", "oil", "pyro", "heat", "char", "coke", "alloy", "arc-furnace")) {
            return mk(build.x, build.y, baseR * 1.2f, FIRE, 0.75f);
        }

        // Electric / surge / plasma
        if (has(n, "surge", "phase", "electric", "heater", "arc", "laser",
                "pulse", "beam", "plasma", "spark", "tesla", "lightning")) {
            return mk(build.x, build.y, baseR * 1.0f, ELEC, 0.65f);
        }

        // Nuclear / reactor
        if (has(n, "thorium", "nuclear", "reactor", "fission", "fusion",
                "impact", "rtg", "neoplasm", "overdrive-dome")) {
            return mk(build.x, build.y, baseR * 1.5f, NUCLEAR, 0.80f);
        }

        // Industrial smelters / separators / grinders (red-orange glow)
        if (has(n, "smelter", "separator", "disassembl", "grinder", "centrifuge", "melter")) {
            return mk(build.x, build.y, baseR * 0.85f, RED_IND, 0.55f);
        }

        return null;
    }

    public static LightEntry classifyBullet(Bullet bullet) {
        mindustry.entities.bullet.BulletType type = bullet.type;
        if (type == null)
            return null;

        try {
            if (type.lightOpacity > 0.08f && type.lightRadius > 8f && type.lightColor != null) {
                Color c = type.lightColor;
                return mk(bullet.x, bullet.y, type.lightRadius,
                        c.r, c.g, c.b, type.lightOpacity * 0.8f);
            }
        } catch (Exception ignored) {
        }

        String n = type.getClass().getSimpleName().toLowerCase();

        if (has(n, "fire", "lava", "melt", "blaze", "incend", "flame", "heat", "pyro")) {
            float r = Math.max(type.hitSize * 2.5f, 22f);
            return mk(bullet.x, bullet.y, r, FIRE, 0.55f);
        }
        if (has(n, "laser", "beam", "phase", "lightning", "arc", "surge", "electr", "pulse")) {
            float r = Math.max(type.hitSize * 2f, 18f);
            return mk(bullet.x, bullet.y, r, ELEC, 0.50f);
        }
        if (has(n, "nuke", "nuclear", "missile", "bomb", "explod", "frag")) {
            float r = Math.max(type.hitSize * 3f, 30f);
            return mk(bullet.x, bullet.y, r, FIRE, 0.65f);
        }

        if (type.hitSize > 10f) {
            float r = type.hitSize * 1.8f;
            return mk(bullet.x, bullet.y, r, WARM, 0.25f);
        }

        return null;
    }

    // helpers

    private static LightEntry mk(float x, float y, float radius,
            float[] col, float intensity) {
        return new LightEntry(x, y, radius, col[0], col[1], col[2], intensity);
    }

    private static LightEntry mk(float x, float y, float radius,
            float r, float g, float b, float intensity) {
        return new LightEntry(x, y, radius, r, g, b, intensity);
    }

    private static boolean has(String s, String... keys) {
        for (String k : keys)
            if (s.contains(k))
                return true;
        return false;
    }
}
