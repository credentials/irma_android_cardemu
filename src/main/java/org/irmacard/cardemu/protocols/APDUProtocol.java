package org.irmacard.cardemu.protocols;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import net.sf.scuba.smartcards.*;
import org.acra.ACRA;
import org.apache.http.Header;
import org.apache.http.entity.StringEntity;
import org.irmacard.api.common.AttributeDisjunctionList;
import org.irmacard.api.common.IssuingRequest;
import org.irmacard.cardemu.*;
import org.irmacard.cardemu.messages.*;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.credentials.idemix.smartcard.VerificationStartListener;
import org.irmacard.credentials.info.AttributeDescription;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.idemix.IdemixService;
import org.irmacard.idemix.util.VerificationSetupData;
import org.irmacard.api.common.AttributeDisjunction;
import org.irmacard.api.common.DisclosureProofRequest;

import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

public class APDUProtocol extends Protocol {
	private static String TAG = "CardEmuAPDU";
	private static final int MESSAGE_STARTGET = 1;
	private static final int MAX_RETRIES = 3;

	private long issuingStartTime;

	private MainActivity mainActivity;

	// Card state
	private IRMACard card;
	private IdemixService is = null;
	private VerificationStartListener verificationListener;
	private List<VerificationSetupData> verificationList = new ArrayList<>();

	// Networking state
	private ReaderMessage lastReaderMessage = null;
	private ReaderMessage disclosureproof = null;
	private String currentReaderURL = "";
	private String currentWriteURL = null;
	private int retry_counter = 0;
	private int currentHandlers = 0;

	public APDUProtocol() {
		verificationListener = new VerificationStartListener() {
			@Override
			public void verificationStarting(VerificationSetupData data) {
				verificationList.add(data);
			}
		};
	}

	public VerificationStartListener getListener() {
		return verificationListener;
	}

	/**
	 * Have the instance act on the specified card.
	 * @param card The new IRMACard instance
	 */
	public void setCard(IRMACard card) {
		this.card = card;
		is = new IdemixService(new SmartCardEmulatorService(card));
	}

	private void askForPIN() {
		mainActivity.setState(MainActivity.STATE_COMMUNICATING);
		new ProcessReaderMessage().execute(new ReaderInput(lastReaderMessage, "0000"));
	}

	// Helper function to build a ResponseAPDU from a status message. Copied from IRMACard.java
	private static ResponseAPDU sw(short status) {
		byte msbyte = (byte) ((byte) (status >> 8) & ((byte) 0xff));
		byte lsbyte = (byte) (((byte) status) & ((byte) 0xff));
		return new ResponseAPDU(new byte[]{msbyte, lsbyte});
	}

	// Networking code

	/**
	 * Connect to a given url.
	 * @param url The url to connect to
	 */
	@Override
	public void connect(String url) {
		IRMACard card = CredentialManager.saveCard();
		card.addVerificationListener(getListener());
		setCard(card);

		Log.i(TAG, "Start channel listening: " + url);

		try {
			mainActivity = (MainActivity) activity;
		} catch (ClassCastException e) { // Add a message to the exception
			throw new ClassCastException("APDUProtocol can only be used by MainActivity");
		}

		currentReaderURL = url;
		Message msg = new Message();
		msg.what = MESSAGE_STARTGET;
		mainActivity.setState(MainActivity.STATE_CONNECTING_TO_SERVER);
		messageHandler.sendMessage(msg);
	}

	/**
	 * Aborts the connection to the server: sends ISO7816.SW_COMMAND_NOT_ALLOWED.
	 */
	@Override
	public void cancelSession() {
		super.cancelSession();

		ResponseAPDU response = sw(ISO7816.SW_COMMAND_NOT_ALLOWED); // TODO is this the appropriate response?
		ProtocolResponses responses = new ProtocolResponses();

		responses.put("startprove", new ProtocolResponse("startprove", response));
		ReaderMessage rm = new ReaderMessage(
				ReaderMessage.TYPE_RESPONSE,
				ReaderMessage.NAME_COMMAND_TRANSMIT,
				disclosureproof.id,
				new ResponseArguments(responses));
		postMessage(rm);
	}

	@Override
	public void disclose(final DisclosureProofRequest request) {
		postMessage(disclosureproof);
	}

	@Override // Never used, everything happens via the handler below
	protected void finishIssuance(IssuingRequest request) {}

	Handler messageHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			if (msg.what != MESSAGE_STARTGET)
				return;

