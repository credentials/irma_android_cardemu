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
import com.google.gson.reflect.TypeToken;
import org.irmacard.android.util.credentials.StoreManager;
import org.irmacard.api.common.*;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.CredentialBuilder;
import org.irmacard.credentials.idemix.IdemixCredential;
import org.irmacard.credentials.idemix.IdemixSystemParameters;
import org.irmacard.credentials.idemix.info.IdemixKeyStore;
import org.irmacard.credentials.idemix.messages.IssueCommitmentMessage;
import org.irmacard.credentials.idemix.messages.IssueSignatureMessage;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.credentials.idemix.proofs.ProofListBuilder;
import org.irmacard.credentials.info.*;
import org.irmacard.credentials.util.log.IssueLogEntry;
import org.irmacard.credentials.util.log.LogEntry;
import org.irmacard.credentials.util.log.RemoveLogEntry;
import org.irmacard.credentials.util.log.VerifyLogEntry;

import java.lang.reflect.Type;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.*;

/**
 * Handles issuing, disclosing, and deletion of credentials; keeps track of log entries; and handles (de)
 * serialization of credentials and log entries from/to storage.
 */
public class CredentialManager {
	private static HashMap<CredentialIdentifier, IdemixCredential> credentials = new HashMap<>();
	private static List<LogEntry> logs = new LinkedList<>();
	private static BigInteger secretKey;

	// Type tokens for Gson (de)serialization
	private static Type credentialsType = new TypeToken<HashMap<CredentialIdentifier, IdemixCredential>>() {}.getType();
	private static Type logsType = new TypeToken<List<LogEntry>>() {}.getType();

	private static SharedPreferences settings;
	private static DescriptionStore descriptionStore;
	private static IdemixKeyStore keyStore;

	private static final String TAG = "CredentialManager";
	private static final String CREDENTIAL_STORAGE = "credentials";
	private static final String LOG_STORAGE = "logs";

	// Issuing state
	private static List<CredentialBuilder> credentialBuilders;

	public static void init(SharedPreferences s) {
		settings = s;
		try {
			descriptionStore = DescriptionStore.getInstance();
			keyStore = IdemixKeyStore.getInstance();
		} catch (InfoException e) { // Can't recover from this, crash now
			throw new RuntimeException("Could not read DescriptionStore", e);
		}
		load();
	}

	/**
	 * Saves the credentials and logs to storage.
	 */
	public static void save() {
		Log.i(TAG, "Saving credentials");

		Gson gson = GsonUtil.getGson();
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

		Gson gson = GsonUtil.getGson();
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
	}

	/**
	 * Given a credential identifier, return the corresponding credential if we have it.
	 * @return The credential, or null if we do not have it
	 * @throws CredentialsException if this credential identifier was not found in the DescriptionStore
	 */
	public static Attributes getAttributes(CredentialIdentifier identifier) throws CredentialsException {
		CredentialDescription cd = getCredentialDescription(identifier);

		IdemixCredential credential = credentials.get(identifier);
		if (credential == null)
			return null;

		Attributes attributes = new Attributes();
		attributes.add(Attributes.META_DATA_FIELD, credential.getAttribute(1).toByteArray());
		for (int i = 0; i < cd.getAttributeNames().size(); i++) {
			String name = cd.getAttributeNames().get(i);
			BigInteger value = credential.getAttribute(i + 2); // + 2: skip secret key and metadata
			attributes.add(name, value.toByteArray());
		}

		return attributes;
	}

	/**
	 * Get a map containing all credential descriptions and attributes we currently have
	 */
	public static HashMap<CredentialDescription, Attributes> getAllAttributes() {
		HashMap<CredentialDescription, Attributes> map = new HashMap<>();

		for (CredentialIdentifier id : credentials.keySet()) {
			try {
				map.put(getCredentialDescription(id), getAttributes(id));
			} catch (CredentialsException e) { /** Ignore absent descriptions, might be downloaded later */ }
		}

		return map;
	}

