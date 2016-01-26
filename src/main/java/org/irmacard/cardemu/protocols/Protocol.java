package org.irmacard.cardemu.protocols;

import android.app.Activity;
import android.util.Patterns;
import org.irmacard.api.common.*;
import org.irmacard.cardemu.disclosuredialog.SessionDialogFragment;
import org.irmacard.api.common.util.GsonUtil;

public abstract class Protocol implements SessionDialogFragment.SessionDialogListener {
	protected Activity activity;
	protected ProtocolHandler.Action action = ProtocolHandler.Action.UNKNOWN;
	protected ProtocolHandler handler;

	/**
	 * Connect to the IRMA server at the specified URL
	 * @param url The server to connect to
	 */
	abstract public void connect(String url);

	/**
	 * Perform a disclosure.
	 * @param request The request containing the attributes to show (each of its disjunctions should have
	 *                a selected attribute).
	 */
	abstract public void disclose(final DisclosureProofRequest request);

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
	public static void NewSession(String qrcontent, Activity activity, ProtocolHandler handler) {
		// Decide on the protocol version and the URL to connect to
		String url, protocolVersion;
		try {
			ClientQr contents = GsonUtil.getGson().fromJson(qrcontent, ClientQr.class);
			protocolVersion = contents.getVersion();
			url = contents.getUrl();
		} catch (Exception e) {
			// If the contents of the QR code is not JSON, it is probably just a URL to an
			// APDU-based IRMA server.
			protocolVersion = "1.0";
			url = qrcontent;
		}

		// Check URL validity
		if (!Patterns.WEB_URL.matcher(url).matches()) {
			handler.onFailure(ProtocolHandler.Action.UNKNOWN, "Protocol not supported");
			return;
		}

		// We have a valid URL: let's go!
		Protocol protocol;
		switch (protocolVersion) {
			case "1.0":
				protocol = new APDUProtocol();
				break;
			case "2.0":
				protocol = new JsonProtocol();
				break;
			default:
				handler.onFailure(ProtocolHandler.Action.UNKNOWN, "Protocol not supported");
				return;
		}

		protocol.activity = activity;
		protocol.handler = handler;
		handler.setProtocol(protocol);
		protocol.connect(url);
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
	public void onIssueOK(IssuingRequest request) {}

	@Override
	public void onIssueCancel() {
		cancelSession();
	}
}

