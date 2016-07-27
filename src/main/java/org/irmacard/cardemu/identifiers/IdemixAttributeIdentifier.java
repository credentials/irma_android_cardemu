package org.irmacard.cardemu.identifiers;

import org.irmacard.credentials.idemix.IdemixCredential;
import org.irmacard.credentials.info.AttributeIdentifier;

/**
 * Identifies an attribute of an {@link IdemixCredential} instance
 */
public class IdemixAttributeIdentifier extends IdemixIdentifier<AttributeIdentifier> {
	private static final long serialVersionUID = 2217867985330335002L;
	private transient IdemixCredentialIdentifier ici;

	public IdemixAttributeIdentifier(AttributeIdentifier identifier, int index, int count) {
		super(identifier, index, count);
		ici = new IdemixCredentialIdentifier(identifier.getCredentialIdentifier(), index, count);
	}

	public IdemixCredentialIdentifier getIdemixCredentialIdentifier() {
		return ici;
	}

	@Override
	public String getUiTitle(boolean includeIndex) {
		if (identifier.isCredential())
			return ici.getUiTitle(includeIndex);
		else
			return ici.getUiTitle(includeIndex) + " - " + identifier.getAttributeName();
	}
}
