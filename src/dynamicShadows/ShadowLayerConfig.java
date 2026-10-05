package dynamicShadows;

import arc.math.Mathf;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.Junction;
import mindustry.world.blocks.distribution.OverflowGate;
import mindustry.world.blocks.distribution.Router;
import mindustry.world.blocks.distribution.Sorter;
import mindustry.world.blocks.distribution.StackConveyor;
import mindustry.world.blocks.environment.Prop;
import mindustry.world.blocks.environment.StaticWall;
import mindustry.world.blocks.environment.TreeBlock;
import mindustry.world.blocks.payloads.PayloadConveyor;
import mindustry.world.blocks.power.PowerNode;

/** Clasificación de capas Z y asignación de Tiers para las sombras dinámicas. */
public final class ShadowLayerConfig {

    public static final int numTiers  = 5;
    public static final int tierSmall = 0; // 1x1: transportes, rocas pequeñas
    public static final int tierMed   = 1; // 2x2: vegetación mediana
    public static final int tierLarge = 2; // 3x3
    public static final int tierXL    = 3; // 4x4 y 5x5
    public static final int TIER_ENV   = 4; // Montañas y paredes naturales

    private static final ObjectIntMap<String> tierOverrides = new ObjectIntMap<>();
    private static final ObjectMap<Block, Boolean> mountainWallCache = new ObjectMap<>(128);

    private ShadowLayerConfig() {}

    static {
        // Sobrescrituras de Tier para bloques cuya clase o tamaño no se clasifican automáticamente
        tierOverrides.put("mass-driver",               tierLarge);
        tierOverrides.put("payload-mass-driver",       tierLarge);
        tierOverrides.put("large-payload-mass-driver", tierXL);
        tierOverrides.put("impetus",                   tierLarge);

        tierOverrides.put("logic-display",             tierSmall);
        tierOverrides.put("large-logic-display",       tierSmall);
        tierOverrides.put("memory-cell",               tierSmall);
        tierOverrides.put("memory-bank",               tierSmall);

        // Núcleos
        tierOverrides.put("core-shard",                tierLarge);
        tierOverrides.put("core-foundation",           tierXL);
        tierOverrides.put("core-nucleus",              tierXL);
        tierOverrides.put("core-bastion",              tierLarge);
        tierOverrides.put("core-citadel",              tierXL);
        tierOverrides.put("core-acropolis",            tierXL);

        // Almacenamiento y líquidos
        tierOverrides.put("container",                 tierMed);
        tierOverrides.put("vault",                     tierLarge);
        tierOverrides.put("unloader",                  tierSmall);
        tierOverrides.put("liquid-container",            tierMed);
        tierOverrides.put("liquid-tank",                 tierLarge);
        tierOverrides.put("reinforced-container",        tierMed);
        tierOverrides.put("reinforced-vault",            tierLarge);
        tierOverrides.put("reinforced-liquid-container", tierMed);
        tierOverrides.put("reinforced-liquid-tank",      tierLarge);
        tierOverrides.put("reinforced-pump",             tierMed);

        // Proyectores y reparadores
        tierOverrides.put("overdrive-projector",       tierMed);
        tierOverrides.put("overdrive-dome",            tierLarge);
        tierOverrides.put("force-projector",           tierLarge);
        tierOverrides.put("large-force-projector",     tierXL);
        tierOverrides.put("mender",                    tierSmall);
        tierOverrides.put("mend-projector",            tierMed);
        tierOverrides.put("repair-tower",              tierMed);
        tierOverrides.put("repair-turret",             tierMed);

        // Lanzamiento y aterrizaje
        tierOverrides.put("launch-pad",                tierLarge);
        tierOverrides.put("landing-pad",               tierLarge);
        tierOverrides.put("interplanetary-accelerator",tierXL);

        // Reconstructores
        tierOverrides.put("additive-reconstructor",       tierLarge);
        tierOverrides.put("multiplicative-reconstructor", tierXL);
        tierOverrides.put("exponential-reconstructor",    tierXL);
        tierOverrides.put("tetrative-reconstructor",      tierXL);
    }

