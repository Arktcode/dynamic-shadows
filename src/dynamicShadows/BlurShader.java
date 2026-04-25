package dynamicShadows;

import arc.Core;
import arc.graphics.gl.Shader;
import mindustry.Vars;

public class BlurShader extends Shader {
    public float radius = 2.0f; // Intensidad del desenfoque

    public BlurShader() {
        super(
                Vars.tree.get("shaders/blur.vert"),
                Vars.tree.get("shaders/blur.frag"));
    }

    @Override
    public void apply() {
        setUniformf("u_radius", radius);
        setUniformf("u_resolution", Core.graphics.getWidth(), Core.graphics.getHeight());
    }
}
