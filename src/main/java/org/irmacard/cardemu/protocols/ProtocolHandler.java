package org.irmacard.cardemu.protocols;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.Html;
import android.view.View;
import org.irmacard.api.common.AttributeDisjunction;
import org.irmacard.api.common.AttributeDisjunctionList;
import org.irmacard.api.common.DisclosureProofRequest;
import org.irmacard.api.common.IssuingRequest;
import org.irmacard.api.common.exceptions.ApiErrorMessage;
import org.irmacard.cardemu.CredentialManager;
import org.irmacard.cardemu.disclosuredialog.DisclosureInformationActivity;
import org.irmacard.cardemu.disclosuredialog.SessionDialogFragment;

import java.util.List;

/**
 * Glue between {@link Protocol} instances and their users, allowing them to specify the
 * behaviour of the Protocol instance. At minimum, inheritors must
 * implement the on-methods, specifying what to do when the status changes, or when the
 * session is successful, cancelled or failed. If it is given an activity in its constructor,
 * it will also ask permission of the user before disclosing or issuing (see
 * {@link #askForVerificationPermission(DisclosureProofRequest)} and
 * {@link #askForIssuancePermission(IssuingRequest)}.
 */
public abstract class ProtocolHandler implements SessionDialogFragment.SessionDialogListener {
	/** Specifies the current state of the protocol. */
	public enum Status {
		CONNECTED, COMMUNICATING, DONE
	}

	/** Specifies what action the protocol is performing. */
	public enum Action {
		DISCLOSING, ISSUING, UNKNOWN
	}

	private Activity activity;
	private Protocol protocol;

	/**
	 * Construct a new handler. No permission will be asked of the user before disclosing or
	 * issuing (use {@link #ProtocolHandler(Activity)} if this is necessary).
	 */
	public ProtocolHandler() {}

	/**
	 * Construct a new handler. Before disclosing or issuing, a dialog will ask the user for permission
	 * (see {@link #askForVerificationPermission(DisclosureProofRequest)} and
	 * {@link #askForIssuancePermission(IssuingRequest)}).
	 * @param activity The activity to use for creation of the dialogs
	 */
	public ProtocolHandler(Activity activity) {
		this.activity = activity;
	}

	abstract public void onStatusUpdate(Action action, Status status);
	abstract public void onSuccess(Action action);
	abstract public void onCancelled(Action action);
	abstract public void onFailure(Action action, String message, ApiErrorMessage error);

	public void setProtocol(Protocol protocol) {
		this.protocol = protocol;
	}

	/**
	 * If this handler was given an activity in its constructor, ask the user if he is OK with disclosing
	 * the attributes specified in the request. If she agrees then they are disclosed immediately; if she
	 * does not, or the request cannot be satisfied, then the connection is aborted after closing the dialog.
	 * @param request The disclosure request
	 */
	public void askForVerificationPermission(final DisclosureProofRequest request) {
		if (activity == null) { // Can't show dialogs in this case
			onDiscloseOK(request);
			return;
		}

		List<AttributeDisjunction> missing = CredentialManager.getUnsatisfiableDisjunctions(request.getContent());

		if (missing.isEmpty()) {
			SessionDialogFragment dialog = SessionDialogFragment.newDiscloseDialog(request, this);
			dialog.show(activity.getFragmentManager(), "disclosuredialog");
		}
		else {
			showUnsatisfiableRequestDialog(missing, request, Action.DISCLOSING);
		}
	}

	/**
	 * If this handler was given an activity in its constructor, ask the user if he is OK with issuance
	 * @param request The issuance request
	 */
	public void askForIssuancePermission(final IssuingRequest request) {
		if (activity == null) { // Can't show dialogs in this case
			onIssueOK(request);
			return;
		}

		AttributeDisjunctionList requiredAttributes = request.getRequiredAttributes();
		if (!requiredAttributes.isEmpty()) {
			List<AttributeDisjunction> missing = CredentialManager.getUnsatisfiableDisjunctions(requiredAttributes);

			if (!missing.isEmpty()) {
				showUnsatisfiableRequestDialog(missing,
						new DisclosureProofRequest(null, null, requiredAttributes), // FIXME this is ugly
						Action.ISSUING);
				return;
			}
		}

		SessionDialogFragment dialog = SessionDialogFragment.newIssueDialog(request, this);
		dialog.show(activity.getFragmentManager(), "issuingdialog");
	}

	public void showUnsatisfiableRequestDialog(List<AttributeDisjunction> missing,
	                                           final DisclosureProofRequest request, final Action action) {
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
						onCancelled(action);
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
						onCancelled(action);
						Intent intent = new Intent(activity, DisclosureInformationActivity.class);
						intent.putExtra("request", request);
						activity.startActivity(intent);
					}
				});
			}
		});

		dialog.show();
	}

	@Override public void onDiscloseOK(DisclosureProofRequest request) {
		protocol.onDiscloseOK(request);
	}

	@Override public void onDiscloseCancel() {
		protocol.onDiscloseCancel();
	}

	@Override public void onIssueOK(IssuingRequest request) {
		protocol.onIssueOK(request);
	}

	@Override public void onIssueCancel() {
		protocol.onIssueCancel();
	}
}
