package dynamicShadows;

/**
 * Capas de renderizado y constantes z-index personalizadas para Dynamic Shadows.
 * Define la elevación Z exacta de cada pase de sombra en Mindustry.
 */
public class Layers {
    /** Tier 0: Sombras del terreno y bloques base 1x1 */
    public static final float shadowGround = 29.0f;
    
    /** Tier 1: bloques 2x2 Z=70f */
    public static final float shadowTier1 = 29.1f;
    
    /** Tier 2: Sombras de bloques 3x3 Z=70f) */
    public static final float shadowTier2 = 29.2f;
    
    /** Tier 3: Sombras de bloques 4x4 y 5x5 Z=70f) */
    public static final float shadowTier3 = 29.3f;
    
    /** Tier 4: Sombras de bloques 6x6+ y montañas Z=72f) */
    public static final float shadowMountain = 72.0f;
    
    public static float getZ(int tierIndex) {
        switch (tierIndex) {
            case 0: return shadowGround;
            case 1: return shadowTier1;
            case 2: return shadowTier2;
            case 3: return shadowTier3;
            case 4: return shadowMountain;//(6x6+ y montañas)
            default: return shadowGround;
        }
    }
}
