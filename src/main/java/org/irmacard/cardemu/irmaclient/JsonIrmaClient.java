package org.irmacard.cardemu.irmaclient;

import android.content.res.Resources;
import android.os.AsyncTask;
import android.util.Log;

import com.google.gson.reflect.TypeToken;

import org.acra.ACRA;
import org.irmacard.api.common.ClientRequest;
import org.irmacard.api.common.JwtParser;
import org.irmacard.api.common.JwtSessionRequest;
import org.irmacard.api.common.SessionRequest;
import org.irmacard.api.common.disclosure.DisclosureProofRequest;
import org.irmacard.api.common.disclosure.DisclosureProofResult;
import org.irmacard.api.common.disclosure.ServiceProviderRequest;
import org.irmacard.api.common.exceptions.ApiErrorMessage;
import org.irmacard.api.common.exceptions.ApiException;
import org.irmacard.api.common.issuing.IdentityProviderRequest;
import org.irmacard.api.common.issuing.IssuingRequest;
import org.irmacard.api.common.signatures.SignatureClientRequest;
import org.irmacard.api.common.signatures.SignatureProofRequest;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.CredentialManager;
import org.irmacard.cardemu.DisclosureChoice;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;
import org.irmacard.cardemu.httpclient.JsonHttpClient;
import org.irmacard.cardemu.pindialog.EnterPINDialogFragment;
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

public class JsonIrmaClient extends IrmaClient implements EnterPINDialogFragment.PINDialogListener {
	private static final String TAG = "CardEmuJson";
	private static final int maxJwtAge = 10 * 60 * 1000; // 10 minutes in milliseconds

	private String server;
	private String protocolVersion;
	private Resources resources;

	// Some specific distributed issuance/verification state
	private ProofListBuilder builder;

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

