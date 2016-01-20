/*
 * Copyright (c) 2015, the IRMA Team
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 *  Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 *  Neither the name of the IRMA project nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.irmacard.cardemu;

import android.content.SharedPreferences;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.sf.scuba.smartcards.CardServiceException;
import org.irmacard.api.common.*;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.CredentialBuilder;
import org.irmacard.credentials.idemix.IdemixCredential;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.IdemixSystemParameters;
import org.irmacard.credentials.idemix.messages.IssueCommitmentMessage;
import org.irmacard.credentials.idemix.messages.IssueSignatureMessage;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.credentials.idemix.proofs.ProofListBuilder;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.IRMAIdemixCredential;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.util.log.IssueLogEntry;
import org.irmacard.credentials.util.log.LogEntry;
import org.irmacard.credentials.util.log.RemoveLogEntry;
import org.irmacard.credentials.util.log.VerifyLogEntry;
import org.irmacard.idemix.IdemixService;

import java.lang.reflect.Type;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.*;

/**
 * Handles issuing, disclosing, and deletion of credentials; keeps track of log entries; and handles (de)
 * serialization of credentials and log entries from/to storage.
 */
@SuppressWarnings("unused")
public class CredentialManager {
	private static HashMap<Short, IRMAIdemixCredential> credentials = new HashMap<>();
	private static List<LogEntry> logs = new LinkedList<>();
	private static BigInteger secretKey;

	// Type tokens for Gson (de)serialization
	private static Type credentialsType = new TypeToken<HashMap<Short, IRMAIdemixCredential>>() {}.getType();
	private static Type logsType = new TypeToken<List<LogEntry>>() {}.getType();

	private static Gson gson;
	private static SharedPreferences settings;
	private static final String TAG = "CredentialManager";
	private static final String CREDENTIAL_STORAGE = "credentials";
	private static final String LOG_STORAGE = "logs";

	// Issuing state
	private static List<CredentialBuilder> credentialBuilders;

	public static void init(SharedPreferences s) {
		settings = s;
	}

	/**
	 * Clear the instance: throw away all credentials and logs.
	 */
	public static void clear() {
		credentials = new HashMap<>();
		logs = new LinkedList<>();
	}

	/**
	 * Clear existing credentials and logs, and load them from the default card.
	 */
	public static void loadDefaultCard() {
		clear();
		loadFromCard(CardManager.loadDefaultCard());
	}

	/**
	 * Extract and insert credentials and logs from an IRMACard instance retrieved from storage
	 */
	public static void loadFromCard() {
		loadFromCard(CardManager.getCard());
	}

