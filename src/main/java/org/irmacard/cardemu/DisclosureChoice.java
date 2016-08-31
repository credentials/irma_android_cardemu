package org.irmacard.cardemu;

import org.irmacard.api.common.SessionRequest;
import org.irmacard.cardemu.identifiers.IdemixAttributeIdentifier;

import java.io.Serializable;
import java.util.ArrayList;

/**
 * A list of attributes that have been selected for disclosure
 */
public class DisclosureChoice implements Serializable {
	private static final long serialVersionUID = -821313989907752394L;

	private SessionRequest request;
	private ArrayList<IdemixAttributeIdentifier> attributes;

	public DisclosureChoice(SessionRequest request) {
		this.request = request;
		this.attributes = new ArrayList<>();
	}

	public SessionRequest getRequest() {
		return request;
	}

	public ArrayList<IdemixAttributeIdentifier> getAttributes() {
		return attributes;
	}
}