		else if (Pattern.matches(".*/signature/[^/]+/?$", server)) {
			action = Action.SIGNING;
			startSigning();
		}
	}

	// Failure reporting methods -------------------------------------------------------------------

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

	// Issuance methods ----------------------------------------------------------------------------

	/**
	 * Retrieve an {@link IssuingRequest} from the server
	 */
	private void startIssuance() {
		startSession(IssuingRequest.class, IdentityProviderRequest.class);
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
			startCloudProtocol();

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

	// Disclosure methods --------------------------------------------------------------------------

	/**
	 * Retrieve a {@link DisclosureProofRequest} from the server, see if we can satisfy it, and if so,
	 * ask the user which attributes she wants to disclose.
	 */
	private void startDisclosure() {
		startSession(DisclosureProofRequest.class, ServiceProviderRequest.class);
	}

	/**
	 * Given a {@link DisclosureProofRequest} with selected attributes, perform the disclosure.
	 */
	@Override
	public void disclose(final DisclosureProofRequest request, DisclosureChoice disclosureChoice) {
		proofSession(disclosureChoice);
	}

	private void proofSession(DisclosureChoice disclosureChoice) {
		handler.onStatusUpdate(action, Status.COMMUNICATING);
		Log.i(TAG, "Sending disclosure/signature proofs to " + server);

		String message = disclosureChoice.getRequest() instanceof SignatureProofRequest ?
				((SignatureProofRequest) disclosureChoice.getRequest()).getMessage() : null;

		if(CredentialManager.isDistributed()) {
			try {
				this.builder = CredentialManager.generateProofListBuilderForDisclosure(
						disclosureChoice, message);
			} catch (CredentialsException|InfoException e) {
				// TODO!!
				e.printStackTrace();
				return;
			}
			startCloudProtocol();
		} else {
			ProofList proofs;
			try {
				if (action == Action.SIGNING)
					proofs = CredentialManager.getSignatureProofs(disclosureChoice, message);
				else
					proofs = CredentialManager.getProofs(disclosureChoice);
			} catch (CredentialsException|InfoException e) {
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
							handler.onSuccess(action);
						} else { // We successfully computed a proof but server rejects it? That's fishy, report it
							String feedback = resources.getString(R.string.server_rejected_proof, result.name().toLowerCase());
							ACRA.getErrorReporter().handleException(new Exception(feedback));
							fail(feedback, false);
						}
					}
				});
	}

	// Signing methods -----------------------------------------------------------------------------

	/**
	 * Retrieve a {@link SignatureProofRequest} from the server, see if we can satisfy it, and if so,
	 * ask the user which attributes she wants to disclose.
	 */
	private void startSigning() {
		startSession(SignatureProofRequest.class, SignatureClientRequest.class);
	}

	/**
	 * Given a {@link SignatureProofRequest} with selected attributes, create an IRMA signature.
	 */
	@Override
	public void sign(final SignatureProofRequest request, DisclosureChoice disclosureChoice) {
		proofSession(disclosureChoice);
	}

	// Methods used in the issuing, disclosure and signing protocols -------------------------------

	private <T extends SessionRequest, S extends ClientRequest<T>> void startSession(
			Class<T> requestClass, final Class<S> clientRequestClass) {
		handler.onStatusUpdate(action, Status.COMMUNICATING);
		Log.i(TAG, "Starting " + action + ": " + server);
		JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());

		// Get the signature request

		switch (protocolVersion) {
			case "2.0":
				client.get(requestClass, server, new JsonResultHandler<T>() {
					@Override public void onSuccess(final T request) {
						continueSession(request, action);
					}
				});
				break;
			case "2.1":
				client.get(JwtSessionRequest.class, server + "jwt", new JsonResultHandler<JwtSessionRequest>() {
					@Override public void onSuccess(final JwtSessionRequest jwt) {
						continueSession(jwt, clientRequestClass, action);
					}
				});
				break;
			default:
				throw new RuntimeException("Unsupported protocol version: " + protocolVersion);
		}
	}

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

	// Cloud methods -------------------------------------------------------------------------------

	/**
	 * Starts the cloud-part of the issuing or disclosure protocol.
	 * Ask the user for her PIN if necessary (i.e., if our cloud JWT is absent or expired). We allow
	 * the user as much attempts as the cloud server allows. The entered pin is checked using
	 * {@link #verifyPinAtCloud(String)}. After this (if successful) we use the valid JWT to get the
	 *  cloud's {@link ProofP} through {@link #continueProtocolWithAuthorization()}.
	 */
	private void startCloudProtocol() {
		obtainValidAuthorizationToken(-1);
	}

	/**
	 * Same as {@link #startCloudProtocol()}, but allowing the user the specified amount
	 * of tries.
	 */
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
					// Let the handler ask the user for the pin; afterwards it will call onPinEntered() or
					/// onPinCancelled()
					handler.verifyPin(tries, JsonIrmaClient.this);
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

	/**
	 * Given a pin, ask the server if it is correct; if it is, store and use the returned cloud JWT
	 * in {@link #continueProtocolWithAuthorization()}; if it is not, ask for the pin again using
	 * {@link #obtainValidAuthorizationToken(int)} (which afterwards calls us again).
	 */
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
						int remainingTries = Integer.valueOf(msg);
						if (remainingTries > 0)
							obtainValidAuthorizationToken(remainingTries);
						else {
							Log.i(TAG, "Authorization is blocked!!!");
							fail("Cloud authorization blocked", true);
						}
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

	/**
	 * Generate our randomizers for the proofs of knowledge of our part of the secret key, and (possibly)
	 * hidden attributes; afterwards continue the protocol with
	 * {@link #obtainCloudProofPCommitments(List)}.
	 */
	private void continueProtocolWithAuthorization() {
		builder.generateRandomizers();
		List<PublicKeyIdentifier> pkids = builder.getPublicKeyIdentifiers();
		Log.i(TAG, "Trying to obtain commitments from cloud server now!");
		obtainCloudProofPCommitments(pkids);
	}

	/**
	 * Ask the cloud server for R_0^w mod n, with w being its randomness, and n the moduli
	 * of each of the specified public keys; if successful, continue by calculating the challenge and
	 * asking for the responses by {@link #obtainCloudProofP(ProofPCommitmentMap)}.
	 * @param pkids List of public keys; for the n of each of these, we ask for R_0^w mod n
	 */
	private void obtainCloudProofPCommitments(final List<PublicKeyIdentifier> pkids) {
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

				obtainCloudProofP(result);
			}
		});
	}

	/**
	 * Calculate the challenge c using our commitments and that of the cloud server, post that to the
	 * cloud server, and ask for the response c * m + w (where m is the cloud's private key and w its
	 * randomness used previously in its commitment). If successful, combine our responses with that
	 * of the cloud server, and continue the protocol normally using
	 * {@link #finishDistributedProtocol(ProofP, BigInteger)}.
	 * @param plistcom List of commitments of the cloud for each public key
	 */
	private void obtainCloudProofP(ProofPCommitmentMap plistcom) {
		ProofListBuilder.Commitment com = builder.calculateCommitments();
		com.mergeProofPCommitments(plistcom);
		final BigInteger challenge = com.calculateChallenge(
				builder.getContext(), builder.getNonce(), action == Action.SIGNING);

		if (action == Action.ISSUING) {
			CredentialManager.addPublicSKs(plistcom);
		}

		final JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());
		client.setExtraHeader(IRMAHeaders.USERNAME, CredentialManager.getCloudUsername());
		client.setExtraHeader(IRMAHeaders.AUTHORIZATION, CredentialManager.getCloudToken());

		Log.i(TAG, "Posting challenge to the cloud server now!");
		client.post(ProofP.class, CredentialManager.getCloudServer() + "/prove/getResponse", challenge, new JsonResultHandler<ProofP>() {
			@Override public void onSuccess(ProofP result) {
				Log.i(TAG, "Unbelievable, also received a ProofP from the server");
				JsonIrmaClient.this.finishDistributedProtocol(result, challenge);
			}
		});
	}

	/**
	 * Combine our proofs with that of the cloud server, and continue the protocol normally using
	 * {@link #sendDisclosureProofs(ProofList)} or
	 * {@link #sendIssueCommitmentMessage(IssueCommitmentMessage)}.
	 * @param proofp The cloud's proof of its part of the secret key
	 */
	private void finishDistributedProtocol(ProofP proofp, BigInteger challenge) {
		ProofList list = builder.createProofList(challenge, proofp);

		if(action == Action.DISCLOSING || action == Action.SIGNING) {
			sendDisclosureProofs(list);
		} else {
			IssueCommitmentMessage msg = new IssueCommitmentMessage(list, CredentialManager.getNonce2());
			sendIssueCommitmentMessage(msg);
		}
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
