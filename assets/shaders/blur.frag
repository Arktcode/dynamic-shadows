varying vec4 v_color;
varying vec2 v_texCoord;
uniform sampler2D u_texture;
uniform vec2 u_resolution;
uniform float u_radius;

void main() {
    vec2 tex_offset = 1.0 / u_resolution;
    vec4 result = texture2D(u_texture, v_texCoord) * 0.227027;
    
    // Desenfoque radial rápido (Single pass soft shadow blur)
    result += texture2D(u_texture, v_texCoord + vec2(tex_offset.x * u_radius, 0.0)) * 0.15;
    result += texture2D(u_texture, v_texCoord - vec2(tex_offset.x * u_radius, 0.0)) * 0.15;
    result += texture2D(u_texture, v_texCoord + vec2(0.0, tex_offset.y * u_radius)) * 0.15;
    result += texture2D(u_texture, v_texCoord - vec2(0.0, tex_offset.y * u_radius)) * 0.15;
    
    // Diagonales
    result += texture2D(u_texture, v_texCoord + vec2(tex_offset.x, tex_offset.y) * u_radius) * 0.04;
    result += texture2D(u_texture, v_texCoord - vec2(tex_offset.x, tex_offset.y) * u_radius) * 0.04;
    result += texture2D(u_texture, v_texCoord + vec2(tex_offset.x, -tex_offset.y) * u_radius) * 0.04;
    result += texture2D(u_texture, v_texCoord - vec2(tex_offset.x, -tex_offset.y) * u_radius) * 0.04;

    gl_FragColor = result * v_color;
}
