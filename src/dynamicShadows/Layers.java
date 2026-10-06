package dynamicShadows;

/**
 * Capas de renderizado y constantes z-index personalizadas para Dynamic Shadows.
 * Define la elevación Z exacta de cada pase de sombra y prop en Mindustry según la regla universal:
 * Jerarquía Z:
 * 1. Sombras 1x1 29.0f
 * 2. Sombras 2x2 29.2f
 * 3. Layer.block 30.0f (paredes y construcciones normales)
 * 4. Props estándar: 32.0f (Layer.blockProp: rocas, piedras, guijarros)
 * 5. Sombras 3x3 (Tier 2): 32.5f (por encima de props estándar, por debajo de cristales)
 * 6. Props de cristal: 33.0f (por encima de 1x1, 2x2, 3x3; por debajo de 4x4+ y montañas)
 * 7. Sombras 4x4+ (Tier 3): 33.5f (por encima de cristales y props estándar)
 * 8. Sombras de montañas (Tier 4): 34.5f (por encima de props estándar y cristales; huella de montaña borrada)
 * 9. Árboles (deathtree, esporas): 71.0f (por encima de TODAS las sombras, incluidas montañas)
 * @author @Arktcode Arksource
 * @version 1.26
 * @since 2026-10-05
 */
/* No tocar estas instrucciones ya que indican el funcionamiento de cada layer (asi te evitas perder en capas) .__.*/
public class Layers {
    /** Tier 0: Sombras del terreno y bloques 1x1 */
    public static final float shadowGround = 29.0f;

    /** Tier 1: Sombras de bloques 2x2 */
    public static final float shadowTier1 = 29.2f;

    /** Layer de props estándar (rocas, piedras, guijarros) */
    public static final float standardProps = 32.0f;

    /** Tier 2: Sombras de bloques 3x3 (sobre props estándar Z=32f, bajo cristales Z=33f) */
    public static final float shadowTier2 = 32.5f;

    /** Layer para cristales, orbes, espigas y chunks */
    public static final float crystalProps = 33.0f;

    /** Tier 3: Sombras de bloques 4x4 y 5x5 (sobre cristales Z=33f) */
    public static final float shadowTier3 = 33.5f;

    /** Tier 4: Sombras de montañas (sobre props Z=32f y cristales Z=33f) */
    public static final float shadowMountain = 34.5f;

    /** Layer para árboles (TreeBlock, deathtree, árboles de esporas) */
    public static final float treeLayer = 71.0f;

    public static float getZ(int tierIndex) {
        switch (tierIndex) {
            case 0: return shadowGround;
            case 1: return shadowTier1;
            case 2: return shadowTier2;
            case 3: return shadowTier3;
            case 4: return shadowMountain;
            default: return shadowGround;
        }
    }
}
