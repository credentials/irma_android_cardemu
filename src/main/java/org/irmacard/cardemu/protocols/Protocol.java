package org.irmacard.cardemu.protocols;

import android.app.Activity;
import android.util.Patterns;
import org.irmacard.api.common.ClientQr;
import org.irmacard.api.common.DisclosureProofRequest;
import org.irmacard.api.common.IssuingRequest;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.disclosuredialog.SessionDialogFragment;

public abstract class Protocol implements SessionDialogFragment.SessionDialogListener {
	protected ProtocolHandler.Action action = ProtocolHandler.Action.UNKNOWN;
	protected ProtocolHandler handler;

	protected Protocol(ProtocolHandler handler) {
		this.handler = handler;
		this.handler.setProtocol(this);
	}

	/**
	 * Perform a disclosure.
	 * @param request The request containing the attributes to show (each of its disjunctions should have
	 *                a selected attribute).
	 */
	abstract public void disclose(final DisclosureProofRequest request);

	/**
	 * Perform an issuing session.
	 * @param request The request containing which attributes we will receive, and which we have to disclose
	 */
	abstract protected void finishIssuance(final IssuingRequest request);

	/**
	 * Cancel the current session.
	 */
	public void cancelSession() {
		handler.onCancelled(action);
	}

	/**
	 * Create a new session
	 * @param qrcontent Contents of the QR code, containing the server to connect to and protocol version number
	 * @param activity The activity to report progress to
	 */
	public static void NewSession(String qrcontent, ProtocolHandler handler) {
		// Decide on the protocol version and the URL to connect to
		ClientQr qr;
		try {
			qr = GsonUtil.getGson().fromJson(qrcontent, ClientQr.class);
		} catch (Exception e) {
			// If the contents of the QR code is not JSON, it is probably just a URL to an
			// APDU-based IRMA server.
			qr = new ClientQr("1.0", qrcontent);
		}

		NewSession(qr, handler);
	}

	public static void NewSession(ClientQr qr, ProtocolHandler handler) {
		String protocolVersion = qr.getVersion();
		String url = qr.getUrl();

		// Check URL validity
		if (!Patterns.WEB_URL.matcher(url).matches()) {
			handler.onFailure(ProtocolHandler.Action.UNKNOWN, "Protocol not supported", null);
			return;
		}

		// We have a valid URL: let's go!
		Protocol protocol;
		switch (protocolVersion) {
			case "2.0":
				protocol = new JsonProtocol(url, handler);
				break;
			default: // TODO show warning message in case "1.0"
				handler.onFailure(ProtocolHandler.Action.UNKNOWN, "Protocol not supported", null);
				break;
		}
	}

	@Override
	public void onDiscloseOK(final DisclosureProofRequest request) {
		disclose(request);
	}

	@Override
	public void onDiscloseCancel() {
		cancelSession();
	}

	@Override
	public void onIssueOK(IssuingRequest request) {
		finishIssuance(request);
	}

	@Override
	public void onIssueCancel() {
		cancelSession();
	}
}

