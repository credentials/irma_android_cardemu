package org.irmacard.cardemu.protocols;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.Html;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import org.irmacard.api.common.IssuingRequest;
import org.irmacard.cardemu.CredentialManager;
import org.irmacard.cardemu.MainActivity;
import org.irmacard.cardemu.disclosuredialog.SessionDialogFragment;
import org.irmacard.cardemu.disclosuredialog.DisclosureInformationActivity;
import org.irmacard.api.common.AttributeDisjunction;
import org.irmacard.api.common.DisclosureProofRequest;
import org.irmacard.api.common.ClientQr;
import org.irmacard.api.common.util.GsonUtil;

import java.util.ArrayList;
import java.util.List;

public abstract class Protocol implements SessionDialogFragment.SessionDialogListener {
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
		activity.setState(MainActivity.STATE_IDLE);
	}

	private static final String TAG = "CardEmuProtocol";

	protected MainActivity activity;

	// Automatically return to browser when launched using a URL
	private boolean launchedFromBrowser;

	@Override
	public void onIssueOK(IssuingRequest request) {
	}

	@Override
	public void onIssueCancel() {
		cancelSession();
		done();
	}

	/**
	 * Create a new session
	 * @param qrcontent Contents of the QR code, containing the server to connect to and protocol version number
	 * @param activity The activity to report progress to
	 */
	public static void NewSession(String qrcontent, MainActivity activity, boolean launchedFromBrowser) {
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
		protocol.launchedFromBrowser = launchedFromBrowser;
		protocol.connect(url);
	}

	private List<AttributeDisjunction> getUnsatisfiableDisjunctions(List<AttributeDisjunction> disjunctions) {
		List<AttributeDisjunction> missing = new ArrayList<>();
		for (AttributeDisjunction disjunction : disjunctions) {
			if (CredentialManager.getCandidates(disjunction).isEmpty()) {
				missing.add(disjunction);
			}
		}
		return missing;
	}

	private void showUnsatisfiableRequestDialog(List<AttributeDisjunction> missing, final DisclosureProofRequest request) {
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

		final AlertDialog dialog = new AlertDialog.Builder(activity)
				.setTitle("Missing attributes")
				.setMessage(Html.fromHtml(message))
				.setPositiveButton("OK", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						cancelSession();
						done();
					}
				})
				.setNeutralButton("More Information", null)
				.create();

		// Set the listener for the More Info button here, so that it does not close the dialog
		dialog.setOnShowListener(new DialogInterface.OnShowListener() {
			@Override
			public void onShow(DialogInterface d) {
				dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						cancelSession();
						Intent intent = new Intent(activity, DisclosureInformationActivity.class);
						intent.putExtra("request", request);
						activity.startActivity(intent);
					}
				});
			}
		});

		dialog.show();
	}

	public void askForIssuancePermission(final IssuingRequest request) {
		ArrayList<AttributeDisjunction> requiredAttributes = request.getRequiredAttributes();
		if (!requiredAttributes.isEmpty()) {
			List<AttributeDisjunction> missing = getUnsatisfiableDisjunctions(requiredAttributes);

			if (!missing.isEmpty()) {
				showUnsatisfiableRequestDialog(missing,
						new DisclosureProofRequest(null, null, requiredAttributes)); // FIXME this is ugly
				return;
			}
		}

		SessionDialogFragment dialog = SessionDialogFragment.newIssueDialog(request, this);
		dialog.show(activity.getFragmentManager(), "issuingdialog");
	}

	/**
	 * Asks the user if he is OK with disclosing the attributes specified in the request. If she agrees then
	 * they are disclosed immediately; if she does not, or the request cannot be satisfied, then the connection is
	 * aborted after disclosing the dialog.
	 * @param request The disclosure request
	 */
	public void askForVerificationPermission(final DisclosureProofRequest request) {
		List<AttributeDisjunction> missing = getUnsatisfiableDisjunctions(request.getContent());

		if (missing.isEmpty()) {
			SessionDialogFragment dialog = SessionDialogFragment.newDiscloseDialog(request, this);
			dialog.show(activity.getFragmentManager(), "disclosuredialog");
		}
		else {
			showUnsatisfiableRequestDialog(missing, request);
		}
	}

	@Override
	public void onDiscloseOK(final DisclosureProofRequest request) {
		disclose(request);
	}

	@Override
	public void onDiscloseCancel() {
		cancelSession();
		done();
	}

	public void done() {
		if(launchedFromBrowser) {
			launchedFromBrowser = false;
			Log.i(TAG, "Programatically returning to browser");
			activity.onBackPressed();
		}
	}
}

