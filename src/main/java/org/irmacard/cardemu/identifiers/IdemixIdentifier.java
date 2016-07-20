package org.irmacard.cardemu.identifiers;

import org.irmacard.credentials.info.ObjectIdentifier;
import org.irmacard.credentials.idemix.IdemixCredential;

import java.io.Serializable;

/**
 * Identifies an instance of an {@link ObjectIdentifier} T, by an {@link ObjectIdentifier}
 * and a hash code, see {@link #getHashCode()}. The hash code can be anything but currently only
 * {@link IdemixCredential#hashCode()} is used.
 * Se also {@link IdemixAttributeIdentifier} and {@link IdemixCredentialIdentifier}
 * @param <T> A type extending {@link ObjectIdentifier}
 */
public abstract class IdemixIdentifier<T extends ObjectIdentifier> implements Serializable {
	private static final long serialVersionUID = -6152302145335181746L;

	protected T identifier;
	protected int hashCode;

	public IdemixIdentifier(T identifier, int hashCode) {
		this.identifier = identifier;
		this.hashCode = hashCode;
	}

	public T getIdentifier() {
		return identifier;
	}

	public int getHashCode() {
		return hashCode;
	}

	/**
	 * Get the title of this instance as it should be displayed in the UI
	 * (without a count appended of how many there are of this type, as in
	 * {@link #getUiTitle(int, boolean)})
	 */
	public abstract String getUiTitle();

	/**
	 * Get the title of this instance as it should be displayed in the UI
	 * @param index Position in the list
	 * @param multiple If the position should be appended between brackets
	 */
	public String getUiTitle(int index, boolean multiple) {
		String title = getUiTitle();
		if (multiple)
			title += " (" + index + ")";
		return title;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		IdemixIdentifier<?> that = (IdemixIdentifier<?>) o;

		if (hashCode != that.hashCode) return false;
		return identifier.equals(that.identifier);
	}

	@Override
	public int hashCode() {
		int result = identifier.hashCode();
		result = 31 * result + hashCode;
		return result;
	}
}
