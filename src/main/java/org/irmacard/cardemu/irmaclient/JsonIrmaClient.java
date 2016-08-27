package org.irmacard.cardemu.irmaclient;

import android.os.AsyncTask;
import android.util.Log;

import com.google.gson.reflect.TypeToken;

import org.acra.ACRA;
import org.irmacard.cardemu.store.StoreManager;
import org.irmacard.api.common.ClientRequest;
import org.irmacard.api.common.DisclosureProofRequest;
import org.irmacard.api.common.DisclosureProofResult;
import org.irmacard.api.common.IdentityProviderRequest;
import org.irmacard.api.common.IssuingRequest;
import org.irmacard.api.common.JwtParser;
import org.irmacard.api.common.JwtSessionRequest;
import org.irmacard.api.common.ServiceProviderRequest;
import org.irmacard.api.common.SessionRequest;
import org.irmacard.api.common.exceptions.ApiErrorMessage;
import org.irmacard.api.common.exceptions.ApiException;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.CredentialManager;
import org.irmacard.cardemu.DisclosureChoice;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;
import org.irmacard.cardemu.httpclient.JsonHttpClient;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.messages.IssueCommitmentMessage;
import org.irmacard.credentials.idemix.messages.IssueSignatureMessage;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.info.KeyException;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.regex.Pattern;

public class JsonIrmaClient extends IrmaClient {
	private static final String TAG = "CardEmuJson";
	private static final int maxJwtAge = 10 * 60 * 1000; // 10 minutes in milliseconds

	private String server;
	private String protocolVersion;

	public JsonIrmaClient(String server, IrmaClientHandler handler, String protocolVersion) {
		super(handler);
		this.protocolVersion = protocolVersion;

		if (server.endsWith("/"))
			this.server = server;
		else
			this.server = server + "/";

		connect();
	}

	private void connect() {
		if (Pattern.matches(".*/verification/[^/]+/$", server)) {
			action = Action.DISCLOSING;
			startDisclosure();
		}
		else if (Pattern.matches(".*/issue/[^/]+/$", server)) {
			action = Action.ISSUING;
			startIssuance();
		}
	}

	/**
	 * Report the specified error message (if present) or the exception (if not) to the handler
	 */
	private void fail(HttpClientException e, ApiErrorMessage errorMessage) {
		String feedback, techinfo;

		if (errorMessage != null && errorMessage.getError() != null) {
			feedback = String.format("server returned: %s", errorMessage.getError().getDescription());
			techinfo = errorMessage.getStacktrace();
			Log.w(TAG, String.format("API error: %s, %s", errorMessage.getError().name(), errorMessage.getDescription()));
			Log.w(TAG, errorMessage.getStacktrace());
		} else if (e.getCause() != null) {
			feedback = "could not connect to server";
			techinfo = e.getCause().getMessage();
			Log.w(TAG, "Exception details ", e);
		} else {
			feedback = "could not connect to server";
			techinfo = String.format("server returned status %d", e.status);
			Log.w(TAG, "Exception details ", e);
		}

		// Since we got a HttpClientException, the server is either not reachable, or it returned
		// some error message. In the first case DELETEing the session will probably also fail;
		// in the second case, the server already knows to delete the session itself
		fail(feedback, false, errorMessage, techinfo);
	}

	/**
	 * Report the specified exception as a failure to the handler.
	 * @param e The feedback
	 * @param deleteSession Whether or not we should DELETE the session
	 */
	private void fail(Exception e, boolean deleteSession) {
		fail(e.getMessage(), deleteSession, null);
	}

	private void fail(String feedback, boolean deleteSession) {
		fail(feedback, deleteSession, null);
	}

	/**
	 * Report the specified feedback as a failure to the handler.
	 * @param feedback The feedback
	 * @param deleteSession Whether or not we should DELETE the session
	 */
	private void fail(String feedback, boolean deleteSession, ApiErrorMessage error) {
		fail(feedback, deleteSession, error, null);
	}

	private void fail(String feedback, boolean deleteSession, ApiErrorMessage error, String info) {
		Log.w(TAG, feedback);

		handler.onFailure(action, feedback, error, info);
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

		handler.onStatusUpdate(Action.ISSUING, Status.COMMUNICATING);
		final String server = this.server;
		final JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());

