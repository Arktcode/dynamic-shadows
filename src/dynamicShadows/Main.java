package dynamicShadows;

import arc.Core;
import arc.Events;
import mindustry.game.EventType;
import mindustry.mod.Mod;

public class Main extends Mod {
    public Main() { super(); }

    @Override
    public void init() {
        Events.on(EventType.ClientLoadEvent.class, e -> {
            if (!mindustry.Vars.headless) {
                mindustry.Vars.ui.settings.addCategory("Shaders", mindustry.gen.Icon.settings, t -> {
                    t.pref(new mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.Setting("") {
                        @Override public void add(mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable table) {
                            table.add("── Sombras de Unidades ──").color(arc.graphics.Color.valueOf("f5c842")).padTop(12f).padBottom(6f).row();
                        }
                    });

                    t.checkPref("dynamic_shadows_enabled", true,  val -> DynamicShadowRenderer.enabled = val);
                    t.checkPref("day_night_cycle",         true,  val -> DynamicShadowRenderer.dayNightCycle = val);

                    t.sliderPref("shadow_length", 10, 0, 30, 1, s -> {
                        DynamicShadowRenderer.SHADOW_LENGTH = s;  return s + " tiles";
                    });
                    t.sliderPref("shadow_opacity_percent", 45, 0, 100, 1, s -> {
                        DynamicShadowRenderer.SHADOW_ALPHA = s/100f; return s + "%";
                    });
                    t.sliderPref("blur_radius", 35, 10, 80, 5, s -> {
                        DynamicShadowRenderer.blurRadius = s/10f; return (s/10f) + " px";
                    });
                    t.sliderPref("shadow_tint_percent", 60, 0, 100, 5, s -> {
                        DynamicShadowRenderer.shadowTint = s/100f; return s + "%";
                    });
                    t.sliderPref("contact_shadow_percent", 45, 0, 100, 5, s -> {
                        DynamicShadowRenderer.contactShadow = s/100f; return s + "%";
                    });
                    t.sliderPref("dark_fade_percent", 80, 0, 100, 5, s -> {
                        DynamicShadowRenderer.darkFadeStrength = s/100f; return s + "%";
                    });
                });
            }

            DynamicShadowRenderer.enabled          = Core.settings.getBool("dynamic_shadows_enabled", true);
            DynamicShadowRenderer.dayNightCycle    = Core.settings.getBool("day_night_cycle", true);
            DynamicShadowRenderer.SHADOW_LENGTH    = Core.settings.getInt("shadow_length", 10);
            DynamicShadowRenderer.SHADOW_ALPHA     = Core.settings.getInt("shadow_opacity_percent", 45)/100f;
            DynamicShadowRenderer.blurRadius       = Core.settings.getInt("blur_radius", 35)/10f;
            DynamicShadowRenderer.shadowTint       = Core.settings.getInt("shadow_tint_percent", 60)/100f;
            DynamicShadowRenderer.contactShadow    = Core.settings.getInt("contact_shadow_percent", 45)/100f;
            DynamicShadowRenderer.darkFadeStrength = Core.settings.getInt("dark_fade_percent", 80)/100f;
        });

        Events.on(EventType.WorldLoadEvent.class,     e -> LightMapRenderer.invalidateCache());
        Events.on(EventType.BlockBuildEndEvent.class, e -> LightMapRenderer.invalidateCache());

        Events.run(EventType.Trigger.draw, () -> {
            DynamicShadowRenderer.weatherMult = 1f;
        });
        
        Events.run(EventType.Trigger.draw, DynamicShadowRenderer::queue);
    }
}
