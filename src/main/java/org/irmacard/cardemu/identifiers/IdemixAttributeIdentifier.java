package org.irmacard.cardemu.identifiers;

import org.irmacard.credentials.idemix.IdemixCredential;
import org.irmacard.credentials.info.AttributeIdentifier;

/**
 * Identifies an attribute of an {@link IdemixCredential} instance
 */
public class IdemixAttributeIdentifier extends IdemixIdentifier<AttributeIdentifier> {
	private static final long serialVersionUID = 2217867985330335002L;

	public IdemixAttributeIdentifier(AttributeIdentifier identifier, int hashCode) {
		super(identifier, hashCode);
	}

	public IdemixCredentialIdentifier getIdemixCredentialIdentifier() {
		return new IdemixCredentialIdentifier(identifier.getCredentialIdentifier(), hashCode);
	}

	@Override
	public String getUiTitle() {
		if (identifier.isCredential())
			return identifier.getIssuerName() + " - " + identifier.getCredentialName();
		else
			return identifier.getIssuerName() + " - " + identifier.getAttributeName();
	}
}
