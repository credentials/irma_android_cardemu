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
import org.irmacard.cardemu.store.StoreManager;
import org.irmacard.api.common.*;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.identifiers.IdemixAttributeIdentifier;
import org.irmacard.cardemu.identifiers.IdemixCredentialIdentifier;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.CredentialBuilder;
import org.irmacard.credentials.idemix.DistributedCredentialBuilder;
import org.irmacard.credentials.idemix.IdemixCredential;
import org.irmacard.credentials.idemix.IdemixSystemParameters1024;
import org.irmacard.credentials.idemix.info.IdemixKeyStore;
import org.irmacard.credentials.idemix.messages.IssueCommitmentMessage;
import org.irmacard.credentials.idemix.messages.IssueSignatureMessage;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.credentials.idemix.proofs.ProofListBuilder;
import org.irmacard.credentials.idemix.proofs.ProofPCommitmentMap;
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
	private static HashMap<CredentialIdentifier, ArrayList<IdemixCredential>> credentials = new HashMap<>();
	private static List<LogEntry> logs = new LinkedList<>();
	private static BigInteger secretKey;

	// Type tokens for Gson (de)serialization
	private static Type oldCredentialsType
			= new TypeToken<HashMap<CredentialIdentifier, IdemixCredential>>() {}.getType();
	private static Type credentialsType
			= new TypeToken<HashMap<CredentialIdentifier, ArrayList<IdemixCredential>>>() {}.getType();
	private static Type logsType = new TypeToken<List<LogEntry>>() {}.getType();

	private static SharedPreferences settings;
	private static DescriptionStore descriptionStore;
	private static IdemixKeyStore keyStore;

	private static final String TAG = "CredentialManager";
	public static final String CREDENTIAL_STORAGE = "credentials";
	private static final String LOG_STORAGE = "logs";

	// TODO: compare this with the global preference, one needs to go
	private static final String DISTRIBUTED_FLAG = "distributedFlag";

	// TODO: not sure if this is a nice place, but at least this I can reach
	private static final String CLOUD_USERNAME = "CloudUsername";
	private static final String CLOUD_SERVER = "CloudServer";
	private static final String CLOUD_TOKEN = "CloudToken";

	private static String cloudUsername;
	private static String cloudServer;
	private static String cloudToken;

	// Issuing state
	private static List<CredentialBuilder> credentialBuilders;
	private static BigInteger nonce2;

	private static boolean isDistributed = true;

	public static void init(SharedPreferences s) throws CredentialsException {
		settings = s;

		descriptionStore = DescriptionStore.getInstance();
		keyStore = IdemixKeyStore.getInstance();

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
				.putBoolean(DISTRIBUTED_FLAG, isDistributed)
				.putString(CLOUD_USERNAME, cloudUsername)
				.putString(CLOUD_SERVER, cloudServer)
				.putString(CLOUD_TOKEN, cloudToken)
				.apply();
	}

	/**
	 * Loads the credentials and logs from storage.
	 */
	public static void load() throws CredentialsException {
		Log.i(TAG, "Loading credentials");

		Gson gson = GsonUtil.getGson();
		String credentialsJson = settings.getString(CREDENTIAL_STORAGE, "");
		String logsJson = settings.getString(LOG_STORAGE, "");
		setDistributed(settings.getBoolean(DISTRIBUTED_FLAG, false));

		cloudUsername = settings.getString(CLOUD_USERNAME, "");
		cloudServer = settings.getString(CLOUD_SERVER, "");
		cloudToken = settings.getString(CLOUD_TOKEN, "");

		if (!credentialsJson.equals("")) {
			try {
				credentials = gson.fromJson(credentialsJson, credentialsType);
			} catch (Exception e) {
				// See if the JSON is of the old serialization format. If it is not,
				// i.e., if the function below also fails, then it throws a CredentialException
				// for our caller to deal with.
				tryParseOldCredentials(credentialsJson);
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
	 * Try to parse the given JSON as credentials serialized in the old format.
	 * @throws CredentialsException if deserialization fails
	 */
	private static void tryParseOldCredentials(String json) throws CredentialsException {
		HashMap<CredentialIdentifier, IdemixCredential> map;

		try {
			map = GsonUtil.getGson().fromJson(json, oldCredentialsType);
		} catch (Exception e) {
			throw new CredentialsException(e);
		}

		credentials = new HashMap<>(map.size());
		for (CredentialIdentifier ci : map.keySet()) {
			ArrayList<IdemixCredential> list = new ArrayList<>(1);
			list.add(map.get(ci));
			credentials.put(ci, list);
		}
	}

	/**
	 * Get a map containing all credential descriptions and attributes we currently have.
	 */
	public static LinkedHashMap<IdemixCredentialIdentifier, Attributes> getAllAttributes() {
		LinkedHashMap<IdemixCredentialIdentifier, Attributes> map = new LinkedHashMap<>();

		for (CredentialIdentifier id : credentials.keySet()) {
			ArrayList<IdemixCredential> list = credentials.get(id);
			int count = list.size();
			for (int i=0; i<count; ++i)
				map.put(new IdemixCredentialIdentifier(id, i, count), list.get(i).getAllAttributes());
		}

		return map;
	}

	/**
	 * Gets the identifier of the {@link IdemixCredential} that has the specified hashCode,
	 * (null if we don't have such a credential).
	 */
	public static IdemixCredentialIdentifier findCredential(int hashCode) {
		for (CredentialIdentifier ci : credentials.keySet()) {
			ArrayList<IdemixCredential> list = credentials.get(ci);
			int count = list.size();
			for (int i=0; i<count; ++i)
				if (list.get(i).hashCode() == hashCode)
					return new IdemixCredentialIdentifier(ci, i, count);
		}

		return null;
	}

	public static int getHashCode(IdemixCredentialIdentifier ici) {
		IdemixCredential cred = get(ici);
		if (cred != null)
			return cred.hashCode();
		else
			throw new IllegalArgumentException("Specified credential not found: " + ici.getUiTitle());
	}

	/**
	 * Get the specified credential.
	 * @return The credential, or null if we don't have it
	 */
	private static IdemixCredential get(IdemixCredentialIdentifier identifier) {
		ArrayList<IdemixCredential> list = credentials.get(identifier.getIdentifier());
		if (list == null || identifier.getIndex() >= list.size())
			return null;

		return list.get(identifier.getIndex());
	}

	/**
	 * Delete the specified credential, saving our changes to storage if neccesary
	 */
	private static void delete(IdemixCredentialIdentifier identifier, boolean shouldSave) {
		if (identifier == null)
			return;

		ArrayList<IdemixCredential> list = credentials.get(identifier.getIdentifier());
		if (list == null)
			return;

		if (list.remove(get(identifier))) {
			CredentialDescription cd = identifier.getIdentifier().getCredentialDescription();
			logs.add(0, new RemoveLogEntry(Calendar.getInstance().getTime(), cd));
			if (shouldSave)
				save();
		}

		if (list.size() == 0)
			credentials.remove(identifier.getIdentifier());
	}

	/**
	 * Delete the specified credential
	 */
	public static void delete(IdemixCredentialIdentifier identifier) {
		delete(identifier, true);
	}

	/**
	 * Delete all credentials.
	 */
	public static void deleteAll() {
		for (CredentialIdentifier id : credentials.keySet()) {
			CredentialDescription cd = descriptionStore.getCredentialDescription(id);
			if (cd == null)
				continue;
			for (int i=0; i<credentials.get(id).size(); ++i)
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
	public static ProofList getProofs(DisclosureChoice disclosureChoice) throws CredentialsException {
		return generateProofListBuilderForVerification(disclosureChoice).build();
	}

	public static ProofListBuilder generateProofListBuilderForVerification(DisclosureChoice disclosureChoice) throws CredentialsException {
		SessionRequest request = disclosureChoice.getRequest();
		ProofListBuilder builder = new ProofListBuilder(request.getContext(), request.getNonce());
		return addProofsToBuilder(disclosureChoice, builder);
	}

	/**
	 * For the selected attribute of each disjunction, add a disclosure proof-commitment to the specified
	 * proof builder.
	 */
	private static ProofListBuilder addProofsToBuilder(DisclosureChoice disclosureChoice, ProofListBuilder builder)
	throws CredentialsException {
		Map<IdemixCredentialIdentifier, List<Integer>> toDisclose = groupAttributesById(disclosureChoice);

		for (IdemixCredentialIdentifier id : toDisclose.keySet()) {
			List<Integer> attrs = toDisclose.get(id);
			IdemixCredential credential = get(id);
			if (credential == null)
				throw new CredentialsException("Credential not found");
			builder.addProofD(credential, attrs);
		}

		logs.addAll(0, generateLogEntries(toDisclose));

		return builder;
	}

	/**
	 * Helper function that, given a list of attributes to be disclosed in the form of a
	 * {@link DisclosureChoice}, groups all selected attributes by credential ID.
	 */
	private static Map<IdemixCredentialIdentifier, List<Integer>> groupAttributesById(
			DisclosureChoice disclosureChoice) throws CredentialsException {
		Map<IdemixCredentialIdentifier, List<Integer>> toDisclose = new HashMap<>();

		// Group the chosen attribute identifiers by their credential ID in the toDisclose map
		for (IdemixAttributeIdentifier attribute : disclosureChoice.getAttributes()) {
			AttributeIdentifier identifier = attribute.getIdentifier();
			IdemixCredentialIdentifier ici = attribute.getIdemixCredentialIdentifier();

			List<Integer> attributes;
			if (toDisclose.get(ici) == null) {
				attributes = new ArrayList<>(5);
				attributes.add(1); // Always disclose metadata
				toDisclose.put(ici, attributes);
			} else
				attributes = toDisclose.get(ici);

			if (identifier.isCredential())
				continue;


			int j = identifier.getCredentialIdentifier().getCredentialDescription()
					.getAttributeNames().indexOf(identifier.getAttributeName());
			if (j == -1) // our CredentialDescription does not contain the asked-for attribute
				throw new CredentialsException("Attribute \"" + identifier.getAttributeName() + "\" not found");

			attributes.add(j + 2);
		}

		return toDisclose;
	}

	/**
	 * Log that we disclosed the specified attributes.
	 */
	private static List<LogEntry> generateLogEntries(Map<IdemixCredentialIdentifier, List<Integer>> toDisclose) {
		List<LogEntry> logs = new ArrayList<>(toDisclose.size());

		for (IdemixCredentialIdentifier id : toDisclose.keySet()) {
			List<Integer> attributes = toDisclose.get(id);

			CredentialDescription cd = descriptionStore.getCredentialDescription(id.getIdentifier());
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

			ArrayList<IdemixCredential> list = credentials.get(credential);
			for (IdemixCredential cred : list) {
				int counter = cred.getKeyCounter();
				if (!keyStore.containsPublicKey(issuer, counter))
					keys.put(issuer, counter);
			}
		}

		if (issuers.size() == 0 && creds.size() == 0 && keys.size() == 0)
			return false;

		StoreManager.download(issuers, creds, keys, handler);
		return true;
	}

	/**
	 * Given an {@link AttributeDisjunction}, return attributes (and their values) that we have and that are
	 * contained in the disjunction. If the disjunction has values for each identifier, then this returns
	 * only those attributes matching those values.
	 */
	public static LinkedHashMap<IdemixAttributeIdentifier, String> getCandidates(AttributeDisjunction disjunction) {
		LinkedHashMap<IdemixAttributeIdentifier, String> map = new LinkedHashMap<>();

		for (AttributeIdentifier attribute : disjunction) {
			CredentialDescription cd = attribute.getCredentialIdentifier().getCredentialDescription();
			if (cd == null)
				continue; // Can't disclose unknown attributes

			for (CredentialIdentifier credId : credentials.keySet()) {
				if (!attribute.getCredentialIdentifier().equals(credId))
					continue;

				ArrayList<IdemixCredential> list = credentials.get(credId);
				int count = list.size();
				for (int i=0; i<list.size(); ++i) {
					IdemixCredential cred = list.get(i);
					IdemixAttributeIdentifier iai = new IdemixAttributeIdentifier(attribute, i, count);
					if (attribute.isCredential()) {
						map.put(iai, iai.getUiTitle());
					}
					else {
						Attributes attrs = cred.getAllAttributes();
						if (attrs.get(attribute.getAttributeName()) == null)
							continue;

						String requiredValue = disjunction.getValues().get(attribute);
						String value = new String(attrs.get(attribute.getAttributeName()));
						if (requiredValue == null || requiredValue.equals(value))
							map.put(iai, value);
					}
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
	 * Returns the secret key from one of our credentials; or, if we do not yet have any credentials,
	 * a new secret key.
	 */
	private static BigInteger getSecretKey() {
		if (secretKey == null) {
			if (credentials == null || credentials.size() == 0)
				// Our secret key will have to fit as an attribute in all credentials,
				// so we should have it be as large as the smallest maximum attribute size.
				secretKey = new BigInteger(new IdemixSystemParameters1024().get_l_m(), new SecureRandom());
			else
				secretKey = credentials.values().iterator().next().get(0).getAttribute(0);
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
	public static IssueCommitmentMessage getIssueCommitments(
			IssuingRequest request, DisclosureChoice disclosureChoice)
			throws InfoException, CredentialsException, KeyException {
		ProofListBuilder builder = CredentialManager.generateProofListBuilderForIssuance(request,
				disclosureChoice);
		return new IssueCommitmentMessage(builder.build(), CredentialManager.getNonce2());
	}


	/**
	 * Compute compute a ProofList Builder for the first step of the distributed issuing protocol:
	 * like {@link #getIssueCommitments(IssuingRequest request, DisclosureChoice disclosureChoice)}
	 * it contains the commitments to the secret key and v_prime and the commitments of the
	 * corresponding proofs of correctness. The cloud server still needs to be used to integrate the
	 * other parts of the secret key.
	 *
	 * @param request The request containing the description of the credentials that will be issued
	 * @return The ProofListBuilder
	 * @throws InfoException If one of the credentials in the request or its attributes is not contained or
	 *                       does not match our DescriptionStore
	 */
	public static ProofListBuilder generateProofListBuilderForIssuance(
			IssuingRequest request, DisclosureChoice disclosureChoice)
			throws InfoException, CredentialsException, KeyException {
		if (!request.credentialsMatchStore())
			throw new InfoException("Request contains mismatching attributes");

		// Initialize issuing state
		credentialBuilders = new ArrayList<>(request.getCredentials().size());
		nonce2 = CredentialBuilder.createReceiverNonce(request.getLargestParameters());

		// Construct the commitment proofs
		ProofListBuilder proofsBuilder = new ProofListBuilder(request.getContext(), request.getNonce());
		proofsBuilder.setSecretKey(getSecretKey());

		for (CredentialRequest cred : request.getCredentials()) {
			CredentialBuilder cb;
			if(isDistributed())
				cb = new DistributedCredentialBuilder(
						cred.getPublicKey(), cred.convertToBigIntegers(), request.getContext(), nonce2);
			else
				cb = new CredentialBuilder(
						cred.getPublicKey(), cred.convertToBigIntegers(), request.getContext(), nonce2);

			proofsBuilder.addCredentialBuilder(cb);
			credentialBuilders.add(cb);
		}

		// Add disclosures, if any
		if (!request.getRequiredAttributes().isEmpty() && disclosureChoice != null)
			addProofsToBuilder(disclosureChoice, proofsBuilder);

		return proofsBuilder;
	}


	/**
	 * Given the issuer's reply to our {@link IssueCommitmentMessage}, compute the new Idemix credentials and save them
	 * @param sigs The message from the issuer containing the CL signatures and proofs of correctness
	 * @throws InfoException If one of the credential types is unknown (this should already have happened in
	 *         {@link #generateProofListBuilderForIssuance(IssuingRequest, DisclosureChoice)})
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

			ArrayList<IdemixCredential> list = credentials.get(cd.getIdentifier());
			if (list == null) {
				list = new ArrayList<>(1);
				credentials.put(cd.getIdentifier(), list);
			}

			// Only grow the list if we are allowed to have more than 1 instance of this credential type
			if (cd.shouldBeSingleton() && list.size() > 0)
				list.set(0, cred);
			else
				list.add(cred);
			logs.add(0, new IssueLogEntry(Calendar.getInstance().getTime(), cd));
		}

		save();
	}

	/**
	 * Get the credential identifiers of the singletons in the specified list of credential requests
	 * (i.e., of the credential types of which we are allowed to posess at most 1 credential).
	*/
	public static ArrayList<CredentialIdentifier> filterSingletons(ArrayList<CredentialRequest> creds) {
		// This would be a oneliner in Java 8...

		ArrayList<CredentialIdentifier> list = new ArrayList<>();

		for (CredentialRequest cred : creds) {
			CredentialIdentifier identifier = cred.getIdentifier();
			if (credentials.containsKey(identifier)
					&& credentials.get(identifier).size() > 0
					&& identifier.getCredentialDescription().shouldBeSingleton())
				list.add(identifier);
		}

		return list;
	}

	public static void addPublicSKs(ProofPCommitmentMap map) {
		for(CredentialBuilder cb : credentialBuilders) {
			if(cb instanceof DistributedCredentialBuilder) {
				((DistributedCredentialBuilder) cb).addPublicSK(map);
			}
		}
	}

	public static void setCloudUsername(String username) {
		cloudUsername = username;
	}

	public static String getCloudUsername() {
		return cloudUsername;
	}

	public static void setCloudServer(String server) {
		Log.i(TAG, "Setting cloud server to :" + server);
		cloudServer = server;
	}

	public static String getCloudServer() {
		Log.i(TAG, "Returning server:" + cloudServer);
		return cloudServer;
	}

	public static void setCloudToken(String token) {
		cloudToken = token;
	}

	public static String getCloudToken() {
		return cloudToken;
	}

	public static void setDistributed(boolean distributed) {
		isDistributed = distributed;
	}

	public static boolean isDistributed() {
		return isDistributed;
	}

	public static BigInteger getNonce2() {
		return nonce2;
	}
}
