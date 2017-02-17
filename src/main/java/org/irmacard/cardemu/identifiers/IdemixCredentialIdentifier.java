package org.irmacard.cardemu.identifiers;

import org.irmacard.credentials.idemix.IdemixCredential;
import org.irmacard.credentials.info.CredentialIdentifier;

/**
 * Identifies a {@link IdemixCredential} instance.
 */
public class IdemixCredentialIdentifier extends IdemixIdentifier<CredentialIdentifier> {
	private static final long serialVersionUID = 8568874143039495380L;

	public IdemixCredentialIdentifier(CredentialIdentifier identifier, int index, int count) {
		super(identifier, index, count);
	}

	public String getUiTitle(boolean includeIndex) {
		String suffix = "";
		if (includeIndex && count > 1)
			suffix += " (" + (index+1) + ")";

		return getBaseTitle(identifier) + suffix;
	}

	public static String getBaseTitle(CredentialIdentifier identifier) {
		return identifier.getIssuerIdentifier().getIssuerDescription().getShortName()
				+ " - "
				+ identifier.getCredentialDescription().getName();
	}
}
