package org.irmacard.cardemu.protocols;

import android.util.Patterns;
import org.irmacard.api.common.ClientQr;
import org.irmacard.api.common.DisclosureProofRequest;
import org.irmacard.api.common.IssuingRequest;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.DisclosureChoice;
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
	public abstract void disclose(DisclosureProofRequest request, DisclosureChoice disclosureChoice);

	/**
	 * Informs the session that this session is to be deleted.
	 */
	abstract public void deleteSession();

	/**
	 * Perform an issuing session.
	 * @param request The request containing which attributes we will receive, and which we have to disclose
	 */
	abstract protected void finishIssuance(IssuingRequest request, DisclosureChoice disclosureChoice);

	/**
	 * Cancel the current session.
	 */
	public void cancelSession() {
		handler.onCancelled(action);
	}

	/**
	 * Create a new session
	 * @param qrcontent Contents of the QR code, containing the server to connect to and protocol version number
	 * @param handler The handler to report feedback to
	 */
	public static void NewSession(String qrcontent, ProtocolHandler handler) {
		// Decide on the protocol version and the URL to connect to
		ClientQr qr;
		try {
			qr = GsonUtil.getGson().fromJson(qrcontent, ClientQr.class);
		} catch (Exception e) {
			handler.onFailure(ProtocolHandler.Action.UNKNOWN, "Not an IRMA session", null, "Content: " + qrcontent);
			return;
		}

		NewSession(qr, handler);
	}

	public static void NewSession(ClientQr qr, ProtocolHandler handler) {
		String protocolVersion = qr.getVersion();
		String url = qr.getUrl();

		// Check URL validity
		if (!Patterns.WEB_URL.matcher(url).matches()) {
			handler.onFailure(ProtocolHandler.Action.UNKNOWN, "Protocol not supported", null,
					qr.getUrl() != null ? "URL: " + qr.getUrl() : "No URL specified.");
			return;
		}

		// We have a valid URL: let's go!
		Protocol protocol;
		switch (protocolVersion) {
			case "2.0":
				protocol = new JsonProtocol(url, handler);
				break;
			default: // TODO show warning message in case "1.0"
				handler.onFailure(ProtocolHandler.Action.UNKNOWN, "Protocol not supported", null,
						qr.getVersion() != null ? "Version: " + qr.getVersion() : "No version specified.");
				break;
		}
	}

	@Override
	public void onDiscloseOK(final DisclosureProofRequest request, DisclosureChoice disclosureChoice) {
		disclose(request, disclosureChoice);
	}

	@Override
	public void onDiscloseCancel() {
		cancelSession();
	}

	@Override
	public void onIssueOK(IssuingRequest request, DisclosureChoice disclosureChoice) {
		finishIssuance(request, disclosureChoice);
	}

	@Override
	public void onIssueCancel() {
		cancelSession();
	}
}

