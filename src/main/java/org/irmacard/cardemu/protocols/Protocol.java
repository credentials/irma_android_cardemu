package org.irmacard.cardemu.protocols;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.Html;
import android.util.Patterns;
import org.irmacard.cardemu.CredentialManager;
import org.irmacard.cardemu.MainActivity;
import org.irmacard.cardemu.disclosuredialog.DisclosureDialogFragment;
import org.irmacard.cardemu.disclosuredialog.DisclosureInformationActivity;
import org.irmacard.verification.common.AttributeDisjunction;
import org.irmacard.verification.common.DisclosureProofRequest;
import org.irmacard.verification.common.DisclosureQr;
import org.irmacard.verification.common.util.GsonUtil;

import java.util.ArrayList;
import java.util.List;

public abstract class Protocol implements DisclosureDialogFragment.DisclosureDialogListener {
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
	 * Cancel the current disclosure.
	 */
	public void cancelDisclosure() {
		activity.setState(MainActivity.STATE_IDLE);
	}

	protected MainActivity activity;

	/**
	 * Create a new session
	 * @param qrcontent Contents of the QR code, containing the server to connect to and protocol version number
	 * @param activity The activity to report progress to
	 * @return A new Protocol instance of the appropriate version
	 */
	public static void NewSession(String qrcontent, MainActivity activity) {
		// Decide on the protocol version and the URL to connect to
		String url, protocolVersion;
		try {
			DisclosureQr contents = GsonUtil.getGson().fromJson(qrcontent, DisclosureQr.class);
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
			activity.setFeedback("Protocol not supported", "failure");
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
				activity.setFeedback("Protocol not supported", "failure");
				return;
		}

		protocol.activity = activity;
		protocol.connect(url);
	}

	/**
	 * Asks the user if he is OK with disclosing the attributes specified in the request. If she agrees then
	 * they are disclosed immediately; if she does not, or the request cannot be satisfied, then the connection is
	 * aborted after disclosing the dialog.
	 * @param request The disclosure request
	 */
	public void askForVerificationPermission(final DisclosureProofRequest request) {
		List<AttributeDisjunction> missing = new ArrayList<>();
		for (AttributeDisjunction disjunction : request.getContent()) {
			if (CredentialManager.getCandidates(disjunction).isEmpty()) {
				missing.add(disjunction);
			}
		}

		if (missing.isEmpty()) {
			DisclosureDialogFragment dialog = DisclosureDialogFragment.newInstance(request, this);
			dialog.show(activity.getFragmentManager(), "disclosuredialog");
		}
		else {
			String message = "The verifier requires attributes of the following kind: ";
			int count = 0;
			int max = missing.size();
			for (AttributeDisjunction disjunction : missing) {
				count++;
				message += "<b>" + disjunction.getLabel() + "</b>";
				if (count < max - 1 || count == max)
					message += ", ";
				if (count == max - 1 && max > 1)
					message += " and ";
			}
			message += " but you do not have the appropriate attributes.";

			new AlertDialog.Builder(activity)
					.setTitle("Missing attributes")
					.setMessage(Html.fromHtml(message))
					.setPositiveButton("OK", new DialogInterface.OnClickListener() {
						@Override public void onClick(DialogInterface dialog, int which) {
							cancelDisclosure();
						}
					})
					.setNeutralButton("More Information", new DialogInterface.OnClickListener() {
						@Override public void onClick(DialogInterface dialog, int which) {
							cancelDisclosure();
							Intent intent = new Intent(activity, DisclosureInformationActivity.class);
							intent.putExtra("request", request);
							activity.startActivity(intent);
						}
					})
					.show();
		}
	}

	@Override
	public void onDiscloseOK(final DisclosureProofRequest request) {
		disclose(request);
	}

	@Override
	public void onDiscloseCancel() {
		cancelDisclosure();
	}
}

