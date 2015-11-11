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
import net.sf.scuba.smartcards.CardServiceException;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.VerificationStartListener;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.idemix.IdemixService;

import java.util.List;

/**
 * Contains static methods for managing an IRMACard.
 */
public class CardManager {
	private static SharedPreferences settings;
	private static IRMACard card;
	private static final String TAG = "CardManager";
	private static final String CARD_STORAGE = "card";

	public static void init(SharedPreferences s) {
		settings = s;
	}

	public static void logCard(IdemixService is) {
		Log.d(TAG, "Current card contents");
		// Retrieve list of credentials from the card
		IdemixCredentials ic = new IdemixCredentials(is);
		List<CredentialDescription> credentialDescriptions;

		try {
			ic.connect();
			is.sendCardPin("000000".getBytes());
			credentialDescriptions = ic.getCredentials();
			for(CredentialDescription cd : credentialDescriptions) {
				Log.d(TAG,cd.getName());
			}
		} catch (CardServiceException|InfoException|CredentialsException e) {
			e.printStackTrace();
		}
	}

	public static void storeCard() {
		Log.d(TAG, "Storing card");

		settings.edit()
			.putString(CARD_STORAGE, new Gson().toJson(card))
			.apply();
	}

	public static IRMACard loadCard() {
		return loadCard(null);
	}

	public static IRMACard loadCard(VerificationStartListener listener) {
		String card_json = settings.getString(CARD_STORAGE, "");

		Gson gson = new Gson();

		if (!card_json.equals("")) {
			try {
				card = gson.fromJson(card_json, IRMACard.class);
			} catch (Exception e) {
				card = new IRMACard();
			}
		}

		if (card == null)
			card = new IRMACard();

		if (listener != null) {
			card.addVerificationListener(listener);
		}

		return card;
	}
}
