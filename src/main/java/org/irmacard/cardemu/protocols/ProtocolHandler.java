package org.irmacard.cardemu.protocols;

import android.app.Activity;

public abstract class ProtocolHandler {
	public enum Status {
		CONNECTED, COMMUNICATING, DONE
	}

	public enum Action {
		DISCLOSING, ISSUING, UNKNOWN
	}

	abstract public void onStatusUpdate(Action action, Status status);

	abstract public void onSuccess(Action action);
	abstract public void onCancelled(Action action);
	abstract public void onFailure(Action action, String message);
}