		switch (protocolVersion) {
			case "2.0":
				client.get(IssuingRequest.class, server, new JsonResultHandler<IssuingRequest>() {
					@Override public void onSuccess(final IssuingRequest request) {
						continueSession(request, Action.ISSUING);
					}
				});
				break;
			case "2.1":
				client.get(JwtSessionRequest.class, server + "jwt", new JsonResultHandler<JwtSessionRequest>() {
					@Override public void onSuccess(final JwtSessionRequest jwt) {
						continueSession(jwt, IdentityProviderRequest.class, Action.ISSUING);
					}
				});
				break;
			default:
				throw new RuntimeException("Unsupported protocol version: " + protocolVersion);
		}

	}

	/**
	 * Given an {@link IssuingRequest}, compute the first issuing message and post it to the server
	 * (using the specified {@link JsonHttpClient}). If the server returns corresponding CL signatures,
	 * construct and save the new Idemix credentials.
	 */
	@Override
	protected void finishIssuance(final IssuingRequest request, final DisclosureChoice disclosureChoice) {
		handler.onStatusUpdate(Action.ISSUING, Status.COMMUNICATING);
		Log.i(TAG, "Posting issuing commitments");

		final JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());

		// Increase timeout for this step as issuance server might take longer for many creds
		client.setTimeout(15000);

		IssueCommitmentMessage msg;
		try {
			msg = CredentialManager.getIssueCommitments(request, disclosureChoice);
		} catch (InfoException e) {
			e.printStackTrace();
			fail("wrong credential type", true);
			return;
		} catch (CredentialsException e) {
			fail("missing required attributes", true);
			return;
		} catch (KeyException e) {
			fail("missing public key", true);
			return;
		}

		Type t = new TypeToken<ArrayList<IssueSignatureMessage>>(){}.getType();
		client.post(t, server + "commitments", msg, new JsonResultHandler<ArrayList<IssueSignatureMessage>>() {
			@Override public void onSuccess(ArrayList<IssueSignatureMessage> result) {
				try {
					CredentialManager.constructCredentials(result);
					handler.onSuccess(Action.ISSUING);
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
		handler.onStatusUpdate(Action.DISCLOSING, Status.COMMUNICATING);
		Log.i(TAG, "Retrieving disclosure request: " + server);

		JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());

		// Get the disclosure request
		switch (protocolVersion) {
			case "2.0":
				client.get(DisclosureProofRequest.class, server, new JsonResultHandler<DisclosureProofRequest>() {
					@Override public void onSuccess(final DisclosureProofRequest request) {
						continueSession(request, Action.DISCLOSING);
					}
				});
				break;
			case "2.1":
				client.get(JwtSessionRequest.class, server + "jwt", new JsonResultHandler<JwtSessionRequest>() {
					@Override public void onSuccess(final JwtSessionRequest jwt) {
						continueSession(jwt, ServiceProviderRequest.class, action);
					}
				});
				break;
			default:
				throw new RuntimeException("Unsupported protocol version: " + protocolVersion);
		}
	}

	// These templates are getting a little crazy, could also create separate functions for
	// issuing and disclosing
	private <T extends ClientRequest<S>, S extends SessionRequest> void continueSession(
		JwtSessionRequest sessionRequest, Class<T> clazz, final Action action) {
		try {
			JwtParser<T> jwtParser = new JwtParser<>(clazz, true, maxJwtAge);
			jwtParser.parseJwt(sessionRequest.getJwt());
			S request = jwtParser.getPayload().getRequest();

			request.setNonce(sessionRequest.getNonce());
			request.setContext(sessionRequest.getContext());
			continueSession(request, action, jwtParser.getJwtIssuer());
		} catch (ApiException e) {
			fail(e, true);
		}
	}

	private <T extends SessionRequest> void continueSession(T request, Action action) {
		continueSession(request, action, null);
	}

	private <T extends SessionRequest> void continueSession(final T request, final Action action,
	                                                        final String requesterName) {
		if (request.isEmpty() || request.getNonce() == null || request.getContext() == null) {
			fail("Got malformed disclosure request", true);
			return;
		}

		// If necessary, update the stores; afterwards, ask the user for permission to continue
		StoreManager.download(request, new StoreManager.DownloadHandler() {
			@Override public void onSuccess() {
				handler.onStatusUpdate(action, Status.CONNECTED);
				handler.askForPermission(request, requesterName);
			}
			@Override public void onError(Exception e) {
				if (e instanceof InfoException)
					fail("Unknown scheme manager", true);
				if (e instanceof IOException)
					fail("Could not download credential or issuer information", true);
			}
		});
	}

	/**
	 * Given a {@link DisclosureProofRequest} with selected attributes, perform the disclosure.
	 */
	@Override
	public void disclose(final DisclosureProofRequest request, DisclosureChoice disclosureChoice) {
		handler.onStatusUpdate(Action.DISCLOSING, Status.COMMUNICATING);
		Log.i(TAG, "Sending disclosure proofs to " + server);

		ProofList proofs;
		try {
			proofs = CredentialManager.getProofs(disclosureChoice);
		} catch (CredentialsException e) {
			e.printStackTrace();
			cancelSession();
			fail(e, true);
			return;
		}

		JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());
		client.post(DisclosureProofResult.Status.class, server + "proofs", proofs,
			new JsonResultHandler<DisclosureProofResult.Status>() {
			@Override public void onSuccess(DisclosureProofResult.Status result) {
				if (result == DisclosureProofResult.Status.VALID) {
					handler.onSuccess(Action.DISCLOSING);
				} else { // We successfully computed a proof but server rejects it? That's fishy, report it
					String feedback = "Server rejected proof: " + result.name().toLowerCase();
					ACRA.getErrorReporter().handleException(new Exception(feedback));
					fail(feedback, false);
				}
			}
		});
	}

	/**
	 * Deletes the current session by DELETE-ing the specified url and setting the state to idle.
	 */
	@Override
	public void deleteSession() {
		Log.i(TAG, "DELETEing " + server);

		new AsyncTask<String,Void,Void>() {
			@Override protected Void doInBackground(String... params) {
				try {
					new JsonHttpClient(GsonUtil.getGson()).doDelete(params[0]);
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
