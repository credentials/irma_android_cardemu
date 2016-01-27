package org.irmacard.cardemu.protocols;

import android.app.Activity;
import android.os.AsyncTask;
import android.util.Log;
import com.google.gson.reflect.TypeToken;
import org.acra.ACRA;
import org.irmacard.api.common.exceptions.ApiErrorMessage;
import org.irmacard.cardemu.*;
import org.irmacard.cardemu.httpclient.HttpClient;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;
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
import java.util.regex.Pattern;

public class JsonProtocol extends Protocol {
	private static String TAG = "CardEmuJson";

	private String server;

	public JsonProtocol(String server, ProtocolHandler handler) {
		super(handler);

		if (server.endsWith("/"))
			this.server = server;
		else
			this.server = server + "/";

		connect();
	}

	private void connect() {
		if (Pattern.matches(".*/verification/[^/]+/$", server)) {
			action = ProtocolHandler.Action.DISCLOSING;
			startDisclosure();
		}
		else if (Pattern.matches(".*/issue/[^/]+/$", server)) {
			action = ProtocolHandler.Action.ISSUING;
			startIssuance();
		}
	}

	/**
	 * Report the specified error message (if present) or the exception (if not) to the handler
	 */
	private void fail(HttpClientException e, ApiErrorMessage errorMessage) {
		String feedback;

		if (errorMessage != null && errorMessage.getError() != null) {
			feedback = "Server returned: " + errorMessage.getError().getDescription();
			Log.w(TAG, "API error: " + errorMessage.getError().name() + ", " + errorMessage.getDescription());
			Log.w(TAG, errorMessage.getStacktrace());
		} else if (e.getCause() != null)
			feedback = "Could not connect: " + e.getCause().getMessage();
		else
			feedback = "Could not connect: Server returned status " + e.status;

		// Since we got a HttpClientException, the server is either not reachable, or it returned
		// some error message. In the first case DELETEing the session will probably also fail;
		// in the second case, the server already knows to delete the session itself
		fail(feedback, false);
	}

	/**
	 * Report the specified exception as a failure to the handler.
	 * @param e The feedback
	 * @param deleteSession Whether or not we should DELETE the session
	 */
	private void fail(Exception e, boolean deleteSession) {
		fail(e.getMessage(), deleteSession);
	}

	/**
	 * Report the specified feedback as a failure to the handler.
	 * @param feedback The feedback
	 * @param deleteSession Whether or not we should DELETE the session
	 */
	private void fail(String feedback, boolean deleteSession) {
		Log.w(TAG, feedback);

		handler.onFailure(action, feedback);
		if (deleteSession)
			deleteSession();
	}

	@Override
	public void onIssueCancel() {
		deleteSession();
		super.onIssueCancel();
	}

	@Override
	public void onDiscloseCancel() {
		deleteSession();
		super.onDiscloseCancel();
	}

	/**
	 * Retrieve an {@link IssuingRequest} from the server
	 */
	public void startIssuance() {
		Log.i(TAG, "Retrieving issuing request: " + server);

		handler.onStatusUpdate(ProtocolHandler.Action.ISSUING, ProtocolHandler.Status.COMMUNICATING);
		final String server = this.server;
		final HttpClient client = new HttpClient(GsonUtil.getGson());
		
		client.get(IssuingRequest.class, server, new JsonResultHandler<IssuingRequest>() {
			@Override public void onSuccess(IssuingRequest result) {
				Log.i(TAG, result.toString());
				handler.onStatusUpdate(ProtocolHandler.Action.ISSUING, ProtocolHandler.Status.CONNECTED);
				handler.askForIssuancePermission(result);
			}
		});
	}

