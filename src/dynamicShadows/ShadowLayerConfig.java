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
import mindustry.world.blocks.payloads.PayloadRouter;
import mindustry.world.blocks.power.PowerNode;

/**
 * Configuración de capas y clasificación Z por Tiers para Dynamic Shadows.
 */
public final class ShadowLayerConfig {

    public static final int NUM_TIERS  = 5;
    public static final int TIER_SMALL = 0; // 1x1, transportes, rocas pequeñas
    public static final int TIER_MED   = 1; // 2x2, vegetación
    public static final int TIER_LARGE = 2; // 3x3
    public static final int TIER_XL    = 3; // 4x4+
    public static final int TIER_ENV   = 4; // Montañas y paredes de rocas naturales

    private static final ObjectIntMap<String> tierOverrides = new ObjectIntMap<>();
    private static final ObjectMap<Block, Boolean> mountainWallCache = new ObjectMap<>(128);

    private ShadowLayerConfig() {}

    static {
        // Sobrescrituras explícitas de Tiers para bloques cuyas dimensiones/clase no se clasifican automáticamente.
        // Los lanzadores de masa (Mass drivers) son 3x3 y deben proyectar TIER_LARGE.
        tierOverrides.put("mass-driver",               TIER_LARGE);
        tierOverrides.put("payload-mass-driver",       TIER_LARGE);
        tierOverrides.put("large-payload-mass-driver", TIER_XL);
        tierOverrides.put("impetus",                   TIER_LARGE); // Variante 3x3 de mods

        // Pantallas lógicas y células/bancos de memoria forzados a Tier 1x1
        tierOverrides.put("logic-display",             TIER_SMALL);
        tierOverrides.put("large-logic-display",       TIER_SMALL);
        tierOverrides.put("memory-cell",               TIER_SMALL);
        tierOverrides.put("memory-bank",               TIER_SMALL);

        // Bloques de núcleo
        tierOverrides.put("core-shard",                TIER_LARGE);
        tierOverrides.put("core-foundation",           TIER_XL);
        tierOverrides.put("core-nucleus",              TIER_XL);
        tierOverrides.put("core-bastion",              TIER_LARGE);
        tierOverrides.put("core-citadel",              TIER_XL);
        tierOverrides.put("core-acropolis",            TIER_XL);

        // Bloques de almacenamiento y líquidos (Serpulo + Erekir)
        tierOverrides.put("container",                 TIER_MED);   // 2x2
        tierOverrides.put("vault",                     TIER_LARGE); // 3x3
        tierOverrides.put("unloader",                  TIER_SMALL);
        tierOverrides.put("liquid-container",            TIER_MED);   // 2x2
        tierOverrides.put("liquid-tank",                 TIER_LARGE); // 3x3
        tierOverrides.put("reinforced-container",        TIER_MED);   // 2x2
        tierOverrides.put("reinforced-vault",            TIER_LARGE); // 3x3
        tierOverrides.put("reinforced-liquid-container", TIER_MED);   // 2x2
        tierOverrides.put("reinforced-liquid-tank",      TIER_LARGE); // 3x3
        tierOverrides.put("reinforced-pump",             TIER_MED);   // 2x2

        // Soporte / Proyectores / Reparadores
        tierOverrides.put("overdrive-projector",       TIER_MED);
        tierOverrides.put("overdrive-dome",            TIER_LARGE);
        tierOverrides.put("force-projector",           TIER_LARGE);
        tierOverrides.put("large-force-projector",     TIER_XL);
        tierOverrides.put("mender",                    TIER_SMALL);
        tierOverrides.put("mend-projector",            TIER_MED);
        tierOverrides.put("repair-tower",              TIER_MED);
        tierOverrides.put("repair-turret",             TIER_MED);

        // Campaña / Lanzamiento / Aterrizaje
        tierOverrides.put("launch-pad",                TIER_LARGE);
        tierOverrides.put("landing-pad",               TIER_LARGE);
        tierOverrides.put("interplanetary-accelerator",TIER_XL);

        // Reconstructores (3x3, 5x5, 7x7, 9x9)
        tierOverrides.put("additive-reconstructor",       TIER_LARGE); // 3x3
        tierOverrides.put("multiplicative-reconstructor", TIER_XL);    // 5x5
        tierOverrides.put("exponential-reconstructor",    TIER_ENV);   // 7x7 (>= 6x6 -> Tier 4, sobre enlaces de energía)
        tierOverrides.put("tetrative-reconstructor",      TIER_ENV);   // 9x9 (>= 6x6 -> Tier 4, sobre enlaces de energía)
    }
    //TODO Esto mayormente lo hago para seleccionar bloques especificos con propiedades especificas

    public static int getTier(Block b) {
        if (b == null) return TIER_SMALL;
        if (isBridge(b) || isPowerNode(b) || isDistributionBlock(b) || isLogicOrMemory(b)) return TIER_SMALL;

        String key = b.name != null ? b.name.toLowerCase() : "";
        if (tierOverrides.containsKey(key)) {
            return Mathf.clamp(tierOverrides.get(key, TIER_SMALL), 0, NUM_TIERS - 1);
        }

        if (isMountainOrWall(b)) return TIER_ENV;
        if (b instanceof Prop || b instanceof TreeBlock) return getPropTier(b);

        return sizeToTier(b.size);
    }

    public static int unitTier(float elevation) {
        return Mathf.clamp((int)(elevation * NUM_TIERS), 0, NUM_TIERS - 1);
    }

    private static int sizeToTier(int size) {
        if (size <= 1) return TIER_SMALL;
        if (size == 2) return TIER_MED;
        if (size == 3) return TIER_LARGE;
        if (size <= 5) return TIER_XL;
        return TIER_ENV; // Bloques 6x6 o mayores asignados a Tier 4 (Z=72.0f)
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
        if (b instanceof Prop || b instanceof TreeBlock) return false;
        String n = b.name != null ? b.name.toLowerCase() : "";
        return n.contains("wall") || n.contains("mountain") || n.contains("montana") || n.contains("cliff");
    }

    private static int getPropTier(Block b) {
        String n = b.name != null ? b.name.toLowerCase() : "";
        if (n.contains("rock") || n.contains("boulder") || n.contains("pebble") || n.contains("stone")) return TIER_SMALL;
        if (n.contains("chunk")) return TIER_LARGE;
        if (n.contains("spore") || n.contains("dead") || n.contains("shale")) return TIER_XL;
        return TIER_MED;
    }

    public static boolean isDistributionBlock(Block b) {
        if (b instanceof Conveyor || b instanceof Router || b instanceof Sorter || b instanceof Junction
                || b instanceof StackConveyor || b instanceof OverflowGate || b instanceof ItemBridge) return true;
        if (b instanceof PayloadConveyor || b instanceof PayloadRouter) return true;
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
