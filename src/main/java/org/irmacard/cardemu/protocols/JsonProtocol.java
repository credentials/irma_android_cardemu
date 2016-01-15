package org.irmacard.cardemu.protocols;

import android.os.AsyncTask;
import android.util.Log;
import org.acra.ACRA;
import org.irmacard.cardemu.*;
import org.irmacard.cardemu.httpclient.HttpClient;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpClientResult;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.api.common.DisclosureProofRequest;
import org.irmacard.api.common.DisclosureProofResult;
import org.irmacard.api.common.util.GsonUtil;

public class JsonProtocol extends Protocol {
	private static String TAG = "CardEmuJson";

	private String server;

	public void connect(String url) {
		if (!url.endsWith("/"))
			url = url + "/";
		server = url;

		startDisclosure();
	}

	private void startDisclosure() {
		Log.i(TAG, "Retrieving disclosure request: " + server);

		activity.setState(MainActivity.STATE_CONNECTING_TO_SERVER);
		final String server = this.server;
		final HttpClient client = new HttpClient(GsonUtil.getGson());

		new AsyncTask<Void,Void,HttpClientResult<DisclosureProofRequest>>() {
			@Override
			protected HttpClientResult<DisclosureProofRequest> doInBackground(Void... params) {
				try {
					DisclosureProofRequest request = client.doGet(DisclosureProofRequest.class, server);
					return new HttpClientResult<>(request);
				} catch (HttpClientException e) {
					return new HttpClientResult<DisclosureProofRequest>(e);
				}
			}

			@Override
			protected void onPostExecute(HttpClientResult<DisclosureProofRequest> result) {
				if (result.getObject() != null) {
					activity.setState(MainActivity.STATE_READY);
					DisclosureProofRequest request = result.getObject();
					if (request.getContent().size() == 0 || request.getNonce() == null || request.getContext() == null) {
						activity.setFeedback("Got malformed disclosure request", "failure");
						cancelDisclosure();
						return;
					}
					askForVerificationPermission(request);
				} else {
					cancelDisclosure();
					String feedback;
					if (result.getException().getCause() != null)
						feedback =  result.getException().getCause().getMessage();
					else
						feedback = "Server returned status " + result.getException().status;
					activity.setFeedback(feedback, "failure");
				}
			}
		}.execute();
	}

	public void disclose(final DisclosureProofRequest request) {
		activity.setState(MainActivity.STATE_COMMUNICATING);
		Log.i(TAG, "Sending disclosure proofs to " + server);

		new AsyncTask<Void,Void,String>() {
			HttpClient client = new HttpClient(GsonUtil.getGson());
			String successMessage = "Done";
			String server = JsonProtocol.this.server;
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
				} catch (HttpClientException e) {
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
				done();
			}
		}.execute();
	}

	/**
	 * Cancels the current disclosure session by DELETE-ing the specified url and setting the state to idle.
	 */
	@Override
	public void cancelDisclosure() {
		super.cancelDisclosure();
		Log.i(TAG, "Canceling disclosure to " + server);

		new AsyncTask<String,Void,Void>() {
			@Override protected Void doInBackground(String... params) {
				try {
					new HttpClient(GsonUtil.getGson()).doDelete(params[0]);
				} catch (HttpClientException e) {
					e.printStackTrace();
				}
				return null;
			}
		}.execute(server);

		server = null;
	}
}
