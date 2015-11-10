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
