package org.irmacard.cardemu.identifiers;

import org.irmacard.credentials.idemix.IdemixCredential;
import org.irmacard.credentials.info.CredentialIdentifier;

/**
 * Identifies a {@link IdemixCredential} instance.
 */
public class IdemixCredentialIdentifier extends IdemixIdentifier<CredentialIdentifier> {
	private static final long serialVersionUID = 8568874143039495380L;

	public IdemixCredentialIdentifier(CredentialIdentifier identifier, int hashCode) {
		super(identifier, hashCode);
	}

	@Override
	public String getUiTitle() {
		return identifier.getIssuerName() + " - " + identifier.getCredentialName();
	}
}
