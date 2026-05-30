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
        // Initialise shadow renderer

        Events.on(EventType.ClientLoadEvent.class, e -> {
            if (!mindustry.Vars.headless) {
                mindustry.Vars.ui.settings.addCategory(
                    Core.bundle.get("settings.dynamic_shadows.category", "Shaders"),
                    mindustry.gen.Icon.settings, t -> {

                    // Dynamic Shadows header
                    t.pref(new mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.Setting("") {
                        @Override
                        public void add(mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable table) {
                            table.add(Core.bundle.get("settings.dynamic_shadows.header", "Dynamic Shadows"))
                                .color(arc.graphics.Color.valueOf("fed17b")).padTop(12f).padBottom(6f).row();
                        }
                    });

                    t.checkPref("dynamic_shadows_enabled", true,  val -> {
                        DynamicShadowRenderer.enabled = val;
                        DynamicShadowRenderer.updateUnitShadows();
                    });
                    t.checkPref("day_night_cycle",         true,  val -> DynamicShadowRenderer.dayNightCycle = val);
                    t.checkPref("dynamic_unit_shadows",    true,  val -> {
                        DynamicShadowRenderer.unitShadowsEnabled = val;
                        DynamicShadowRenderer.updateUnitShadows();
                    });

                    t.sliderPref("graphics_quality", 2, 0, 2, 1, s -> {
                        DynamicShadowRenderer.graphicsQuality = s;
                        return Core.bundle.get("setting.graphics_quality.val." + s,
                            s == 0 ? "Low" : s == 1 ? "Medium" : "High");
                    });
                    t.sliderPref("shadow_length", 10, 0, 30, 1, s -> {
                        DynamicShadowRenderer.SHADOW_LENGTH = s;
                        return s + " " + Core.bundle.get("setting.shadow_length.tiles", "tiles");
                    });
                    t.sliderPref("prop_shadow_scale", 100, 0, 200, 10, s -> {
                        DynamicShadowRenderer.propShadowScale = s / 100f;
                        boolean oldVal = DynamicShadowRenderer.oldShadowsEnabled;
                        DynamicShadowRenderer.oldShadowsEnabled = (s == 0);
                        if (oldVal != DynamicShadowRenderer.oldShadowsEnabled) {
                            DynamicShadowRenderer.ChunkCache.invalidateAll();
                        }
                        return s == 0 ? Core.bundle.get("setting.prop_shadow_scale.old", "Mindustry (Old)") : s + "%";
                    });
                    t.sliderPref("shadow_opacity_percent", 45, 0, 100, 1, s -> {
                        DynamicShadowRenderer.SHADOW_ALPHA = s / 100f;
                        return s + "%";
                    });
                    t.sliderPref("blur_radius", 35, 10, 80, 5, s -> {
                        DynamicShadowRenderer.blurRadius = s / 10f;
                        return (s / 10f) + " px";
                    });
                    t.sliderPref("shadow_tint_percent", 60, 0, 100, 5, s -> {
                        DynamicShadowRenderer.shadowTint = s / 100f;
                        return s + "%";
                    });
                    t.sliderPref("contact_shadow_percent", 45, 0, 100, 5, s -> {
                        DynamicShadowRenderer.contactShadow = s / 100f;
                        return s + "%";
                    });
                    t.sliderPref("dark_fade_percent", 80, 0, 100, 5, s -> {
                        DynamicShadowRenderer.darkFadeStrength = s / 100f;
                        return s + "%";
                    });

                    // God-rays / Dynamic Lights
                    // (renderers not available in this build)
                });
            }

            // Restore persisted settings
            DynamicShadowRenderer.graphicsQuality    = Core.settings.getInt ("graphics_quality",            2);
            DynamicShadowRenderer.enabled            = Core.settings.getBool("dynamic_shadows_enabled",     true);
            DynamicShadowRenderer.dayNightCycle      = Core.settings.getBool("day_night_cycle",             true);
            DynamicShadowRenderer.unitShadowsEnabled = Core.settings.getBool("dynamic_unit_shadows",        true);
            int pScaleVal = Core.settings.getInt("prop_shadow_scale", 100);
            DynamicShadowRenderer.propShadowScale    = pScaleVal / 100f;
            DynamicShadowRenderer.oldShadowsEnabled  = (pScaleVal == 0);
            DynamicShadowRenderer.SHADOW_LENGTH      = Core.settings.getInt ("shadow_length",               10);
            DynamicShadowRenderer.SHADOW_ALPHA       = Core.settings.getInt ("shadow_opacity_percent",       45) / 100f;
            DynamicShadowRenderer.blurRadius         = Core.settings.getInt ("blur_radius",                  35) / 10f;
            DynamicShadowRenderer.shadowTint         = Core.settings.getInt ("shadow_tint_percent",          60) / 100f;
            DynamicShadowRenderer.contactShadow      = Core.settings.getInt ("contact_shadow_percent",       45) / 100f;
            DynamicShadowRenderer.darkFadeStrength   = Core.settings.getInt ("dark_fade_percent",            80) / 100f;

            // Apply unit shadows initial configuration
            DynamicShadowRenderer.updateUnitShadows();
        });

        Events.on(EventType.WorldLoadEvent.class, e -> {
            DynamicShadowRenderer.ChunkCache.init();
        });
        Events.on(EventType.BlockBuildEndEvent.class, e -> {
            if (e.tile != null) {
                DynamicShadowRenderer.ChunkCache.invalidateTile(e.tile.x, e.tile.y);
            }
        });

        Events.run(EventType.Trigger.draw, () -> DynamicShadowRenderer.weatherMult = 1f);
        Events.run(EventType.Trigger.draw, DynamicShadowRenderer::queue);
    }
}
