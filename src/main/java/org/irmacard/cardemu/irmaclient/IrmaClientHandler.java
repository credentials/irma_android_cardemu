package org.irmacard.cardemu.irmaclient;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.Html;
import android.view.View;

import org.irmacard.api.common.AttributeDisjunction;
import org.irmacard.api.common.AttributeDisjunctionList;
import org.irmacard.api.common.DisclosureRequest;
import org.irmacard.api.common.SessionRequest;
import org.irmacard.api.common.disclosure.DisclosureProofRequest;
import org.irmacard.api.common.exceptions.ApiErrorMessage;
import org.irmacard.api.common.issuing.IssuingRequest;
import org.irmacard.api.common.signatures.SignatureProofRequest;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.CredentialManager;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.disclosuredialog.DisclosureInformationActivity;
import org.irmacard.cardemu.disclosuredialog.SessionDialogFragment;
import org.irmacard.cardemu.irmaclient.IrmaClient.Action;
import org.irmacard.cardemu.irmaclient.IrmaClient.Status;
import org.irmacard.cardemu.pindialog.EnterPINDialogFragment;
import org.irmacard.cardemu.pindialog.EnterPINDialogFragment.PINDialogListener;

import java.util.List;

/**
 * Glue between {@link IrmaClient} instances and a {@link Activity} user, allowing it to specify the
 * behaviour of the IrmaClient instance. At minimum, inheritors must
 * implement the on-methods, specifying what to do when the status changes, or when the
 * session is successful, cancelled or failed. If it is given an activity in its constructor,
 * it will also ask permission of the user before disclosing or issuing (see
 * {@link #askForVerificationPermission(DisclosureProofRequest, String)} and
 * {@link #askForIssuancePermission(IssuingRequest, String)}.
 */
public abstract class IrmaClientHandler {
	protected final String TAG = "ProtocolHandler";

	private Activity activity;
	private IrmaClient irmaClient;

	/**
	 * Construct a new handler. Before disclosing or issuing, a dialog will ask the user for permission
	 * (see {@link #askForVerificationPermission(DisclosureProofRequest, String)} and
	 * {@link #askForIssuancePermission(IssuingRequest, String)}).
	 * @param activity The activity to use for creation of the dialogs
	 */
	public IrmaClientHandler(Activity activity) {
		this.activity = activity;
	}

	abstract public void onStatusUpdate(Action action, Status status);
	abstract public void onSuccess(Action action);
	abstract public void onCancelled(Action action);
	abstract public void onFailure(Action action, String message, ApiErrorMessage error, String techInfo);

	public void setIrmaClient(IrmaClient irmaClient) {
		this.irmaClient = irmaClient;
	}

	public void askForPermission(SessionRequest request, String requesterName) {
		if (request instanceof DisclosureProofRequest)
			askForVerificationPermission((DisclosureProofRequest) request, requesterName);
		if (request instanceof IssuingRequest)
			askForIssuancePermission((IssuingRequest) request, requesterName);
		if (request instanceof SignatureProofRequest)
			askForSignPermission((SignatureProofRequest) request, requesterName);
	}

	/**
	 * Ask the user if he is OK with disclosing
	 * the attributes specified in the request. If she agrees then they are disclosed immediately; if she
	 * does not, or the request cannot be satisfied, then the connection is aborted after closing the dialog.
	 * @param request The disclosure request
	 */
	public void askForVerificationPermission(final DisclosureProofRequest request, final String requesterName) {
		List<AttributeDisjunction> missing = CredentialManager.getUnsatisfiableDisjunctions(request.getContent());

		if (missing.isEmpty()) {
			SessionDialogFragment dialog = SessionDialogFragment.newDiscloseDialog(request, requesterName, irmaClient);
			dialog.show(activity.getFragmentManager(), "disclosuredialog");
		}
		else {
			showUnsatisfiableRequestDialog(missing, request, Action.DISCLOSING);
		}
	}

	/**
	 * If this handler was given an activity in its constructor, ask the user if he is OK with signing the message and
	 * the attributes specified in the request. If she agrees then they are signed immediately; if she
	 * does not, or the request cannot be satisfied, then the connection is aborted after closing the dialog.
	 * TODO maybe merge with askForVerificationPermission()
	 * @param request The disclosure request
	 */
	public void askForSignPermission(final SignatureProofRequest request, final String requesterName) {
		List<AttributeDisjunction> missing = CredentialManager.getUnsatisfiableDisjunctions(
				request.getContent());

		// TODO: check conditions and give feedback to user if they are not met

		if (missing.isEmpty()) {
			SessionDialogFragment dialog = SessionDialogFragment.newSignDialog(request, requesterName, irmaClient);
			dialog.show(activity.getFragmentManager(), "signdialog");
		}
		else {
			showUnsatisfiableRequestDialog(missing, request, Action.SIGNING);
		}
	}



	/**
	 * Ask the user if he is OK with issuance
	 * @param request The issuance request
	 */
	public void askForIssuancePermission(final IssuingRequest request, final String requesterName) {
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

		SessionDialogFragment dialog = SessionDialogFragment.newIssueDialog(request, requesterName, irmaClient);
		dialog.show(activity.getFragmentManager(), "issuingdialog");
	}

	private void showUnsatisfiableRequestDialog(List<AttributeDisjunction> missing,
												final DisclosureRequest request, final Action action) {
		String attrs = "";
		int count = 0;
		int max = missing.size();
		for (AttributeDisjunction disjunction : missing) {
			count++;
			attrs += "<b>" + disjunction.getLabel() + "</b>";
			if (count < max - 1 || count == max)
				attrs += ", ";
			if (count == max - 1 && max > 1)
				attrs += " " + activity.getString(R.string.and) +  " ";
		}

		final AlertDialog dialog = new AlertDialog.Builder(activity)
				.setTitle(R.string.missing_attributes_title)
				.setMessage(Html.fromHtml(activity.getString(R.string.missing_attributes_text, attrs)))
				.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						irmaClient.cancelSession();
					}
				})
				.setNeutralButton(R.string.more_information, null)
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
						intent.putExtra("disjunctions", GsonUtil.getGson().toJson(request.getContent()));
						intent.putExtra("issuing", action == Action.ISSUING);
						activity.startActivity(intent);
					}
				});
			}
		});

		dialog.show();
	}

	public Activity getActivity() {
		return activity;
	}

	/**
	 * Ask for a PIN using {@link EnterPINDialogFragment}.
	 * @param tries The amount of tries allowed to the user
	 * @param listener Will receive the PIN
     */
	public void verifyPin(int tries, PINDialogListener listener) {
		DialogFragment newFragment = EnterPINDialogFragment.getInstance(tries, listener);
		newFragment.show(activity.getFragmentManager(), "pin-entry");
	}
}