	private static void delete(CredentialDescription cd, boolean shouldSave) {
		if (cd == null)
			return;

		IdemixCredential cred = credentials.remove(cd.getIdentifier());

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
	 * Delete all credentials.
	 */
	public static void deleteAll() {
		for (CredentialIdentifier id : credentials.keySet()) {
			CredentialDescription cd = descriptionStore.getCredentialDescription(id);
			if (cd == null)
				continue;
			logs.add(0, new RemoveLogEntry(Calendar.getInstance().getTime(), cd));
		}

		credentials = new HashMap<>();

		save();
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

		Map<CredentialIdentifier, List<Integer>> toDisclose = groupAttributesById(attributes);

		for (CredentialIdentifier id : toDisclose.keySet()) {
			List<Integer> attrs = toDisclose.get(id);
			IdemixCredential credential = credentials.get(id);
			builder.addProofD(credential, attrs);
		}

		logs.addAll(0, generateLogEntries(toDisclose));

		return builder;
	}

	/**
	 * Helper function that, given a list of {@link AttributeDisjunction} that each have a selected member (as in,
	 * {@link AttributeDisjunction#getSelected()} returns non-null), groups all selected attributes by credential ID.
	 */
	private static Map<CredentialIdentifier, List<Integer>> groupAttributesById(List<AttributeDisjunction> content)
	throws CredentialsException {
		Map<CredentialIdentifier, List<Integer>> toDisclose = new HashMap<>();

		// Group the chosen attribute identifiers by their credential ID in the toDisclose map
		for (AttributeDisjunction disjunction : content) {
			AttributeIdentifier identifier = disjunction.getSelected();
			CredentialIdentifier id = identifier.getCredentialIdentifier();
			CredentialDescription cd = getCredentialDescription(id);

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
	private static List<LogEntry> generateLogEntries(Map<CredentialIdentifier, List<Integer>> toDisclose) {
		List<LogEntry> logs = new ArrayList<>(toDisclose.size());

		for (CredentialIdentifier id : toDisclose.keySet()) {
			List<Integer> attributes = toDisclose.get(id);

			CredentialDescription cd = descriptionStore.getCredentialDescription(id);
			if (cd == null)
				continue;
			HashMap<String, Boolean> booleans = new HashMap<>(cd.getAttributeNames().size());

			for (int i = 0; i < cd.getAttributeNames().size(); ++i) {
				String attrName = cd.getAttributeNames().get(i);
				booleans.put(attrName, attributes.contains(i + 2));
			}

			logs.add(new VerifyLogEntry(Calendar.getInstance().getTime(), cd, booleans));
		}

		return logs;
	}

	/**
	 * If the {@link DescriptionStore} or {@link IdemixKeyStore} do not contain all issuers, credential types,
	 * or public keys from our credentials, use their download methods to download the missing info.
	 * @param handler Callbacks to call when done. If nothing was downloaded, this is not used.
	 * @return True if anything was downloaded so that the callback was used; false otherwise
	 */
	public static boolean updateStores(StoreManager.DownloadHandler handler) {
		HashSet<IssuerIdentifier> issuers = new HashSet<>();
		HashMap<IssuerIdentifier,Integer> keys = new HashMap<>();
		HashSet<CredentialIdentifier> creds = new HashSet<>();

		for (CredentialIdentifier credential : credentials.keySet()) {
			IssuerIdentifier issuer = credential.getIssuerIdentifier();

			if (descriptionStore.getCredentialDescription(credential) == null)
				creds.add(credential);
			if (descriptionStore.getIssuerDescription(issuer) == null)
				issuers.add(issuer);

			int counter = credentials.get(credential).getKeyCounter();
			if (!keyStore.containsPublicKey(issuer, counter))
				keys.put(issuer, counter);
		}

		if (issuers.size() == 0 && creds.size() == 0 && keys.size() == 0)
			return false;

		StoreManager.download(issuers, creds, keys, handler);
		return true;
	}

	/**
	 * Helper function that either returns a non-null CredentialDescription or throws an exception
	 */
	private static CredentialDescription getCredentialDescription(CredentialIdentifier identifier)
	throws CredentialsException {
		// Find the corresponding CredentialDescription
		CredentialDescription cd = descriptionStore.getCredentialDescription(identifier);
		if (cd == null)
			throw new CredentialsException("Unknown issuer or credential");

		return cd;
	}

	/**
	 * Given an {@link AttributeDisjunction}, return attributes (and their values) that we have and that are
	 * contained in the disjunction. If the disjunction has values for each identifier, then this returns
	 * only those attributes matching those values.
	 */
	public static LinkedHashMap<AttributeIdentifier, String> getCandidates(AttributeDisjunction disjunction) {
		LinkedHashMap<AttributeIdentifier, String> map = new LinkedHashMap<>();

		for (AttributeIdentifier attribute : disjunction) {
			Attributes foundAttrs;
			CredentialDescription cd;
			try {
				foundAttrs = getAttributes(attribute.getCredentialIdentifier());
				cd = getCredentialDescription(attribute.getCredentialIdentifier());
			} catch (CredentialsException e) {
				e.printStackTrace();
				continue; // Can't disclose unknown attributes
			}

			if (foundAttrs != null) {
				if (attribute.isCredential()) {
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

	public static List<AttributeDisjunction> getUnsatisfiableDisjunctions(List<AttributeDisjunction> disjunctions) {
		List<AttributeDisjunction> missing = new ArrayList<>();
		for (AttributeDisjunction disjunction : disjunctions) {
			if (CredentialManager.getCandidates(disjunction).isEmpty()) {
				missing.add(disjunction);
			}
		}
		return missing;
	}

	/**
	 * Check if we have the specified attribute.
	 */
	public static boolean contains(AttributeIdentifier identifier) {
		try {
			Attributes attrs = getAttributes(identifier.getCredentialIdentifier());
			return attrs != null && (identifier.isCredential() || attrs.get(identifier.getAttributeName()) != null);
		} catch (CredentialsException e) {
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
				secretKey = credentials.values().iterator().next().getAttribute(0);
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
			IdemixCredential cred = credentialBuilders.get(i).constructCredential(sigs.get(i));

			CredentialDescription cd = cred.getAllAttributes().getCredentialDescription();
			if (cd == null)
				throw new InfoException("Unknown credential");

			credentials.put(cd.getIdentifier(), cred);
			logs.add(0, new IssueLogEntry(Calendar.getInstance().getTime(), cd));
		}

		save();
	}
}
