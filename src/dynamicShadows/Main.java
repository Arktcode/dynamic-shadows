package dynamicShadows;

import arc.Core;
import arc.Events;
import mindustry.game.EventType;
import mindustry.mod.Mod;

public class Main extends Mod {

    public Main() {
        super();
    }

    @Override
    public void init() {
        Events.on(EventType.ClientLoadEvent.class, e -> {
            if (!mindustry.Vars.headless) {
                mindustry.Vars.ui.settings.addCategory("Shaders", mindustry.gen.Icon.settings, t -> {
                    t.checkPref("dynamic_lights_standalone", true, val -> {
                        DynamicShadowRenderer.enabled = val;
                    });
                    t.checkPref("day_night_cycle", true, val -> {
                        DynamicShadowRenderer.dayNightCycle = val;
                    });
                    t.sliderPref("shadow_length_multiplier", 10, 0, 30, 1, s -> {
                        DynamicShadowRenderer.SHADOW_LENGTH = s;
                        return s + "";
                    });
                    // 20% más oscuras (45% en vez de 38%)
                    t.sliderPref("shadow_opacity_percent", 45, 0, 100, 1, s -> {
                        DynamicShadowRenderer.SHADOW_ALPHA = s / 100f;
                        return s + "%";
                    });
                });
            }

            // Cargar estado inicial desde las configuraciones
            DynamicShadowRenderer.enabled = Core.settings.getBool("dynamic_lights_standalone", true);
            DynamicShadowRenderer.dayNightCycle = Core.settings.getBool("day_night_cycle", true);
            DynamicShadowRenderer.SHADOW_LENGTH = Core.settings.getInt("shadow_length_multiplier", 10);
            DynamicShadowRenderer.SHADOW_ALPHA = Core.settings.getInt("shadow_opacity_percent", 45) / 100f;
        });

        Events.run(EventType.Trigger.draw, () -> {
            DynamicShadowRenderer.weatherMult = 1f;
        });
        
        Events.run(EventType.Trigger.draw, DynamicShadowRenderer::queue);
    }
}
