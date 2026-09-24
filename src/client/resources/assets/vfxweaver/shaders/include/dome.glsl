#moj_import <vfxweaver:camera.glsl>

vec3 vfx_view_dir(vec2 uv, mat4 invViewProj, vec3 camPos) {
    vec3 farWorld = vfx_world_from_depth(uv, VFX_DEPTH_FAR_RAW, invViewProj);
    return normalize(farWorld - camPos);
}

vec2 vfx_dome_uv(vec3 direction) {
    float yaw = degrees(atan(-direction.x, direction.z));
    float pitch = degrees(-asin(clamp(direction.y, -1.0, 1.0)));
    return vec2((yaw + 180.0) / 360.0, (pitch + 90.0) / 180.0);
}

vec2 vfx_dome_uv_rotated(vec3 direction, float rotationDegrees) {
    float angle = radians(rotationDegrees);
    float c = cos(angle);
    float s = sin(angle);
    return vfx_dome_uv(vec3(c * direction.x - s * direction.z, direction.y, s * direction.x + c * direction.z));
}
