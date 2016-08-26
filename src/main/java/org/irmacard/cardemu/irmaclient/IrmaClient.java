package org.irmacard.cardemu.irmaclient;

import android.util.Patterns;

import org.irmacard.api.common.ClientQr;
import org.irmacard.api.common.DisclosureProofRequest;
import org.irmacard.api.common.IssuingRequest;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.DisclosureChoice;
import org.irmacard.cardemu.disclosuredialog.SessionDialogFragment;

import java.util.Arrays;
import java.util.List;

public abstract class IrmaClient implements SessionDialogFragment.SessionDialogListener {
	/** Specifies the current state of the instance. */
	public enum Status {
		CONNECTED, COMMUNICATING, DONE
	}

	/** Specifies what action the instance is performing. */
	public enum Action {
		DISCLOSING, ISSUING, UNKNOWN
	}

	protected Action action = Action.UNKNOWN;
	protected IrmaClientHandler handler;

	protected IrmaClient(IrmaClientHandler handler) {
		this.handler = handler;
		this.handler.setIrmaClient(this);
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
	public static void NewSession(String qrcontent, IrmaClientHandler handler) {
		// Decide on the protocol version and the URL to connect to
		ClientQr qr;
		try {
			qr = GsonUtil.getGson().fromJson(qrcontent, ClientQr.class);
		} catch (Exception e) {
			handler.onFailure(Action.UNKNOWN, "Not an IRMA session", null, "Content: " + qrcontent);
			return;
		}

		NewSession(qr, handler);
	}

	public static void NewSession(ClientQr qr, IrmaClientHandler handler) {
		List<String> supportedVersions = Arrays.asList("2.0", "2.1");

		String url = qr.getUrl();
		String protocolVersion = null;

		if (supportedVersions.contains(qr.getVersion()))
			protocolVersion = qr.getVersion();
		if (supportedVersions.contains(qr.getMaxVersion()))
			protocolVersion = qr.getMaxVersion();

		if (protocolVersion == null) {
			handler.onFailure(Action.UNKNOWN, "Protocol not supported", null,
					qr.getVersion() != null ? "Version: " + qr.getVersion() : "No version specified.");
			return;
		}

		// Check URL validity
		if (!Patterns.WEB_URL.matcher(url).matches()) {
			handler.onFailure(Action.UNKNOWN, "Protocol not supported", null,
					qr.getUrl() != null ? "URL: " + qr.getUrl() : "No URL specified.");
			return;
		}

		// We have a valid URL: let's go!
		new JsonIrmaClient(url, handler, protocolVersion);
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

