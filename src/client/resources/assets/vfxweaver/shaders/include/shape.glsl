// World-projected shape dispatcher for surface_pattern. Owned by the shared shape/field library:
// the distance maths is shapes.glsl's `vfx_shape_sdf` and the fill/softness is shapes.glsl's
// `vfx_shape_coverage` — this wrapper only applies the figure ordinal, the cell rotation and the
// repeat/tile modifier. No consumer re-implements a signed-distance function.
//
//   figure   VFXShapeFigure ordinal: circle = 0, ellipse = 1, rect = 2, polygon = 3
//   fill     VFXShapeFill ordinal:   solid = 0,  stroke = 1
//   p        cell-local coordinate (the shader divides the world XZ by `tile_scale` first)
//   repeat   [nx, ny] tile counts, each 1..64; a grid is any figure with a repeat > 1
//   shape0   (radius, radius_x, radius_y, half_width)
//   shape1   (half_height, corner_radius, sides, rotation_degrees)
#moj_import <vfxweaver:shapes.glsl>

float vfx_shape_pattern_coverage(int figure, int fill, vec2 p, vec2 repeat,
                                 vec4 shape0, vec4 shape1, float strokeWidth, float softness) {
	float rot = radians(shape1.w);
	float c = cos(rot);
	float s = sin(rot);
	vec2 local = mat2(c, -s, s, c) * p;
	if (repeat.x > 1.0 || repeat.y > 1.0) {
		local = fract(local * repeat) - 0.5;
	}
	float sdf = vfx_shape_sdf(figure, local, shape0.x, shape0.y, shape0.z, shape0.w, shape1.x, shape1.y, shape1.z);
	return vfx_shape_coverage(sdf, fill, strokeWidth, softness);
}
