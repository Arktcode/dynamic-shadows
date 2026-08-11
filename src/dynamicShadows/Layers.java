package dynamicShadows;

/**
 * Capas de renderizado y constantes z-index personalizadas para Dynamic Shadows.
 * Define la elevación Z exacta de cada pase de sombra en Mindustry.
 */
public class Layers {
    /** Tier 0: Sombras del terreno y bloques base 1x1 (debajo de bloques en Z=30f) */
    public static final float shadowGround = 29.0f;
    
    /** Tier 1: Sombras de bloques 2x2 (sobre bloques 1x1 y torretas Z=50.1f) */
    public static final float shadowTier1 = 50.1f;
    
    /** Tier 2: Sombras de bloques 3x3 (sobre bloques 2x2 y torretas Z=50.2f) */
    public static final float shadowTier2 = 50.2f;
    
    /** Tier 3: Sombras de bloques 4x4 y 5x5 (sobre bloques 3x3 y torretas Z=50.3f) */
    public static final float shadowTier3 = 50.3f;
    
    /** Tier 4: Sombras de bloques 6x6+ y montañas Z=54.5f (debajo de rayos de taladros de plasma Z=55f y energía Z=70f) */
    public static final float shadowMountain = 54.5f;
    
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
