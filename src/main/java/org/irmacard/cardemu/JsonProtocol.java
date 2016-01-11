package org.irmacard.cardemu;

import android.os.AsyncTask;
import android.util.Log;
import org.acra.ACRA;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.verification.common.DisclosureProofRequest;
import org.irmacard.verification.common.DisclosureProofResult;
import org.irmacard.verification.common.util.GsonUtil;

public class JsonProtocol {
	private static String TAG = "CardEmuJson";

	private MainActivity activity;

	private String disclosureServer;

	public JsonProtocol(MainActivity activity) {
		this.activity = activity;
	}

	public void connect(String url) {
		if (!url.endsWith("/"))
			url = url + "/";
		disclosureServer = url;
		Log.i(TAG, "Start channel listening: " + url);

		activity.setState(MainActivity.STATE_CONNECTING_TO_SERVER);
		final String server = url;
		final HttpClient client = new HttpClient(GsonUtil.getGson());

		new AsyncTask<Void,Void,DisclosureStartResult>() {
			@Override
			protected DisclosureStartResult doInBackground(Void... params) {
				try {
					DisclosureProofRequest request = client.doGet(DisclosureProofRequest.class, server);
					return new DisclosureStartResult(request);
				} catch (HttpClient.HttpClientException e) {
					return new DisclosureStartResult(e);
				}
			}

			@Override
			protected void onPostExecute(DisclosureStartResult result) {
				if (result.request != null) {
					activity.setState(MainActivity.STATE_READY);
					activity.askForVerificationPermission(result.request);
				} else {
					cancelDisclosure();
					String feedback;
					if (result.exception.getCause() != null)
						feedback =  result.exception.getCause().getMessage();
					else
						feedback = "Server returned status " + result.exception.status;
					activity.setFeedback(feedback, "failure");
				}
			}
		}.execute();
	}

	public void disclose(final DisclosureProofRequest request) {
		activity.setState(MainActivity.STATE_COMMUNICATING);

		new AsyncTask<Void,Void,String>() {
			HttpClient client = new HttpClient(GsonUtil.getGson());
			String successMessage = "Done";
			String server = disclosureServer;
			boolean shouldCancel = false;

			@Override
			protected String doInBackground(Void[] params) {
				DisclosureProofResult.Status status;

				try {
					ProofList proofs;
					try {
						proofs = CredentialManager.getProofs(request);
					} catch (CredentialsException e) {
						e.printStackTrace();
						shouldCancel = true;
						return e.getMessage();
					}

					status = client.doPost(DisclosureProofResult.Status.class, server + "proofs", proofs);
				} catch (HttpClient.HttpClientException e) {
					e.printStackTrace();
					if (e.getCause() != null)
						return e.getCause().getMessage();
					else
						return "Server returned status " + e.status;
				}

				if (status == DisclosureProofResult.Status.VALID)
					return successMessage;
				else { // We successfully computed a proof but server rejects it? That's fishy, report it
					String feedback = "Server rejected proof: " + status.name().toLowerCase();
					ACRA.getErrorReporter().handleException(new Exception(feedback));
					return feedback;
				}
			}

			@Override
			protected void onPostExecute(String result) {
				activity.setState(MainActivity.STATE_IDLE);
				String status = result.equals(successMessage) ? "success" : "failure";

				if (shouldCancel)
					cancelDisclosure();

				// Translate some possible problems to more human-readable versions
				if (result.startsWith("failed to connect"))
					result = "Could not connect";
				if (result.startsWith("Supplied sessionToken not found or expired"))
					result = "Server refused connection";

				activity.setFeedback(result, status);
			}
		}.execute();
	}

	/**
	 * Cancels the current disclosure session by DELETE-ing the specified url and setting the state to idle.
	 */
	public void cancelDisclosure() {
		new AsyncTask<String,Void,Void>() {
			@Override protected Void doInBackground(String... params) {
				try {
					new HttpClient(GsonUtil.getGson()).doDelete(params[0]);
				} catch (HttpClient.HttpClientException e) {
					e.printStackTrace();
				}
				return null;
			}
		}.execute(disclosureServer);

		disclosureServer = null;
	}
}
