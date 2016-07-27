package org.irmacard.cardemu.identifiers;

import org.irmacard.credentials.info.ObjectIdentifier;

import java.io.Serializable;

/**
 * Identifies an instance of an {@link ObjectIdentifier} T.
 * Se also {@link IdemixAttributeIdentifier} and {@link IdemixCredentialIdentifier}
 * @param <T> A type extending {@link ObjectIdentifier}
 */
public abstract class IdemixIdentifier<T extends ObjectIdentifier> implements Serializable {
	private static final long serialVersionUID = -6152302145335181746L;

	protected T identifier;
	protected int index;
	protected int count;

	public IdemixIdentifier(T identifier, int index, int count) {
		this.identifier = identifier;
		this.index = index;
		this.count = count;
	}

	public T getIdentifier() {
		return identifier;
	}

	public int getIndex() {
		return index;
	}

	public int getCount() {
		return count;
	}

	/**
	 * Get the title of this instance as it should be displayed in the UI
	 */
	public String getUiTitle() {
		return getUiTitle(true);
	}

	/**
	 * Get the title of this instance as it should be displayed in the UI,
	 * optionally appending the index suffix.
	 */
	public abstract String getUiTitle(boolean includeIndex);

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		IdemixIdentifier<?> that = (IdemixIdentifier<?>) o;

		if (index != that.index) return false;
		return identifier.equals(that.identifier);

	}

	@Override
	public int hashCode() {
		int result = identifier.hashCode();
		result = 31 * result + index;
		return result;
	}
}