	/**
	 * Extract and insert credentials and logs from the specified IRMACard
	 */
	@SuppressWarnings("unchecked")
	public static void loadFromCard(IRMACard card) {
		Log.i(TAG, "Loading credentials from card");

		credentials.putAll(card.getCredentials());

		try {
			IdemixService is = new IdemixService(new SmartCardEmulatorService(card));
			is.open();
			is.sendCardPin("000000".getBytes());
			logs.addAll(0, new IdemixCredentials(is).getLog());
		} catch (CardServiceException | InfoException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Create a new IRMACard containing all credentials, insert it into the CardManager, and serialize it to storage
	 * @return the new IRMACard
	 */
	public static IRMACard saveCard() {
		IRMACard card = new IRMACard();
		card.setCredentials(credentials);
		if (credentials.size() > 0) {
			BigInteger sk = credentials.values().iterator().next().getCredential().getAttribute(0);
			card.setMasterSecret(sk);
		}

		CardManager.setCard(card);
		return card;
	}

	private static Gson getGson() {
		if (gson == null) {
			gson = new GsonBuilder()
					.registerTypeAdapter(LogEntry.class, new LogEntrySerializer())
					.create();
		}

		return gson;
	}

	/**
	 * Saves the credentials and logs to storage.
	 */
	public static void save() {
		Log.i(TAG, "Saving credentials");

		saveCard();

		Gson gson = getGson();
		String credentialsJson = gson.toJson(credentials, credentialsType);
		String logsJson = gson.toJson(logs, logsType);

		settings.edit()
				.putString(CREDENTIAL_STORAGE, credentialsJson)
				.putString(LOG_STORAGE, logsJson)
				.apply();
	}

	/**
	 * Loads the credentials and logs from storage.
	 */
	public static void load() {
		Log.i(TAG, "Loading credentials");

		Gson gson = getGson();
		String credentialsJson = settings.getString(CREDENTIAL_STORAGE, "");
		String logsJson = settings.getString(LOG_STORAGE, "");

		if (!credentialsJson.equals("")) {
			try {
				credentials = gson.fromJson(credentialsJson, credentialsType);
			} catch (Exception e) {
				credentials = new HashMap<>();
			}
		}
		if (credentials == null) {
			credentials = new HashMap<>();
		}

		if (!logsJson.equals("")) {
			try {
				logs = gson.fromJson(logsJson, logsType);
			} catch (Exception e) {
				logs = new LinkedList<>();
			}
		}
		if (logs == null) {
			logs = new LinkedList<>();
		}

		// Upgrade path from the old protocol-branch to the new protocol-branch:
		// Normally, we only temporarily save the credentials from this.credentials into a new,
		// temporary IRMACard. So if we do not yet have any credentials while the IRMACard from
		// storage does, it must mean we're called for the very first time, so that the user's
		// credentials are still in the CardManager. So, we fetch them.
		IRMACard card = CardManager.loadCard();
		if (credentials.size() == 0 && card.getCredentials().size() > 0) {
			loadFromCard();
			save();
		}
	}

	/**
	 * Given an Idemix credential, return its attributes.
	 *
	 * @throws InfoException if we received null, or if the credential type was not found in the DescriptionStore
	 */
	private static Attributes getAttributes(IdemixCredential credential) throws InfoException {
		Attributes attributes = new Attributes();

		short id = Attributes.extractCredentialId(credential.getAttribute(1));
		CredentialDescription cd = DescriptionStore.getInstance().getCredentialDescription(id);

		if (cd == null)
			throw new InfoException("Credential type not found in DescriptionStore");

		attributes.add(Attributes.META_DATA_FIELD, credential.getAttribute(1).toByteArray());
		for (int i = 0; i < cd.getAttributeNames().size(); i++) {
			String name = cd.getAttributeNames().get(i);
			BigInteger value = credential.getAttribute(i + 2); // + 2: skip secret key and metadata
			attributes.add(name, value.toByteArray());
		}

		return attributes;
	}

	/**
	 * Given an issuer and credential name, return the corresponding credential if we have it.
	 * @return The credential, or null if we do not have it
	 * @throws InfoException if this combination of issuer/credentialname was not found in the DescriptionStore
	 */
	public static Attributes getAttributes(String issuer, String credentialName) throws InfoException {
		if (issuer == null || credentialName == null)
			return null;

		CredentialDescription cd = DescriptionStore.getInstance().getCredentialDescriptionByName(issuer, credentialName);
		if (cd == null)
			throw new InfoException("Issuer or credential not found in DescriptionStore");

		IRMAIdemixCredential credential = credentials.get(cd.getId());
		if (credential == null)
			return null;

		return getAttributes(credential.getCredential());
	}

	/**
	 * Given a credential ID, return the corresponding credential if we have it
	 * @return The credential, or null if we do not have it
	 * @throws InfoException if no credential with this id was found in the DescriptionStore
	 */
	public static Attributes getAttributes(short id) throws InfoException {
		CredentialDescription cd = DescriptionStore.getInstance().getCredentialDescription(id);
		if (cd == null)
			throw new InfoException("Credential type not found in DescriptionStore");

		IRMAIdemixCredential credential = credentials.get(cd.getId());
		if (credential == null)
			return null;

		return getAttributes(credential.getCredential());
	}

	/**
	 * Get a map containing all credential descriptions and attributes we currently have
	 */
	public static HashMap<CredentialDescription, Attributes> getAllAttributes() {
		HashMap<CredentialDescription, Attributes> map = new HashMap<>();
		CredentialDescription cd;

		for (short id : credentials.keySet()) {
			try {
				cd = DescriptionStore.getInstance().getCredentialDescription(id);
				map.put(cd, getAttributes(credentials.get(id).getCredential()));
			} catch (InfoException e) {
				e.printStackTrace();
			}
		}

		return map;
	}

	private static void delete(CredentialDescription cd, boolean shouldSave) {
		if (cd == null)
			return;

		IRMAIdemixCredential cred = credentials.remove(cd.getId());

		if (cred != null) {
			logs.add(0, new RemoveLogEntry(Calendar.getInstance().getTime(), cd));
			if (shouldSave)
				save();
		}
	}

	/**
	 * Delete the credential with this description if we have it
	 */
	public static void delete(CredentialDescription cd) {
		delete(cd, true);
	}

	/**
	 * Delete the credential with this id if we have it
	 */
	public static void delete(short id) {
		delete(id, true);
	}

	private static void delete(short id, boolean shouldSave) {
		try {
			CredentialDescription cd = DescriptionStore.getInstance().getCredentialDescription(id);
			delete(cd, shouldSave);
		} catch (InfoException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Delete all credentials.
	 */
	public static void deleteAll() {
		for (short id : credentials.keySet()) {
			try {
				CredentialDescription cd = DescriptionStore.getInstance().getCredentialDescription(id);
				logs.add(0, new RemoveLogEntry(Calendar.getInstance().getTime(), cd));
			} catch (InfoException e) {
				e.printStackTrace();
			}
		}

		credentials = new HashMap<>();

		save();
	}

	public static boolean isEmpty() {
		return credentials.isEmpty();
	}

	public static List<LogEntry> getLog() {
		return logs;
	}

	/**
	 * Given a disclosure request with selected attributes, build a proof collection. This function assumes that each
	 * {@link AttributeDisjunction} in the contents of the request has a selected (as in
	 * {@link AttributeDisjunction#getSelected()}) {@link AttributeIdentifier}, and we assume that we have the
	 * credential of this identifier.
	 * @throws CredentialsException if something goes wrong
	 */
	public static ProofList getProofs(DisclosureProofRequest request) throws CredentialsException {
		ProofListBuilder builder = new ProofListBuilder(request.getContext(), request.getNonce());
		return getProofs(request.getContent(), builder).build();
	}

	/**
	 * For the selected attribute of each disjunction, add a disclosure proof-commitment to the specified
	 * proof builder.
	 */
	private static ProofListBuilder getProofs(AttributeDisjunctionList attributes, ProofListBuilder builder)
			throws CredentialsException {
		if (!attributes.haveSelected())
			throw new CredentialsException("Select an attribute in each disjunction first");

		Map<Short, List<Integer>> toDisclose = groupAttributesById(attributes);

		for (short id : toDisclose.keySet()) {
			List<Integer> attrs = toDisclose.get(id);
			IdemixCredential credential = credentials.get(id).getCredential();
			builder.addProofD(credential, attrs);
		}

		logs.addAll(0, generateLogEntries(toDisclose));

		return builder;
	}

	/**
	 * Helper function that, given a list of {@link AttributeDisjunction} that each have a selected member (as in,
	 * {@link AttributeDisjunction#getSelected()} returns non-null), groups all selected attributes by credential ID.
	 */
	private static Map<Short, List<Integer>> groupAttributesById(List<AttributeDisjunction> content)
	throws CredentialsException {
		Map<Short, List<Integer>> toDisclose = new HashMap<>();

		// Group the chosen attribute identifiers by their credential ID in the toDisclose map
		for (AttributeDisjunction disjunction : content) {
			AttributeIdentifier identifier = disjunction.getSelected();

			String issuer = identifier.getIssuerName();
			String credentialName = identifier.getCredentialName();
			CredentialDescription cd = getCredentialDescription(issuer, credentialName);
			short id = cd.getId();

			List<Integer> attributes;
			if (toDisclose.get(id) == null) {
				attributes = new ArrayList<>(5);
				attributes.add(1); // Always disclose metadata
				toDisclose.put(id, attributes);
			} else
				attributes = toDisclose.get(id);

			if (identifier.isCredential())
				continue;

			int j = cd.getAttributeNames().indexOf(identifier.getAttributeName());
			if (j == -1) // our CredentialDescription does not contain the asked-for attribute
				throw new CredentialsException("Attribute \"" + identifier.getAttributeName() + "\" not found");

			attributes.add(j + 2);
		}

		return toDisclose;
	}

	/**
	 * Log that we disclosed the specified attributes.
	 */
	private static List<LogEntry> generateLogEntries(Map<Short, List<Integer>> toDisclose)
	throws CredentialsException {
		List<LogEntry> logs = new ArrayList<>(toDisclose.size());

		for (short id : toDisclose.keySet()) {
			List<Integer> attributes = toDisclose.get(id);

			try {
				CredentialDescription cd = DescriptionStore.getInstance().getCredentialDescription(id);
				HashMap<String, Boolean> booleans = new HashMap<>(cd.getAttributeNames().size());

				for (int i = 0; i < cd.getAttributeNames().size(); ++i) {
					String attrName = cd.getAttributeNames().get(i);
					booleans.put(attrName, attributes.contains(i + 2));
				}

				// The third argument should be a VerificationDescription, and we don't have one here.
				// Fortunately it seems to be optional, at least for the log screen...
				logs.add(new VerifyLogEntry(Calendar.getInstance().getTime(), cd, null, booleans));
			} catch (InfoException e) {
				throw new CredentialsException(e);
			}
		}

		return logs;
	}

	/**
	 * Helper function that either returns a non-null CredentialDescription or throws an exception
	 */
	private static CredentialDescription getCredentialDescription(String issuer, String credentialName)
	throws CredentialsException {
		// Find the corresponding CredentialDescription
		CredentialDescription cd;
		try {
			cd = DescriptionStore.getInstance().getCredentialDescriptionByName(issuer, credentialName);
		} catch (InfoException e) { // Should not happen
			e.printStackTrace();
			throw new CredentialsException("Could not read DescriptionStore", e);
		}
		if (cd == null)
			throw new CredentialsException("Unknown issuer or credential");

		return cd;
	}

	/**
	 * Given a disclosure request, see if we have satisfy it - i.e., if we have at least one attribute for each
	 * disjunction.
	 */
	public static boolean canSatisfy(DisclosureProofRequest request) {
		for (AttributeDisjunction disjunction : request.getContent())
			if (getCandidates(disjunction).isEmpty())
				return false;

		return true;
	}

	/**
	 * Given an {@link AttributeDisjunction}, return attributes (and their values) that we have and that are
	 * contained in the disjunction. If the disjunction has values for each identifier, then this returns
	 * only those attributes matching those values.
	 */
	public static LinkedHashMap<AttributeIdentifier, String> getCandidates(AttributeDisjunction disjunction) {
		LinkedHashMap<AttributeIdentifier, String> map = new LinkedHashMap<>();

		for (AttributeIdentifier attribute : disjunction) {
			Attributes foundAttrs = null;
			CredentialDescription cd = null;
			try {
				foundAttrs = getAttributes(attribute.getIssuerName(), attribute.getCredentialName());
				cd = getCredentialDescription(attribute.getIssuerName(), attribute.getCredentialName());
			} catch (InfoException|CredentialsException e) {
				e.printStackTrace();
			}

			if (foundAttrs != null) {
				if (attribute.isCredential() && cd != null) {
					map.put(attribute, cd.getIssuerID() + " - " + cd.getShortName());
				}
				if (!attribute.isCredential() && foundAttrs.get(attribute.getAttributeName()) != null) {
					String requiredValue = disjunction.getValues().get(attribute);
					String value = new String(foundAttrs.get(attribute.getAttributeName()));

					if (requiredValue == null || requiredValue.equals(value))
						map.put(attribute, value);
				}
			}
		}

		return map;
	}

	/**
	 * Check if we have the specified attribute.
	 */
	public static boolean contains(AttributeIdentifier identifier) {
		try {
			Attributes attrs = getAttributes(identifier.getIssuerName(), identifier.getCredentialName());
			if (attrs == null)
				return false;
			if (identifier.isCredential())
				return true;
			else
				return attrs.get(identifier.getAttributeName()) != null;
		} catch (InfoException e) {
			return false;
		}
	}

	/**
	 * Returns the secret key from one of our credentials; or, if we do not yet have any credentials,
	 * a new secret key.
	 */
	private static BigInteger getSecretKey() {
		if (secretKey == null) {
			if (credentials == null || credentials.size() == 0)
				secretKey = new BigInteger(new IdemixSystemParameters().l_m, new SecureRandom());
			else
				secretKey = credentials.values().iterator().next().getCredential().getAttribute(0);
		}

		return secretKey;
	}

	/**
	 * Compute the first message in the issuing protocol: the commitments to the secret key and v_prime,
	 * the corresponding proofs of correctness, and the user nonce.
	 * @param request The request containing the description of the credentials that will be issued
	 * @return The message
	 * @throws InfoException If one of the credentials in the request or its attributes is not contained or
	 *         does not match our DescriptionStore
	 */
	public static IssueCommitmentMessage getIssueCommitments(IssuingRequest request)
			throws InfoException, CredentialsException {
		if (!request.credentialsMatchStore())
			throw new InfoException("Request contains mismatching attributes");

		// Initialize issuing state
		credentialBuilders = new ArrayList<>(request.getCredentials().size());

		// TODO This reuses the same nonce for all credentials: not good once pk sizes start to vary
		BigInteger nonce2 = CredentialBuilder.createReceiverNonce(request.getCredentials().get(0).getPublicKey());

		// Construct the commitment proofs
		ProofListBuilder proofsBuilder = new ProofListBuilder(request.getContext(), request.getNonce());
		proofsBuilder.setSecretKey(getSecretKey());

		for (CredentialRequest cred : request.getCredentials()) {
			CredentialBuilder cb = new CredentialBuilder(
					cred.getPublicKey(), cred.convertToBigIntegers(), request.getContext(), nonce2);
			proofsBuilder.addCredentialBuilder(cb);
			credentialBuilders.add(cb);
		}

		// Add disclosures, if any
		if (!request.getRequiredAttributes().isEmpty())
			getProofs(request.getRequiredAttributes(), proofsBuilder);

		return new IssueCommitmentMessage(proofsBuilder.build(), nonce2);
	}

	/**
	 * Given the issuer's reply to our {@link IssueCommitmentMessage}, compute the new Idemix credentials and save them
	 * @param sigs The message from the issuer containing the CL signatures and proofs of correctness
	 * @throws InfoException If one of the credential types is unknown (this should already have happened in
	 *         {@link #getIssueCommitments(IssuingRequest)})
	 * @throws CredentialsException If one of the credentials could not be reconstructed (e.g., because of an incorrect
	 *         issuer proof)
	 */
	public static void constructCredentials(List<IssueSignatureMessage> sigs)
			throws InfoException, CredentialsException {
		if (sigs.size() != credentialBuilders.size())
			throw new CredentialsException("Received unexpected amount of signatures");

		for (int i = 0; i < sigs.size(); ++i) {
			// TODO what about the IdemixFlags contained in IRMAIdemixCredential?
			IRMAIdemixCredential irmaCred = new IRMAIdemixCredential(null);
			IdemixCredential cred = credentialBuilders.get(i).constructCredential(sigs.get(i));
			irmaCred.setCredential(cred);

			short id = Attributes.extractCredentialId(cred.getAttribute(1));
			credentials.put(id, irmaCred);

			CredentialDescription cd = DescriptionStore.getInstance().getCredentialDescription(id);
			logs.add(0, new IssueLogEntry(Calendar.getInstance().getTime(), cd));
		}

		save();
	}
}
