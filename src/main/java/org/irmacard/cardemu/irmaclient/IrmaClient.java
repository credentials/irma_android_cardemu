package org.irmacard.cardemu.irmaclient;

import android.content.res.Resources;
import android.util.Log;
import android.util.Patterns;

import com.google.gson.reflect.TypeToken;

import org.acra.ACRA;
import org.irmacard.api.common.ClientQr;
import org.irmacard.api.common.ClientRequest;
import org.irmacard.api.common.CredentialRequest;
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
import org.irmacard.cardemu.DisclosureChoice;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;
import org.irmacard.cardemu.httpclient.JsonHttpClient;
import org.irmacard.cardemu.pindialog.EnterPINDialogFragment;
import org.irmacard.cardemu.store.CredentialManager;
import org.irmacard.cardemu.store.KeyshareServer;
import org.irmacard.cardemu.store.StoreManager;
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
import org.irmacard.keyshare.common.AuthorizationResult;
import org.irmacard.keyshare.common.IRMAHeaders;

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

public class IrmaClient implements EnterPINDialogFragment.PINDialogListener {
	/** Specifies the current state of the instance. */
	public enum Status {
		CONNECTED, COMMUNICATING, DONE
	}

	/** Specifies what action the instance is performing. */
	public enum Action {
		DISCLOSING, SIGNING, ISSUING, UNKNOWN
	}

	private static final String[] supportedVersions = {"2.0", "2.1"};
	private static final int maxJwtAge = 10 * 60 * 1000; // 10 minutes in milliseconds
	private final static String TAG = "IrmaClient";

	private String server;
	private IrmaTransport transport;
	private JsonHttpClient keyshareClient;
	private Action action = Action.UNKNOWN;
	private IrmaClientHandler handler;
	private String protocolVersion;
	private Resources resources;
	private String schemeManager;

	// Some specific distributed issuance/verification state
	private ProofListBuilder builder;


	/**
	 * Create a new session
	 * @param qrcontent Contents of the QR code, containing the server to connect to and protocol version number
	 * @param handler The handler to report feedback to
	 */
	public IrmaClient(String qrcontent, IrmaClientHandler handler) {
		// Decide on the protocol version and the URL to connect to
		ClientQr qr;
		try {
			qr = GsonUtil.getGson().fromJson(qrcontent, ClientQr.class);
		} catch (Exception e) {
			handler.onFailure(Action.UNKNOWN, "Not an IRMA session", null, "Content: " + qrcontent);
			return;
		}

		start(qr, handler);
	}

	public IrmaClient(ClientQr qr, IrmaClientHandler handler) {
		start(qr, handler);
	}

	/**
	 * Using the version numbers in the specified QR, calculate the highest version number
	 * that both us and the server supports.
	 * @throws NumberFormatException
	 * @throws java.util.NoSuchElementException
     */
	private static String calculateProtocolVersion(ClientQr qr) throws NumberFormatException {
		// Build an interpolated list of supported server versions, e.g.
		// v: "2.0", vmax: "2.2" ==> {"2.0", "2.1", "2.2"}
		// We assume the major versions is always the same. vmax may be absent.
		List<String> serverVersions = new ArrayList<>();
		String v = qr.getVersion(), vmax = qr.getMaxVersion();
		if (vmax == null || vmax.length() == 0)
			vmax = v;
		int min = Integer.parseInt(v.substring(2));
		int max = Integer.parseInt(vmax.substring(2));
		int maj = Integer.parseInt(vmax.substring(0, 1));
		for (int i = min; i <= max; ++i)
			serverVersions.add("" + maj + "." + i);

		// Now that we have a list of supported versions for both the server and ourselves,
		// we choose the highest element of the intersection of these two sets.
		// (TreeSet is an ordered set and .retainAll() is set intersection.)
		TreeSet<String> versions = new TreeSet<>(Arrays.asList(supportedVersions));
		versions.retainAll(serverVersions);
		return versions.last();
	}

	private void start(ClientQr qr, IrmaClientHandler handler) {
		try {
			protocolVersion = calculateProtocolVersion(qr);
		} catch (Exception e) {
			handler.onFailure(Action.UNKNOWN, "Invalid QR", null, e.getMessage());
			return;
		}

		// Check URL validity
		server = qr.getUrl();
		if (!Patterns.WEB_URL.matcher(server).matches()) {
			handler.onFailure(Action.UNKNOWN, "Protocol not supported", null,
					qr.getUrl() != null ? "URL: " + qr.getUrl() : "No URL specified.");
			return;
		}
		if (!server.endsWith("/"))
			server += "/";

		// We have a valid URL: let's go!
		this.handler = handler;
		this.handler.setIrmaClient(this);
		this.resources = handler.getActivity().getResources();
		this.transport = new IrmaHttpTransport(server);

		if (Pattern.matches(".*/verification/[^/]+/?$", server)) {
			action = Action.DISCLOSING;
			startSession(DisclosureProofRequest.class, ServiceProviderRequest.class);
		}
		else if (Pattern.matches(".*/issue/[^/]+/?$", server)) {
			action = Action.ISSUING;
			startSession(IssuingRequest.class, IdentityProviderRequest.class);
		}
		else if (Pattern.matches(".*/signature/[^/]+/?$", server)) {
			action = Action.SIGNING;
			startSession(SignatureProofRequest.class, SignatureClientRequest.class);
		}
	}

