package org.irmacard.cardemu.irmaclient;

import android.content.res.Resources;
import android.os.AsyncTask;
import android.util.Log;

import com.google.gson.reflect.TypeToken;

import org.acra.ACRA;
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
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;
import org.irmacard.cardemu.httpclient.JsonHttpClient;
import org.irmacard.cardemu.store.StoreManager;
import org.irmacard.cloud.common.AuthorizationResult;
import org.irmacard.cloud.common.CloudResult;
import org.irmacard.cloud.common.IRMAHeaders;
import org.irmacard.cloud.common.PinTokenMessage;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.messages.IssueCommitmentMessage;
import org.irmacard.credentials.idemix.messages.IssueSignatureMessage;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.credentials.idemix.proofs.ProofListBuilder;
import org.irmacard.credentials.idemix.proofs.ProofP;
import org.irmacard.credentials.idemix.proofs.ProofPCommitmentMap;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.info.KeyException;
import org.irmacard.credentials.info.PublicKeyIdentifier;

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class JsonIrmaClient extends IrmaClient {
	private static final String TAG = "CardEmuJson";
	private static final int maxJwtAge = 10 * 60 * 1000; // 10 minutes in milliseconds

	private String server;
	private String protocolVersion;
	private Resources resources;

	// Some specific distributed issuance/verification state
	private ProofListBuilder builder;
	private BigInteger challenge;

	// Constants to indicate whether we are issuing or verifying
	private static int mode;
	private static final int MODE_ISSUE = 0;
	private static final int MODE_VERIFY = 1;

	public JsonIrmaClient(String server, IrmaClientHandler handler, String protocolVersion) {
		super(handler);
		this.protocolVersion = protocolVersion;
		this.resources = handler.getActivity().getResources();

		if (server.endsWith("/"))
			this.server = server;
		else
			this.server = server + "/";

		if (Pattern.matches(".*/verification/[^/]+/?$", this.server)) {
			action = Action.DISCLOSING;
			startDisclosure();
		}

		else if (Pattern.matches(".*/issue/[^/]+/?$", this.server)) {
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
			feedback = resources.getString(R.string.server_returned, errorMessage.getError().getDescription());
			techinfo = errorMessage.getStacktrace();
			Log.w(TAG, String.format("API error: %s, %s", errorMessage.getError().name(), errorMessage.getDescription()));
			Log.w(TAG, errorMessage.getStacktrace());
		} else if (e.getCause() != null) {
			feedback = resources.getString(R.string.could_not_connect);
			techinfo = e.getCause().getLocalizedMessage();
			Log.w(TAG, "Exception details ", e);
		} else {
			feedback = resources.getString(R.string.could_not_connect);
			techinfo = resources.getString(R.string.server_returned_status, e.status);
			Log.w(TAG, "Exception details ", e);
		}

		// Since we got a HttpClientException, the server is either not reachable, or it returned
		// some error message. In the first case DELETEing the session will probably also fail;
		// in the second case, the server already knows to delete the session itself
		failSession(feedback, false, errorMessage, techinfo);
	}

	/**
	 * Report the specified exception as a failure to the handler.
	 * @param e The feedback
	 * @param deleteSession Whether or not we should DELETE the session
	 */
	private void fail(Exception e, boolean deleteSession) {
		failSession(e.getMessage(), deleteSession, null, null);
	}

	private void fail(String feedback, boolean deleteSession) {
		failSession(feedback, deleteSession, null, null);
	}

	private void fail(int feedbackId, boolean deleteSession) {
		failSession(resources.getString(feedbackId), deleteSession, null, null);
	}

	/**
	 * Retrieve an {@link IssuingRequest} from the server
	 */
	private void startIssuance() {
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
	public void finishIssuance(final IssuingRequest request, final DisclosureChoice disclosureChoice) {
		handler.onStatusUpdate(Action.ISSUING, Status.COMMUNICATING);
		Log.i(TAG, "Posting issuing commitments to " + server);

		final JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());

		// Increase timeout for this step as issuance server might take longer for many creds
		client.setTimeout(15000);

		if (CredentialManager.isDistributed()) {
			try {
				this.builder = CredentialManager.generateProofListBuilderForIssuance(request, disclosureChoice);
			} catch (InfoException|CredentialsException|KeyException e) {
				e.printStackTrace();
				return;
			}
			mode = MODE_ISSUE;
			obtainValidAuthorizationToken(-1);

		} else {
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
			sendIssueCommitmentMessage(msg);
		}
	}

	private void sendIssueCommitmentMessage(IssueCommitmentMessage msg) {
		final JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());

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

	private void obtainCloudProofP(ProofPCommitmentMap plistcom, final int mode) {
		ProofListBuilder.Commitment com = builder.calculateCommitments();
		com.mergeProofPCommitments(plistcom);
		challenge = com.calculateChallenge(builder.getContext(), builder.getNonce());

		if(mode == MODE_ISSUE) {
			CredentialManager.addPublicSKs(plistcom);
		}

		final JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());
		client.setExtraHeader(IRMAHeaders.USERNAME, CredentialManager.getCloudUsername());
		client.setExtraHeader(IRMAHeaders.AUTHORIZATION, CredentialManager.getCloudToken());

		Log.i(TAG, "Posting challenge to the cloud server now!");
		client.post(ProofP.class, CredentialManager.getCloudServer() + "/prove/getResponse", challenge, new JsonResultHandler<ProofP>() {
			@Override public void onSuccess(ProofP result) {
				Log.i(TAG, "Unbelievable, also received a ProofP from the server");
				JsonIrmaClient.this.finishDistributedProtocol(result, mode);
			}
		});
	}

	private void finishDistributedProtocol(ProofP proofp, int mode) {
		ProofList list = builder.createProofList(challenge, proofp);

		if(mode == MODE_VERIFY) {
			sendDisclosureProofs(list);
		} else {
			IssueCommitmentMessage msg = new IssueCommitmentMessage(list, CredentialManager.getNonce2());
			sendIssueCommitmentMessage(msg);
		}
	}

	private void obtainValidAuthorizationToken(final int tries) {
		JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());

		client.setExtraHeader(IRMAHeaders.USERNAME, CredentialManager.getCloudUsername());
		client.setExtraHeader(IRMAHeaders.AUTHORIZATION, CredentialManager.getCloudToken());

		String url = CredentialManager.getCloudServer() + "/users/isAuthorized";
		client.post(AuthorizationResult.class, url, "", new HttpResultHandler<AuthorizationResult>() {
			@Override
			public void onSuccess(AuthorizationResult result) {
				Log.i(TAG, "Successfully retrieved authorization result, " + result);
				if(result.getStatus().equals(AuthorizationResult.STATUS_AUTHORIZED)) {
					Log.i(TAG, "Token is still valid! Continuing!");
					continueProtocolWithAuthorization();
				} else if(result.getStatus().equals(AuthorizationResult.STATUS_EXPIRED)) {
					Log.i(TAG, "Token has expired, should get proper auth!");
					handler.verifyPin(tries);
				} else {
					Log.i(TAG, "Authorization is blocked!!!");
					fail("Cloud authorization blocked", true);
				}
			}

			@Override
			public void onError(HttpClientException exception) {
				// TODO: handle properly
				fail("Failed to test authorization status", true);
				if(exception != null)
					exception.printStackTrace();
				Log.e(TAG, "Pin verification returned error");
			}
		});
	}

	@Override
	public void onPinEntered(String pin) {
		verifyPinAtCloud(pin);
	}

	@Override
	public void onPinCancelled() {
		fail("Pin entry cancelled", true);
	}

	private void verifyPinAtCloud(String pin) {
		JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());
		client.setExtraHeader(IRMAHeaders.USERNAME, CredentialManager.getCloudUsername());
		client.setExtraHeader(IRMAHeaders.AUTHORIZATION, CredentialManager.getCloudToken());

		PinTokenMessage msg = new PinTokenMessage(CredentialManager.getCloudUsername(), pin);

		String url = CredentialManager.getCloudServer() + "/users/verify/pin";
		client.post(CloudResult.class, url, msg, new HttpResultHandler<CloudResult>() {
			@Override
			public void onSuccess(CloudResult result) {
				Log.i(TAG, "PIN Verification call was successful, " + result);
				if(result.getStatus().equals(CloudResult.STATUS_SUCCESS)) {
					Log.i(TAG, "Verification with PIN verified, yay!");
					CredentialManager.setCloudToken(result.getMessage());
					continueProtocolWithAuthorization();
				} else {
					Log.i(TAG, "Something went wrong verifying the pin");
					String msg = result.getMessage();
					if(msg != null) {
						obtainValidAuthorizationToken(new Integer(msg));
					}
				}
			}

			@Override
			public void onError(HttpClientException exception) {
				// TODO: handle properly
				// setFeedback("Failed to verify PIN with Cloud Server", "error");
				if(exception != null)
					exception.printStackTrace();
				Log.e(TAG, "Pin verification failed");
			}
		});
	}

	private void continueProtocolWithAuthorization() {
		builder.generateRandomizers();
		List<PublicKeyIdentifier> pkids = builder.getPublicKeyIdentifiers();
		Log.i(TAG, "Trying to obtain commitments from cloud server now!");
		obtainCloudProofPCommitments(pkids, mode);
	}

	private void obtainCloudProofPCommitments(final List<PublicKeyIdentifier> pkids, final int mode) {
		final JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());
		client.setExtraHeader(IRMAHeaders.USERNAME, CredentialManager.getCloudUsername());
		client.setExtraHeader(IRMAHeaders.AUTHORIZATION, CredentialManager.getCloudToken());

		Log.i(TAG, "Posting to the cloud server now!");
		client.post(ProofPCommitmentMap.class, CredentialManager.getCloudServer() + "/prove/getCommitments", pkids, new JsonResultHandler<ProofPCommitmentMap>() {
			@Override public void onSuccess(ProofPCommitmentMap result) {
				Log.i(TAG, "Unbelievable, it actually worked:");
				for(PublicKeyIdentifier pkid : pkids) {
					Log.i(TAG, "Key " + pkid + ", response " + result.get(pkid));
				}
				Log.i(TAG, "Calling next function in this protocol class:" + JsonIrmaClient.this);

				obtainCloudProofP(result, mode);
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
			fail(R.string.malformed_disclosure_request, true);
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
					fail(R.string.unknown_scheme_manager, true);
				if (e instanceof IOException)
					fail(R.string.downloading_info_failed, true);
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

		if(CredentialManager.isDistributed()) {
			try {
				this.builder = CredentialManager.generateProofListBuilderForVerification(disclosureChoice);
			} catch (CredentialsException e) {
				// TODO!!
				e.printStackTrace();
				return;
			}
			mode = MODE_VERIFY;
			obtainValidAuthorizationToken(-1);
		} else {
			ProofList proofs;
			try {
				proofs = CredentialManager.getProofs(disclosureChoice);
			} catch (CredentialsException e) {
				e.printStackTrace();
				// cancelSession(); TODO why was this removed?
				fail(e, true);
				return;
			}
			sendDisclosureProofs(proofs);
		}
	}

	private void sendDisclosureProofs(ProofList proofs) {
		JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());
		client.post(DisclosureProofResult.Status.class, server + "proofs", proofs,
			new JsonResultHandler<DisclosureProofResult.Status>() {
			@Override public void onSuccess(DisclosureProofResult.Status result) {
				if (result == DisclosureProofResult.Status.VALID) {
					handler.onSuccess(Action.DISCLOSING);
				} else { // We successfully computed a proof but server rejects it? That's fishy, report it
					String feedback = resources.getString(R.string.server_rejected_proof, result.name().toLowerCase());
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
