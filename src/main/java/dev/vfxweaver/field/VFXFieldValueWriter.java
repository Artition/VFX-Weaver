package dev.vfxweaver.field;

/**
 * The field-program write target. The client adapts {@code com.mojang.blaze3d.buffers.Std140Builder}
 * to this interface so the packer stays free of Minecraft types and remains unit-checkable.
 */
public interface VFXFieldValueWriter {
	/** Appends one scalar (std140, 4 bytes). */
	void putFloat(float value);

	/** Appends one vec4 (std140, 16-byte aligned). */
	void putVec4(float x, float y, float z, float w);
}
