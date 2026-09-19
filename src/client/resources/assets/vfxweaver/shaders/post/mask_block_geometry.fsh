#version 330

// The block-geometry contribution (expanded design). Rendered into the per-mask geometry scratch
// with the vanilla core/position_color vertex shader and the POSITION_COLOR format, so it reuses
// the real block-overlay model path (baked model quads, camera-relative pose, DynamicTransforms/
// Projection). Every rasterised fragment is full coverage; the coverage shader samples this
// scratch as a block leaf's coverage. It is NOT a per-pixel block lookup.

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    fragColor = vec4(1.0, 0.0, 0.0, 1.0);
}
