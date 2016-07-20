package org.irmacard.cardemu.identifiers;

import org.irmacard.credentials.info.ObjectIdentifier;

import java.util.ArrayList;
import java.util.Collection;

/**
 * A list of Idemix attribute or credential identifiers, mainly for keeping track of the count
 * and position of instances, and obtaining properly formatted string representations.
 * @param <T>
 */
public class IdemixIdentifierList<T extends ObjectIdentifier> extends ArrayList<IdemixIdentifier<T>> {
	private static final long serialVersionUID = 4398852134330106331L;

	public IdemixIdentifierList() {
		super();
	}

	public IdemixIdentifierList(Collection<? extends IdemixIdentifier<T>> collection) {
		super(collection);
	}

	/**
	 * Returns how many of the specified identifier we have
	 */
	public int count(T identifier) {
		int count = 0;
		for (IdemixIdentifier<T> id : this)
			if (id.getIdentifier().equals(identifier))
				++count;

		return count;
	}

	/**
	 * Returns how many instances we have that have the same {@link IdemixIdentifier#getIdentifier()})
	 */
	public int count(IdemixIdentifier<T> idemixIdentifier) {
		return count(idemixIdentifier.getIdentifier());
	}

	/**
	 * Given an {@link IdemixIdentifier}, returns the position in the sublist
	 * of items having the same {@link IdemixIdentifier#getIdentifier()}, or 0
	 * if the specified IdemixIdentifier is not contained in this instance.
	 */
	public int position(IdemixIdentifier<T> idemixId) {
		int count = 0;
		for (IdemixIdentifier<T> id : this) {
			if (id.getIdentifier().equals(idemixId.getIdentifier()))
				++count;
			if (id.equals(idemixId))
				return count;
		}

		return 0;
	}

	/**
	 * Get the element a the specified index, cast to S
	 * @param index The index of the element to fetch
	 * @param <S> Class to cast the element to, should be a subclass of IdemixIdentifier&lt;T&gt;
	 * @return The element
	 * @throws ClassCastException
	 */
	@SuppressWarnings("unchecked")
	public <S extends IdemixIdentifier<T>> S getIdentifer(int index) {
		return (S) get(index);
	}

	/**
	 * Get a string representation of the specified {@link IdemixIdentifier} for dispaying
	 * in the UI, with its position in brackets appended if we have more than one of its kind.
	 */
	public String getUiTitle(IdemixIdentifier<T> idemixId) {
		return idemixId.getUiTitle(position(idemixId), count(idemixId) > 1);
	}
}
