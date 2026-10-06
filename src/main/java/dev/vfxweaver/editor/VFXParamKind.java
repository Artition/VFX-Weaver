package dev.vfxweaver.editor;

/**
 * The value kind of an editable effect parameter. Drives how the visual node editor
 * renders the input widget and how a value is validated against {@link VFXParamDescriptor}.
 */
public enum VFXParamKind {
	/** A continuous scalar. */
	FLOAT,
	/** A whole number. */
	INT,
	/** A boolean toggle. */
	BOOL,
	/** A choice out of {@link VFXParamDescriptor#enumValues()}. */
	ENUM,
	/** An RGB colour. */
	COLOR_RGB,
	/** A world-space position. */
	POSITION
}
