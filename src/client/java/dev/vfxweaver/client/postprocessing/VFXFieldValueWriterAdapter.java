package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.buffers.Std140Builder;
import dev.vfxweaver.field.VFXFieldValueWriter;

/**
 * Adapts the MC-free {@link VFXFieldValueWriter} to {@code Std140Builder}, keeping the field
 * packer free of {@code com.mojang.*} types.
 */
public final class VFXFieldValueWriterAdapter implements VFXFieldValueWriter {
	private final Std140Builder builder;

	public VFXFieldValueWriterAdapter(final Std140Builder builder) {
		this.builder = builder;
	}

	@Override
	public void putFloat(final float value) {
		this.builder.putFloat(value);
	}

	@Override
	public void putVec4(final float x, final float y, final float z, final float w) {
		this.builder.putVec4(x, y, z, w);
	}
}