	// Failure reporting methods -------------------------------------------------------------------

	private void failSession(String feedback, boolean deleteSession, ApiErrorMessage error, String info) {
		Log.w(TAG, feedback);

		if (deleteSession)
			deleteSession();
		handler.onFailure(action, feedback, error, info);
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

	// Issuance methods ----------------------------------------------------------------------------

	/**
	 * Given an {@link IssuingRequest}, compute the first issuing message and post it to the server.
	 * If the server returns corresponding CL signatures,
	 * construct and save the new Idemix credentials.
	 */
	public void finishIssuance(final IssuingRequest request, final DisclosureChoice disclosureChoice) {
		handler.onStatusUpdate(Action.ISSUING, Status.COMMUNICATING);
		Log.i(TAG, "Posting issuing commitments to " + server);

		if (CredentialManager.isDistributed(request.getSchemeManager())) {
			try {
				this.builder = CredentialManager.generateProofListBuilderForIssuance(request, disclosureChoice);
			} catch (InfoException |CredentialsException |KeyException e) {
				e.printStackTrace();
				return;
			}
			startKeyshareProtocol();

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
		Type t = new TypeToken<ArrayList<IssueSignatureMessage>>(){}.getType();
		transport.post(t, "commitments", msg, new JsonResultHandler<ArrayList<IssueSignatureMessage>>() {
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
	 * Given a {@link DisclosureProofRequest} with selected attributes, perform the disclosure.
	 */
	public void disclose(final DisclosureProofRequest request, DisclosureChoice disclosureChoice) {
		proofSession(disclosureChoice);
	}

	private void proofSession(DisclosureChoice disclosureChoice) {
		handler.onStatusUpdate(action, Status.COMMUNICATING);
		Log.i(TAG, "Sending disclosure/signature proofs to " + server);

		String message = disclosureChoice.getRequest() instanceof SignatureProofRequest ?
				((SignatureProofRequest) disclosureChoice.getRequest()).getMessage() : null;

		if(CredentialManager.isDistributed(disclosureChoice.getRequest().getSchemeManager())) {
			try {
				this.builder = CredentialManager.generateProofListBuilderForDisclosure(
						disclosureChoice, message);
			} catch (CredentialsException|InfoException e) {
				// TODO!!
				e.printStackTrace();
				return;
			}
			startKeyshareProtocol();
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
		transport.post(DisclosureProofResult.Status.class, "proofs", proofs,
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
	 * Given a {@link SignatureProofRequest} with selected attributes, create an IRMA signature.
	 */
	public void sign(final SignatureProofRequest request, DisclosureChoice disclosureChoice) {
		proofSession(disclosureChoice);
	}

	// Methods used in the issuing, disclosure and signing protocols -------------------------------

	private <T extends SessionRequest, S extends ClientRequest<T>> void startSession(
			Class<T> requestClass, final Class<S> clientRequestClass) {
		handler.onStatusUpdate(action, Status.COMMUNICATING);
		Log.i(TAG, "Starting " + action + ": " + server);

		// Get the signature request

		switch (protocolVersion) {
			case "2.0":
				transport.get(requestClass, "", new JsonResultHandler<T>() {
					@Override public void onSuccess(final T request) {
						continueSession(request, action);
					}
				});
				break;
			case "2.1":
			case "2.2":
				transport.get(JwtSessionRequest.class, "jwt", new JsonResultHandler<JwtSessionRequest>() {
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

			if (request instanceof IssuingRequest) {
				for (CredentialRequest cred : ((IssuingRequest) request).getCredentials())
					cred.setKeyCounter(
							sessionRequest.getPublicKeys().get(cred.getIdentifier().getIssuerIdentifier()));
			}

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

		schemeManager = request.getSchemeManager();

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
	 * Cancel the current session.
	 */
	public void cancelSession() {
		deleteSession();
		handler.onCancelled(action);
	}


	/**
	 * Deletes the current session by DELETE-ing the specified url and setting the state to idle.
	 */
	private void deleteSession() {
		Log.i(TAG, "DELETEing " + server);
		transport.delete();
		server = null;
	}

	// Keyshare methods -------------------------------------------------------------------------------

	/**
	 * Starts the keyshare-part of the issuing or disclosure protocol.
	 * Ask the user for her PIN if necessary (i.e., if our keyshare JWT is absent or expired). We allow
	 * the user as much attempts as the keyshare server allows.
	 * After this (if successful) we use the valid JWT to get the
	 * keyshare's {@link ProofP} through {@link #continueProtocolWithAuthorization()}.
	 */
	private void startKeyshareProtocol() {
		KeyshareServer kss = CredentialManager.getKeyshareServer(schemeManager);
		keyshareClient = new JsonHttpClient(GsonUtil.getGson());
		keyshareClient.setExtraHeader(IRMAHeaders.USERNAME, kss.getUsername());
		keyshareClient.setExtraHeader(IRMAHeaders.AUTHORIZATION, kss.getToken());

		String url = kss.getUrl() + "/users/isAuthorized";
		keyshareClient.post(AuthorizationResult.class, url, "", new HttpResultHandler<AuthorizationResult>() {
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
					handler.verifyPin(CredentialManager.getKeyshareServer(schemeManager), IrmaClient.this);
				} else {
					Log.i(TAG, "Authorization is blocked!!!");
					fail("Keyshare authorization blocked", true);
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
		continueProtocolWithAuthorization();
	}

	@Override
	public void onPinCancelled() {
		fail("Pin entry cancelled", true);
	}

	@Override
	public void onPinError() {
		fail("Keyshare authentication blocked", true);
	}

	/**
	 * Generate our randomizers for the proofs of knowledge of our part of the secret key, and (possibly)
	 * hidden attributes; afterwards continue the protocol with
	 * {@link #obtainKeyshareProofPCommitments(List)}.
	 */
	private void continueProtocolWithAuthorization() {
		builder.generateRandomizers();
		List<PublicKeyIdentifier> pkids = builder.getPublicKeyIdentifiers();
		Log.i(TAG, "Trying to obtain commitments from keyshare server now!");
		obtainKeyshareProofPCommitments(pkids);
	}

	/**
	 * Ask the keyshare server for R_0^w mod n, with w being its randomness, and n the moduli
	 * of each of the specified public keys; if successful, continue by calculating the challenge and
	 * asking for the responses by {@link #obtainKeyshareProofP(ProofPCommitmentMap)}.
	 * @param pkids List of public keys; for the n of each of these, we ask for R_0^w mod n
	 */
	private void obtainKeyshareProofPCommitments(final List<PublicKeyIdentifier> pkids) {
		KeyshareServer kss = CredentialManager.getKeyshareServer(schemeManager);
		keyshareClient.setExtraHeader(IRMAHeaders.USERNAME, kss.getUsername());
		keyshareClient.setExtraHeader(IRMAHeaders.AUTHORIZATION, kss.getToken());

		Log.i(TAG, "Posting to the keyshare server now!");
		keyshareClient.post(ProofPCommitmentMap.class, kss.getUrl() + "/prove/getCommitments", pkids, new JsonResultHandler<ProofPCommitmentMap>() {
			@Override public void onSuccess(ProofPCommitmentMap result) {
				Log.i(TAG, "Unbelievable, it actually worked:");
				for(PublicKeyIdentifier pkid : pkids) {
					Log.i(TAG, "Key " + pkid + ", response " + result.get(pkid));
				}
				Log.i(TAG, "Calling next function in this protocol class:" + IrmaClient.this);

				obtainKeyshareProofP(result);
			}
		});
	}

	/**
	 * Calculate the challenge c using our commitments and that of the keyshare server, post that to the
	 * keyshare server, and ask for the response c * m + w (where m is the keyshare's private key and w its
	 * randomness used previously in its commitment). If successful, combine our responses with that
	 * of the keyshare server, and continue the protocol normally using
	 * {@link #finishDistributedProtocol(ProofP, BigInteger)}.
	 * @param plistcom List of commitments of the keyshare for each public key
	 */
	private void obtainKeyshareProofP(ProofPCommitmentMap plistcom) {
		KeyshareServer kss = CredentialManager.getKeyshareServer(schemeManager);

		ProofListBuilder.Commitment com = builder.calculateCommitments();
		com.mergeProofPCommitments(plistcom);
		final BigInteger challenge = com.calculateChallenge(
				builder.getContext(), builder.getNonce(), action == Action.SIGNING);

		final BigInteger encryptedChallenge = kss.getPublicKey().encrypt(challenge);

		if (action == Action.ISSUING) {
			CredentialManager.addPublicSKs(plistcom);
		}

		keyshareClient.setExtraHeader(IRMAHeaders.USERNAME, kss.getUsername());
		keyshareClient.setExtraHeader(IRMAHeaders.AUTHORIZATION, kss.getToken());

		Log.i(TAG, "Posting challenge to the keyshare server now!");
		keyshareClient.post(ProofP.class, kss.getUrl() + "/prove/getResponse", encryptedChallenge, new JsonResultHandler<ProofP>() {
			@Override public void onSuccess(ProofP result) {
				Log.i(TAG, "Unbelievable, also received a ProofP from the server");
				IrmaClient.this.finishDistributedProtocol(result, challenge);
			}
		});
	}

	/**
	 * Combine our proofs with that of the keyshare server, and continue the protocol normally using
	 * {@link #sendDisclosureProofs(ProofList)} or
	 * {@link #sendIssueCommitmentMessage(IssueCommitmentMessage)}.
	 * @param proofp The keyshare server's proof of its part of the secret key
	 */
	private void finishDistributedProtocol(ProofP proofp, BigInteger challenge) {
		proofp.decrypt(CredentialManager.getKeyshareServer(schemeManager).getKeyPair());
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