			Log.i(TAG, "MESSAGE_STARTGET received in messageHandler!");
			AsyncHttpClient client = new AsyncHttpClient();
			client.setTimeout(50000); // timeout of 50 seconds
			client.setUserAgent("org.irmacard.cardemu");

			client.get(mainActivity, currentReaderURL, new AsyncHttpResponseHandler() {
				@Override
				public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
					String responseData = new String(responseBody);
					if (!responseData.equals("")) {
						//Toast.makeText(MainActivity.this, responseData, Toast.LENGTH_SHORT).show();
						handleChannelData(responseData);
					}

					// Do a new request, but only if no new requests have started
					// in the mean time
					if (currentHandlers <= 1) {
						Message newMsg = new Message();
						newMsg.what = MESSAGE_STARTGET;
						if (!(mainActivity.getState() == MainActivity.STATE_IDLE))
							messageHandler.sendMessageDelayed(newMsg, 200);
					}
				}

				@Override
				//public void onFailure(Throwable arg0, String arg1) {
				public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {
					if (mainActivity.getState() != MainActivity.STATE_CONNECTING_TO_SERVER) {
						retry_counter = 0;
						return;
					}

					retry_counter += 1;

					// We should try again, but only if no new requests have started
					// and we should wait a bit longer
					if (currentHandlers <= 1 && retry_counter < MAX_RETRIES) {
						Message newMsg = new Message();
						mainActivity.setFeedback("Trying to reach server again...", "none");
						newMsg.what = MESSAGE_STARTGET;
						messageHandler.sendMessageDelayed(newMsg, 5000);
					} else {
						retry_counter = 0;
						mainActivity.setFeedback("Failed to connect to server", "warning");
						mainActivity.setState(MainActivity.STATE_IDLE);
					}

				}

				public void onStart() {
					currentHandlers += 1;
				}

				public void onFinish() {
					currentHandlers -= 1;
				}
			});
		}
	};

	private void handleChannelData(String data) {
		Gson gson = new GsonBuilder().
				registerTypeAdapter(ProtocolCommand.class, new ProtocolCommandDeserializer()).
				registerTypeAdapter(ReaderMessage.class, new ReaderMessageDeserializer()).
				create();


		if (mainActivity.getState() == MainActivity.STATE_CONNECTING_TO_SERVER) {
			// this is the message that containts the url to write to
			JsonParser p = new JsonParser();
			JsonElement write_url = p.parse(data).getAsJsonObject().get("write_url");

			// The server either returns a JSON object containing just the write_url,
			// or a ReaderMessage which we deal with below. So if we don't find the
			// write_url, we let the rest of this method deal with it.
			if (write_url != null) {
				currentWriteURL = write_url.getAsString();
				mainActivity.setState(MainActivity.STATE_READY); // This ensures we will be in this block only once
				postMessage(
						new ReaderMessage(ReaderMessage.TYPE_EVENT, ReaderMessage.NAME_EVENT_CARDREADERFOUND, null,
								new EventArguments().withEntry("type", "phone")));
				postMessage(new ReaderMessage(ReaderMessage.TYPE_EVENT, ReaderMessage.NAME_EVENT_CARDFOUND, null));

				return;
			}
		}

		// If we're here, we're either connecting or received a timeout, and in both
		// cases we should have received a ReaderMessage. We parse the data the other
		// end sent to us.
		ReaderMessage rm;
		try {
			Log.i(TAG, "Length (real): " + data);
			JsonReader reader = new JsonReader(new StringReader(data));
			reader.setLenient(true);
			rm = gson.fromJson(reader, ReaderMessage.class);
		} catch (Exception e) {
			ACRA.getErrorReporter().handleException(e);
			return;
		}

		lastReaderMessage = rm;

		// If the other end sent a command, execute it
		if (rm.type.equals(ReaderMessage.TYPE_COMMAND)) {
			Log.i(TAG, "Got command message");

			if (mainActivity.getState() != MainActivity.STATE_READY) {
				// FIXME: Only when ready can we handle commands
				throw new RuntimeException(
						"Illegal command from server, no card currently connected");
			}

			if (rm.name.equals(ReaderMessage.NAME_COMMAND_AUTHPIN)) {
				askForPIN();
			} else {
				mainActivity.setState(MainActivity.STATE_COMMUNICATING);
				new ProcessReaderMessage().execute(new ReaderInput(rm));
			}
		}

		// If the other end sent an event, deal with it
		if (rm.type.equals(ReaderMessage.TYPE_EVENT)) {
			EventArguments ea = (EventArguments) rm.arguments;
			if (rm.name.equals(ReaderMessage.NAME_EVENT_STATUSUPDATE)) {
				String state = ea.data.get("state");
				String feedback = ea.data.get("feedback");
				if (state != null) {
					mainActivity.setFeedback(feedback, state);
				}
			} else if (rm.name.equals(ReaderMessage.NAME_EVENT_TIMEOUT)) {
				mainActivity.setState(MainActivity.STATE_IDLE);
			} else if (rm.name.equals(ReaderMessage.NAME_EVENT_DONE)) {
				CardManager.storeCard();
				CredentialManager.loadFromCard();
				CredentialManager.save();
				mainActivity.setState(MainActivity.STATE_IDLE);
			}
		}
	}

	public void postMessage(ReaderMessage rm) {
		if (currentWriteURL != null) {
			Gson gson = new GsonBuilder().
					registerTypeAdapter(ProtocolResponse.class, new ProtocolResponseSerializer()).
					create();
			String data = gson.toJson(rm);
			AsyncHttpClient client = new AsyncHttpClient();
			try {
				client.post(activity, currentWriteURL, new StringEntity(data), "application/json", new AsyncHttpResponseHandler() {
					@Override
					public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
						// TODO: Should there be some simple user feedback?
						//super.onSuccess(statusCode, headers, responseBody);
					}

					@Override
					public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {
						// TODO: Give proper feedback to the user that we are unable to send stuff
						//super.onFailure(statusCode, headers, responseBody, error);
					}
				});
			} catch (UnsupportedEncodingException e) {
				// Ignore, shouldn't happen ;)
				ACRA.getErrorReporter().handleException(e);
			}
		}
	}

	private class ProcessReaderMessage extends AsyncTask<ReaderInput, Void, ReaderMessage> {
		@Override
		protected ReaderMessage doInBackground(ReaderInput... params) {
			ReaderInput input = params[0];
			ReaderMessage rm = input.message;

			// It seems sometimes tag is null afterall
			if (card == null) {
				Log.e("ReaderMessage", "card is null, this should not happen!");
				return new ReaderMessage(ReaderMessage.TYPE_EVENT, ReaderMessage.NAME_EVENT_CARDLOST, null);
			}

			// TODO: The current version of the cardemu shouldn't depend on idemix terminal, but for now it is convenient.
			try {
				if (!is.isOpen()) {
					// TODO: this is dangerous, this call to IdemixService already does a "select applet"
					is.open();
				}
				if (rm.name.equals(ReaderMessage.NAME_COMMAND_AUTHPIN)) {
					if (input.pincode != null) {
						// TODO: this should be done properly, maybe without using IdemixService?
						is.sendCredentialPin(input.pincode.getBytes());
						return new ReaderMessage("response", rm.name, rm.id, new PinResultArguments(-1));
					}
				} else if (rm.name.equals(ReaderMessage.NAME_COMMAND_TRANSMIT)) {
					TransmitCommandSetArguments arg = (TransmitCommandSetArguments) rm.arguments;

					boolean verifying = false;
					boolean issuingOne = false;
					boolean issuingTwo = false;
					long start, stop;

					if (arg.commands.get(0).getKey().equals("startprove")) {
						verifying = true;
					}
					if (arg.commands.get(0).getKey().equals("start_issuance")) {
						issuingOne = true;
						issuingStartTime = System.currentTimeMillis();
					}
					if (arg.commands.get(0).getKey().equals("signature_A")) {
						issuingTwo = true;
					}

					start = System.currentTimeMillis();

					ProtocolResponses responses = new ProtocolResponses();
					for (ProtocolCommand c : arg.commands) {
						ResponseAPDU apdu_response = is.transmit(c.getAPDU());
						responses.put(c.getKey(),
								new ProtocolResponse(c.getKey(), apdu_response));
						if (apdu_response.getSW() != 0x9000) {
							break;
						}
					}

					stop = System.currentTimeMillis();

					if (verifying) {
						MetricsReporter.getInstance().reportAggregrateMeasurement("verification_time", stop - start);
					}
					if (issuingOne) {
						MetricsReporter.getInstance().reportMeasurement("issue_one_time", stop - start, false);
					}
					if (issuingTwo) {
						MetricsReporter.getInstance().reportMeasurement("issue_two_time", stop - start, false);
						MetricsReporter.getInstance().reportMeasurement("issue_total_time",
								stop - issuingStartTime, true);
					}

					return new ReaderMessage(ReaderMessage.TYPE_RESPONSE, rm.name, rm.id, new ResponseArguments(responses));
				}

			} catch (CardServiceException e) {
				// FIXME: IRMA specific handling of failed command, this is too generic.
				if (e.getMessage().contains("Command failed:") && e.getSW() == 0x6982) {
					return null;
				}
				e.printStackTrace();
				// TODO: maybe also include the information about the exception in the event?
				return new ReaderMessage(ReaderMessage.TYPE_EVENT, ReaderMessage.NAME_EVENT_CARDLOST, null);
			} catch (IllegalStateException e) {
				// This sometimes props up when applications comes out of suspend for now we just ignore this.
				Log.i("READER", "IllegalStateException ignored");
				return null;
			}
			return null;
		}

		@Override
		protected void onPostExecute(ReaderMessage result) {
			if (result == null)
				return;

			// Update state
			if (result.type.equals(ReaderMessage.TYPE_EVENT) &&
					result.name.equals(ReaderMessage.NAME_EVENT_CARDLOST)) {
				// Connection to the card is lost
				mainActivity.setState(MainActivity.STATE_CONNECTED);
			} else {
				if (mainActivity.getState() == MainActivity.STATE_COMMUNICATING) {
					mainActivity.setState(MainActivity.STATE_READY);
				}
			}

			if (result.name.equals(ReaderMessage.NAME_COMMAND_AUTHPIN)) {
				// Handle pin separately, abort if pin incorrect and more tries
				// left
				PinResultArguments args = (PinResultArguments) result.arguments;
				if (!args.success) {
					if (args.tries > 0) {
						// Still some tries left, asking again
						mainActivity.setState(MainActivity.STATE_WAITING_FOR_PIN);
						askForPIN();
						return; // do not send a response yet.
					}
				}
			}

			if (verificationList.size() == 0) {
				// Post result to browser
				postMessage(result);
			} else {
				// We're doing disclosure proofs: ask for permission first
				disclosureproof = result;
				handler.askForVerificationPermission(convertToRequest(verificationList));
				verificationList.clear();
			}
		}
	}

	public DisclosureProofRequest convertToRequest(List<VerificationSetupData> list) {
		AttributeDisjunctionList disjunctions = new AttributeDisjunctionList();

		try {
			for (VerificationSetupData entry : list) {
				CredentialDescription desc = DescriptionStore.getInstance().getCredentialDescription(entry.getID());
				List<AttributeDescription> attrDescs = desc.getAttributes();
				String credential = desc.getIssuerID() + "." + desc.getCredentialID();
				List<Short> attrIds = disclosureMaskToList(entry.getDisclosureMask());

				if (attrIds.size() == 0) {
					// We're only proving possession of the credential (or rather, only disclosing metadata attribute)
					disjunctions.add(new AttributeDisjunction(desc.getShortName(), credential));
					continue;
				}

				for (short i = 0; i < desc.getAttributeNames().size(); ++i) {
					if (attrIds.contains(i)) {
						String attrname = attrDescs.get(i).getName();
						disjunctions.add(new AttributeDisjunction(attrname, credential + "." + attrname));
					}
				}
			}
		} catch (InfoException e) {
			e.printStackTrace();
			return null;
		}

		return new DisclosureProofRequest(null, null, disjunctions);
	}

	/**
	 * Converts an attribute disclosure byte mask (from VerificationSetupData)
	 * into a list of shorts containing the IDs of the disclosed attributes.
	 * In this byte mask, the rightmost bit (master secret) is always 0 and the
	 * next bit (metadata) is always 1. These are not taken into account.
	 * E.g. 00101010 will be converted into {1, 3} (note that the IDs are zero-based).
	 *
	 * @param mask The byte mask.
	 * @return The list of disclosed attribute IDs.
	 */
	private static List<Short> disclosureMaskToList(short mask) {
		List<Short> list = new ArrayList<>();

		// We start at 2 to skip the master secret and metadata attributes,
		// which are never and always disclosed, respectively.
		for (short i = 2; i < 14; i++) { // Currently there can be only six attributes, but that may change
			if ((mask & (1 << i)) != 0) {
				list.add((short) (i - 2));
			}
		}

		return list;
	}

	private class ReaderInput {
		public ReaderMessage message;
		public String pincode = null;

		public ReaderInput(ReaderMessage message) {
			this.message = message;
		}

		public ReaderInput(ReaderMessage message, String pincode) {
			this.message = message;
			this.pincode = pincode;
		}
	}
}
