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

package org.irmacard.cardemu.store;

import android.content.Context;
import android.os.AsyncTask;

import org.irmacard.api.common.SessionRequest;
import org.irmacard.credentials.idemix.info.IdemixKeyStore;
import org.irmacard.credentials.idemix.info.IdemixKeyStoreSerializer;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.CredentialIdentifier;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.DescriptionStoreSerializer;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.info.IssuerDescription;
import org.irmacard.credentials.info.IssuerIdentifier;
import org.irmacard.credentials.info.SchemeManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Manager for {@link DescriptionStore} and {@link IdemixKeyStore}: handles downloading new items
 * asynchroniously, and serializing them to storage.
 */
@SuppressWarnings("unused")
public class StoreManager implements DescriptionStoreSerializer, IdemixKeyStoreSerializer {
	/**
	 * Callbacks for when downloading new store items.
	 */
	public interface DownloadHandler {
		void onSuccess();
		void onError(Exception e);
	}

	private Context context;

	public StoreManager(Context context) {
		this.context = context;
	}

	@Override
	public void saveCredentialDescription(CredentialDescription cd, String xml) {
		File issuesDir = new File(getIssuerPath(cd.getIdentifier().getIssuerIdentifier()), "Issues/" + cd.getCredentialID());
		if (!issuesDir.mkdirs() && !issuesDir.isDirectory())
			throw new RuntimeException("Could not create issuing path");

		try {
			FileOutputStream fos = new FileOutputStream(new File(issuesDir, "description.xml"));
			fos.write(xml.getBytes());
			fos.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void saveIssuerDescription(IssuerDescription issuer, String xml, InputStream logo) {
		File issuerDir = getIssuerPath(issuer.getIdentifier());
		writeString(issuerDir, "description.xml", xml);
		writeStream(issuerDir, "logo.png", logo);
	}

	@Override
	public void saveSchemeManager(SchemeManager schemeManager) {
		File store = context.getDir("store", Context.MODE_PRIVATE);
		File path = new File(store, schemeManager.getName());
		if (!path.mkdirs() && !path.isDirectory())
			throw new RuntimeException("Could not create scheme manager path");

		writeString(path, "description.xml", schemeManager.getXml());
	}

	/**
	 * Returns true if the specified scheme manager is stored in internal storage, as opposed to
	 * the app assets (from which it cannot programmatically be deleted).
	 */
	public boolean canRemoveSchemeManager(String manager) {
		return new File(context.getDir("store", Context.MODE_PRIVATE), manager+"/description.xml").exists();
	}

	public void removeSchemeManager(String manager) {
		DescriptionStore.getInstance().removeSchemeManager(manager);

		deleteRecursively(new File(context.getDir("store", Context.MODE_PRIVATE), manager));
	}

	private static void deleteRecursively(File fileOrDirectory) {
		if (fileOrDirectory.isDirectory())
			for (File child : fileOrDirectory.listFiles())
				deleteRecursively(child);

		boolean b = fileOrDirectory.delete();
	}

	@Override
	public void saveIdemixKey(IssuerDescription issuer, String key, int counter) {
		File issuerDir = getIssuerPath(issuer.getIdentifier());
		File path = new File(issuerDir, "PublicKeys");
		if (!path.mkdirs() && !path.isDirectory())
			throw new RuntimeException("Could not create public key path");

		writeString(issuerDir, String.format(IdemixKeyStore.PUBLIC_KEY_FILE, counter), key);
	}

	private void writeString(File path, String filename, String contents) {
		try {
			FileOutputStream fos = new FileOutputStream(new File(path, filename));
			fos.write(contents.getBytes());
			fos.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void writeStream(File path, String filename, InputStream stream) {
		try {
			FileOutputStream fos = new FileOutputStream(new File(path, filename));

			byte[] buffer = new byte[1024];
			int bytesRead;
			while ((bytesRead = stream.read(buffer)) != -1) {
				fos.write(buffer, 0, bytesRead);
			}

			fos.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private File getIssuerPath(IssuerIdentifier issuer) {
		File store = context.getDir("store", Context.MODE_PRIVATE);
		File verifier = new File(store, issuer.getPath(false));

		if (!verifier.mkdirs() && !verifier.isDirectory())
			throw new RuntimeException("Could not create issuer path for '" + issuer + "' in storage");

		return verifier;
	}


	private static void downloadSync(Iterable<IssuerIdentifier> issuers,
	                                 Iterable<CredentialIdentifier> credentials,
	                                 Map<IssuerIdentifier, Integer> keys)
			throws InfoException, IOException {
		// This also downloads the issuer description
		if (issuers != null)
			for (IssuerIdentifier issuer: issuers)
				if (DescriptionStore.getInstance().getIssuerDescription(issuer) == null)
					DescriptionStore.getInstance().downloadIssuerDescription(issuer);

		if (keys != null) {
			for (IssuerIdentifier issuer : keys.keySet()) {
				int counter = keys.get(issuer);
				if (!IdemixKeyStore.getInstance().containsPublicKey(issuer, counter))
					IdemixKeyStore.getInstance().downloadPublicKey(issuer, counter);
			}
		}

		if (credentials != null)
			for (CredentialIdentifier credential : credentials)
				if (DescriptionStore.getInstance().getCredentialDescription(credential) == null)
					DescriptionStore.getInstance().downloadCredentialDescription(credential);
	}

	/**
	 * Asynchroniously attempt to download issuer descriptions, public keys and credential descriptions
	 * from the scheme managers.
	 * @param issuers Issuers to download
	 * @param credentials Credentials to download
	 * @param handler Handler to communicate results to
	 */
	public static void download(final Iterable<IssuerIdentifier> issuers,
	                            final Iterable<CredentialIdentifier> credentials,
	                            final Map<IssuerIdentifier, Integer> keys,
	                            final DownloadHandler handler) {
		download(null, issuers, credentials, keys, handler);
	}

	public static void downloadSchemeManager(final String url, final DownloadHandler handler) {
		download(url, null, null, null, handler);
	}

	private static void download(final String schemeManagerUrl,
	                             final Iterable<IssuerIdentifier> issuers,
	                             final Iterable<CredentialIdentifier> credentials,
	                             final Map<IssuerIdentifier, Integer> keys,
	                             final DownloadHandler handler) {
		new AsyncTask<Void,Void,Exception>() {
			@Override protected Exception doInBackground(Void... params) {
				try {
					if (schemeManagerUrl != null)
						DescriptionStore.getInstance().downloadSchemeManager(schemeManagerUrl);
					downloadSync(issuers, credentials, keys);
					return null;
				} catch (Exception e) {
					return e;
				}
			}

			@Override protected void onPostExecute(Exception e) {
				if (handler == null)
					return;

				if (e == null)
					handler.onSuccess();
				else
					handler.onError(e);
			}
		}.execute();
	}

	/**
	 * Asynchroniously attempt to download issuer descriptions, public keys and credential descriptions
	 * from the scheme managers.
	 * @param request A session request containing unknown issuers or credentials
	 * @param handler Handler to communicate results to
	 */
	public static void download(final SessionRequest request, final DownloadHandler handler) {
		download(null, request.getIssuerList(), request.getCredentialList(), request.getPublicKeyList(), handler);
	}
}
