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
            mindustry.Vars.ui.settings.graphics.checkPref("dynamic_lights_standalone", true, val -> {
                DynamicShadowRenderer.enabled = val;
            });
            DynamicShadowRenderer.enabled = Core.settings.getBool("dynamic_lights_standalone", true);
        });

        Events.run(EventType.Trigger.draw, () -> {
            DynamicShadowRenderer.weatherMult = 1f;
        });
        
        Events.run(EventType.Trigger.draw, DynamicShadowRenderer::queue);
    }
}