    public static int getTier(Block b) {
        if (b == null) return tierSmall;
        if (isBridge(b) || isPowerNode(b) || isDistributionBlock(b) || isLogicOrMemory(b)) return tierSmall;

        String key = b.name != null ? b.name.toLowerCase() : "";
        if (tierOverrides.containsKey(key)) {
            return Mathf.clamp(tierOverrides.get(key, tierSmall), 0, numTiers - 1);
        }

        if (isMountainOrWall(b) || AnyBlocksShadows.isPine(b)) return TIER_ENV;
        if (b instanceof Prop || b instanceof TreeBlock || b instanceof mindustry.world.blocks.environment.TallBlock || isCrystal(b) || isTree(b)) return getPropTier(b);

        return sizeToTier(b.size);
    }

    public static int unitTier(float elevation) {
        return Mathf.clamp((int)(elevation * numTiers), 0, numTiers - 1);
    }

    private static int sizeToTier(int size) {
        if (size <= 1) return tierSmall;
        if (size == 2) return tierMed;
        if (size == 3) return tierLarge;
        return tierXL;
    }

    public static boolean isMountainOrWall(Block b) {
        if (b == null) return false;
        synchronized (mountainWallCache) {
            Boolean cached = mountainWallCache.get(b);
            if (cached != null) return cached;
        }
        boolean result = computeIsMountainOrWall(b);
        synchronized (mountainWallCache) {
            mountainWallCache.put(b, result);
        }
        return result;
    }

    private static boolean computeIsMountainOrWall(Block b) {
        if (!b.isStatic() || !b.solid) return false;
        if (b instanceof StaticWall) return true;
        if (b instanceof TreeBlock || isTree(b)) return false;
        if (b instanceof mindustry.world.blocks.environment.TallBlock || isCrystal(b)) return false;
        if (b instanceof Prop) return false;
        String n = b.name != null ? b.name.toLowerCase() : "";
        return n.contains("wall") || n.contains("mountain") || n.contains("montana") || n.contains("cliff");
    }

    public static boolean isCrystal(Block b) {
        if (b == null) return false;
        if (b instanceof StaticWall) return false;
        if (b instanceof mindustry.world.blocks.environment.Floor) return false;
        if (b instanceof mindustry.world.blocks.environment.TallBlock) return true;
        String n = b.name != null ? b.name.toLowerCase() : "";
        return n.contains("crystal") || n.contains("spike") || n.contains("chunk") || n.contains("orb");
    }

    public static boolean isTree(Block b) {
        if (b == null) return false;
        if (b instanceof TreeBlock) return true;
        String n = b.name != null ? b.name.toLowerCase() : "";
        if (n.contains("wall") || n.contains("floor") || n.contains("press") || n.contains("moss")) return false;
        return n.contains("tree") || n.contains("deathtree") || (n.contains("spore") && (n.contains("pine") || n.contains("tree") || n.contains("wood")));
    }

    private static int getPropTier(Block b) {
        if (isTree(b)) return tierMed;
        return tierSmall;
    }

    public static boolean isDistributionBlock(Block b) {
        if (b instanceof Conveyor || b instanceof Router || b instanceof Sorter || b instanceof Junction
                || b instanceof StackConveyor || b instanceof OverflowGate || b instanceof ItemBridge) return true;
        if (b instanceof PayloadConveyor) return true;
        return isBridge(b);
    }

    public static boolean isBridge(Block b) {
        if (b == null) return false;
        String n = b.name != null ? b.name.toLowerCase() : "";
        String cn = b.getClass().getSimpleName().toLowerCase();
        return n.contains("bridge") || cn.contains("bridge") || b instanceof ItemBridge;
    }

    public static boolean isPowerNode(Block b) {
        if (b == null) return false;
        String cn = b.getClass().getSimpleName();
        return b instanceof PowerNode || cn.equals("BeamNode");
    }

    public static boolean isLogicOrMemory(Block b) {
        if (b == null) return false;
        if (b instanceof mindustry.world.blocks.logic.LogicDisplay || b instanceof mindustry.world.blocks.logic.MemoryBlock) return true;
        String n = b.name != null ? b.name.toLowerCase() : "";
        return n.contains("display") || n.contains("memory");
    }

    public static boolean isMine(Block b) {
        if (b == null) return false;
        if (b instanceof mindustry.world.blocks.defense.ShockMine) return true;
        String n = b.name != null ? b.name.toLowerCase() : "";
        return n.equals("shock-mine") || n.endsWith("-mine");
    }
}
