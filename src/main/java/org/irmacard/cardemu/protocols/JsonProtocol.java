package org.irmacard.cardemu.protocols;

import android.os.AsyncTask;
import android.util.Log;
import com.google.gson.reflect.TypeToken;
import org.acra.ACRA;
import org.irmacard.cardemu.*;
import org.irmacard.cardemu.httpclient.HttpClient;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpClientResult;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.messages.IssueCommitmentMessage;
import org.irmacard.credentials.idemix.messages.IssueSignatureMessage;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.api.common.IssuingRequest;
import org.irmacard.api.common.DisclosureProofRequest;
import org.irmacard.api.common.DisclosureProofResult;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.credentials.info.InfoException;

import java.lang.reflect.Type;
import java.util.ArrayList;

public class JsonProtocol extends Protocol {
	private static String TAG = "CardEmuJson";

	private String server;

	public void connect(String url) {
		if (!url.endsWith("/"))
			url = url + "/";
		server = url;

		if (server.contains("/verification/"))
			startDisclosure();
		else if (server.contains("/issue/"))
			startIssuance();
	}

	private void fail(HttpClientException e) {
		String feedback;
		if (e.getCause() != null)
			feedback =  e.getCause().getMessage();
		else
			feedback = "Server returned status " + e.status;

		Log.w(TAG, feedback);

		activity.setFeedback(feedback, "failure");
		activity.setState(MainActivity.STATE_IDLE);
	}

	public void startIssuance() {
		Log.i(TAG, "Retrieving issuing request: " + server);

		activity.setState(MainActivity.STATE_CONNECTING_TO_SERVER);
		final String server = this.server;
		final HttpClient client = new HttpClient(GsonUtil.getGson());

		new AsyncTask<Void,Void,HttpClientResult<IssuingRequest>>() {
			@Override
			protected HttpClientResult<IssuingRequest> doInBackground(Void... params) {
				try {
					IssuingRequest request = client.doGet(IssuingRequest.class, server);
					return new HttpClientResult<>(request);
				} catch (HttpClientException e) {
					return new HttpClientResult<>(e);
				}
			}

			@Override
			protected void onPostExecute(HttpClientResult<IssuingRequest> result) {
				if (result.getObject() != null) {
					Log.i(TAG, result.getObject().toString());
					postCommitments(result.getObject(), client);
				} else {
					fail(result.getException());
				}
			}
		}.execute();
	}

	private void postCommitments(IssuingRequest request, final HttpClient client) {
		Log.i(TAG, "Posting issuing commitments");

		IssueCommitmentMessage msg;
		try {
			msg = CredentialManager.getIssueCommitments(request);
		} catch (InfoException e) {
			e.printStackTrace();
			activity.setFeedback("Issuing failed: wrong credential type", "failure");
			activity.setState(MainActivity.STATE_IDLE);
			return;
		}

		new AsyncTask<IssueCommitmentMessage, Void, HttpClientResult<ArrayList<IssueSignatureMessage>>>() {
			@Override
			protected HttpClientResult<ArrayList<IssueSignatureMessage>> doInBackground(IssueCommitmentMessage... params) {
				IssueCommitmentMessage msg = params[0];
				Type t = new TypeToken<ArrayList<IssueSignatureMessage>>(){}.getType();
				try {
					ArrayList<IssueSignatureMessage> sigs = client.doPost(t, server + "commitments", msg);
					return new HttpClientResult<>(sigs);
				} catch (HttpClientException e) {
					return new HttpClientResult<>(e);
				}
			}

			@Override
			protected void onPostExecute(HttpClientResult<ArrayList<IssueSignatureMessage>> sigs) {
				if (sigs.getObject() != null) {
					try {
						CredentialManager.constructCredentials(sigs.getObject());
						activity.setFeedback("Issuing was successfull", "success");
						activity.setState(MainActivity.STATE_IDLE);
						done();
					} catch (InfoException|CredentialsException e) {
						fail(new HttpClientException(e));
					}
				} else {
					fail(sigs.getException());
				}
			}
		}.execute(msg);
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
					return new HttpClientResult<>(e);
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
					fail(result.getException());
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
