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
import net.sf.scuba.smartcards.CardServiceException;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.IdemixCredential;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.proofs.ProofCollection;
import org.irmacard.credentials.idemix.proofs.ProofCollectionBuilder;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.IRMAIdemixCredential;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.util.log.LogEntry;
import org.irmacard.credentials.util.log.RemoveLogEntry;
import org.irmacard.idemix.IdemixService;
import org.irmacard.verification.common.AttributeDisjunction;
import org.irmacard.verification.common.AttributeIdentifier;
import org.irmacard.verification.common.DisclosureProofRequest;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.*;

/**
 * Handles issuing, disclosing, and deletion of credentials; keeps track of log entries; and handles (de)
 * serialization of credentials and log entries from/to storage.
 */
@SuppressWarnings("unused")
public class CredentialManager {
	private static HashMap<Short, IRMAIdemixCredential> credentials = new HashMap<>();
	private static List<LogEntry> logs = new LinkedList<>();

	// Type tokens for Gson (de)serialization
	private static Type credentialsType = new TypeToken<HashMap<Short, IRMAIdemixCredential>>() {}.getType();
	private static Type logsType = new TypeToken<List<LogEntry>>() {}.getType();

	private static SharedPreferences settings;
	private static final String TAG = "CredentialManager";
	private static final String CREDENTIAL_STORAGE = "credentials";
	private static final String LOG_STORAGE = "logs";

	public static void init(SharedPreferences s) {
		settings = s;
	}

	/**
	 * Extract credentials and logs from an IRMACard instance retrieved from storage.
	 */
	@SuppressWarnings("unchecked")
	public static void loadFromCard() {
		Log.i(TAG, "Loading credentials from card");

		IRMACard card = CardManager.loadCard();

		try {
			Field f = card.getClass().getDeclaredField("credentials");
			f.setAccessible(true);
			credentials = (HashMap<Short, IRMAIdemixCredential>) f.get(card);
		} catch (NoSuchFieldException|IllegalAccessException|ClassCastException e) {
			e.printStackTrace();
		}

		try {
			IdemixService is = new IdemixService(new SmartCardEmulatorService(card));
			is.open();
			is.sendCardPin("000000".getBytes());
			logs = new IdemixCredentials(is).getLog();
		} catch (CardServiceException|InfoException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Saves the credentials and logs to storage.
	 */
	public static void save() {
		Log.i(TAG, "Saving credentials");

		Gson gson = new Gson();
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

		Gson gson = new Gson();
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
			logs.add(new RemoveLogEntry(Calendar.getInstance().getTime(), cd));
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
		for (short id : credentials.keySet())
			delete(id, false);

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
	public static ProofCollection getProofs(DisclosureProofRequest request) throws CredentialsException {
		if (!isApproved(request))
			throw new CredentialsException("Select an attribute in each disjunction first");

		List<AttributeDisjunction> content = request.getContent();
		ProofCollectionBuilder builder = new ProofCollectionBuilder(request.getContext(), request.getNonce());
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

			int j = cd.getAttributeNames().indexOf(identifier.getAttributeName());
			if (j == -1) // our CredentialDescription does not contain the asked-for attribute
				throw new CredentialsException("Attribute \"" + identifier.getAttributeName() + "\" not found");

			attributes.add(j + 2);
		}

		for (short id : toDisclose.keySet()) {
			List<Integer> attributes = toDisclose.get(id);
			IdemixCredential credential = credentials.get(id).getCredential();
			builder.addProofD(credential, attributes);
		}

		return builder.build();
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
	 * Returns true if the request has been approved by the user - that is, if each disjunction of the request has a
	 * selected attribute
	 */
	public static boolean isApproved(DisclosureProofRequest request) {
		for (AttributeDisjunction disjunction : request.getContent())
			if (disjunction.getSelected() == null)
				return false;

		return true;
	}

	/**
	 * Given an {@link AttributeDisjunction}, return attributes (and their values) that we have and that are
	 * contained in the disjunction.
	 */
	public static LinkedHashMap<AttributeIdentifier, String> getCandidates(AttributeDisjunction disjunction) {
		LinkedHashMap<AttributeIdentifier, String> map = new LinkedHashMap<>();

		for (AttributeIdentifier attribute : disjunction) {
			Attributes foundAttrs = null;
			try {
				foundAttrs = getAttributes(attribute.getIssuerName(), attribute.getCredentialName());
			} catch (InfoException e) {
				e.printStackTrace();
			}

			if (foundAttrs != null && foundAttrs.get(attribute.getAttributeName()) != null) {
				String value = new String(foundAttrs.get(attribute.getAttributeName()));
				map.put(attribute, value);
			}
		}

		return map;
	}
}