	/**
	 * Given an {@link IssuingRequest}, compute the first issuing message and post it to the server
	 * (using the specified {@link HttpClient}). If the server returns corresponding CL signatures,
	 * construct and save the new Idemix credentials.
	 */
	@Override
	protected void finishIssuance(final IssuingRequest request) {
		handler.onStatusUpdate(ProtocolHandler.Action.ISSUING, ProtocolHandler.Status.COMMUNICATING);
		Log.i(TAG, "Posting issuing commitments");

		final HttpClient client = new HttpClient(GsonUtil.getGson());
		IssueCommitmentMessage msg;
		try {
			msg = CredentialManager.getIssueCommitments(request);
		} catch (InfoException e) {
			e.printStackTrace();
			fail("wrong credential type", true);
			return;
		} catch (CredentialsException e) {
			fail("missing required attributes", true);
			return;
		}

		Type t = new TypeToken<ArrayList<IssueSignatureMessage>>(){}.getType();
		client.post(t, server + "commitments", msg, new JsonResultHandler<ArrayList<IssueSignatureMessage>>() {
			@Override public void onSuccess(ArrayList<IssueSignatureMessage> result) {
				try {
					CredentialManager.constructCredentials(result);
					handler.onSuccess(ProtocolHandler.Action.ISSUING);
				} catch (InfoException|CredentialsException e) {
					fail(e, false); // No need to inform the server if this failed
				}
			}
		});
	}

	/**
	 * Retrieve a {@link DisclosureProofRequest} from the server, see if we can satisfy it, and if so,
	 * ask the user which attributes she wants to disclose.
	 */
	private void startDisclosure() {
		handler.onStatusUpdate(ProtocolHandler.Action.DISCLOSING, ProtocolHandler.Status.COMMUNICATING);
		Log.i(TAG, "Retrieving disclosure request: " + server);

		HttpClient client = new HttpClient(GsonUtil.getGson());

		client.get(DisclosureProofRequest.class, server, new JsonResultHandler<DisclosureProofRequest>() {
			@Override public void onSuccess(DisclosureProofRequest result) {
				if (result.getContent().size() == 0 || result.getNonce() == null || result.getContext() == null) {
					fail("Got malformed disclosure request", true);
					return;
				}
				handler.onStatusUpdate(ProtocolHandler.Action.DISCLOSING, ProtocolHandler.Status.CONNECTED);
				handler.askForVerificationPermission(result);
			}
		});
	}

	/**
	 * Given a {@link DisclosureProofRequest} with selected attributes, perform the disclosure.
	 */
	@Override
	public void disclose(final DisclosureProofRequest request) {
		handler.onStatusUpdate(ProtocolHandler.Action.DISCLOSING, ProtocolHandler.Status.COMMUNICATING);
		Log.i(TAG, "Sending disclosure proofs to " + server);

		ProofList proofs;
		try {
			proofs = CredentialManager.getProofs(request);
		} catch (CredentialsException e) {
			e.printStackTrace();
			cancelSession();
			fail(e, true);
			return;
		}

		HttpClient client = new HttpClient(GsonUtil.getGson());
		client.post(DisclosureProofResult.Status.class, server + "proofs", proofs,
			new JsonResultHandler<DisclosureProofResult.Status>() {
			@Override public void onSuccess(DisclosureProofResult.Status result) {
				if (result == DisclosureProofResult.Status.VALID) {
					handler.onSuccess(ProtocolHandler.Action.DISCLOSING);
				} else { // We successfully computed a proof but server rejects it? That's fishy, report it
					String feedback = "Server rejected proof: " + result.name().toLowerCase();
					ACRA.getErrorReporter().handleException(new Exception(feedback));
					fail(feedback, false);
				}
			}
		});
	}

	/**
	 * Deletes the current disclosure session by DELETE-ing the specified url and setting the state to idle.
	 */
	public void deleteSession() {
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

	/**
	 * Error messageHandler that deserializes a server API error, if present
	 */
	private abstract class JsonResultHandler<T> implements HttpResultHandler<T> {
		@Override
		public void onError(HttpClientException exception) {
			try {
				ApiErrorMessage msg = GsonUtil.getGson().fromJson(exception.getMessage(), ApiErrorMessage.class);
				fail(exception, msg);
			} catch (Exception e) {
				fail(exception, null);
			}
		}
	}

}
