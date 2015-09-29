package org.irmacard.cardemu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.util.*;

import android.view.*;
import android.widget.*;
import com.google.gson.JsonElement;
import net.sf.scuba.smartcards.*;
import org.apache.http.Header;
import org.apache.http.entity.StringEntity;
import org.irmacard.android.util.credentials.AndroidWalker;
import org.irmacard.android.util.credentials.CredentialPackage;
import org.irmacard.android.util.disclosuredialog.DisclosureDialogFragment;
import org.irmacard.android.util.disclosuredialog.DisclosureDialogFragment.DisclosureDialogListener;
import org.irmacard.android.util.pindialog.EnterPINDialogFragment.PINDialogListener;
import org.irmacard.android.util.credentialdetails.*;
import org.irmacard.android.util.cardlog.*;
import org.irmacard.cardemu.messages.EventArguments;
import org.irmacard.cardemu.messages.PinResultArguments;
import org.irmacard.cardemu.messages.ReaderMessage;
import org.irmacard.cardemu.messages.ReaderMessageDeserializer;
import org.irmacard.cardemu.messages.ResponseArguments;
import org.irmacard.cardemu.messages.TransmitCommandSetArguments;
import org.irmacard.cardemu.updates.AppUpdater;
import org.irmacard.cardemu.updates.AppVersionInfo;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.info.IdemixKeyStore;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.credentials.idemix.smartcard.VerificationStartListener;
import org.irmacard.credentials.info.AttributeDescription;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.util.log.LogEntry;
import org.irmacard.idemix.IdemixService;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import org.irmacard.idemix.util.VerificationSetupData;


public class MainActivity extends Activity implements PINDialogListener, DisclosureDialogListener {
	public static final int PASSPORT_REQUEST = 100;
	private static final int DETAIL_REQUEST = 101;

	private String TAG = "CardEmuMainActivity";
	private NfcAdapter nfcA;
	private PendingIntent mPendingIntent;
	private IntentFilter[] mFilters;
	private String[][] mTechLists;

	// PIN handling
	private int tries = -1;

	// Previewed list of credentials
	ExpandableCredentialsAdapter credentialListAdapter;

	// List to temporarily store verification information passed to us by IRMACard
	List<VerificationSetupData> verificationList = new ArrayList<>();

	// State variables
	private IsoDep lastTag = null;
	private IRMACard card = null;
	private IdemixService is = null;

	private int activityState = STATE_IDLE;

	// New states
	private static final int STATE_IDLE = 1;
	private static final int STATE_CONNECTING_TO_SERVER = 2;
	private static final int STATE_CONNECTED = 3;
	private static final int STATE_READY = 4;
	private static final int STATE_COMMUNICATING = 5;
	private static final int STATE_WAITING_FOR_PIN = 6;



	// Timer for testing card connectivity
	Timer timer;
	private static final int CARD_POLL_DELAY = 2000;

	// Timer for briefly displaying feedback messages on CardEmu
	CountDownTimer cdt;
	private static final int FEEDBACK_SHOW_DELAY = 10000;
	private boolean showingFeedback = false;

	// Counter for number of connection tries
	private static final int MAX_RETRIES = 3;
	private int retry_counter = 0;

	private final String CARD_STORAGE = "card";
	private final String SETTINGS = "cardemu";

	private ReaderMessage disclosureproof;

	private static final String updateServer = "https://credentials.github.io/appupdates";
	AppUpdater updater;

	private long issuingStartTime;

	private void loadCard() {
		SharedPreferences settings = getSharedPreferences(SETTINGS, 0);
		String card_json = settings.getString(CARD_STORAGE, "");

		Gson gson = new Gson();

		if (!card_json.equals("")) {
			try {
				card = gson.fromJson(card_json, IRMACard.class);
			} catch (Exception e) {
				card = getDefaultCard();
			}
		}

		if (card == null)
			card = getDefaultCard();

		card.addVerificationListener(new VerificationStartListener() {
			@Override
			public void verificationStarting(VerificationSetupData data) {
				verificationList.add(data);
			}
		});

		is = new IdemixService(new SmartCardEmulatorService(card));
	}

	private IRMACard getDefaultCard() {
		try {
			Gson gson = new Gson();
			BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open("card.json")));
			return gson.fromJson(reader, IRMACard.class);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	private void storeCard() {
		Log.d(TAG, "Storing card");
		SharedPreferences settings = getSharedPreferences(SETTINGS, 0);
		SharedPreferences.Editor editor = settings.edit();
		Gson gson = new Gson();
		editor.putString(CARD_STORAGE, gson.toJson(card));
		editor.commit();
	}

	private void resetCard() {
		Log.d(TAG, "Resetting card");

		SharedPreferences settings = getSharedPreferences(SETTINGS, 0);
		SharedPreferences.Editor editor = settings.edit();

		// FIXME: this is a bit of a hack, ideally we'd directly store the file as as a property
		IRMACard tmp_card = getDefaultCard();

		Gson gson = new Gson();
		editor.putString(CARD_STORAGE, gson.toJson(tmp_card));
		editor.commit();

		loadCard();
		updateCardCredentials();
	}

	private void setState(int state) {
		Log.i(TAG, "Set state: " + state);
		activityState = state;

		switch (activityState) {
			case STATE_IDLE:
				lastTag = null;
				updateCardCredentials();
				break;
			default:
				break;
		}

		setUIForState();
	}

	private void setUIForState() {
		int imageResource = 0;
		int statusTextResource = 0;
		int feedbackTextResource = 0;

		switch (activityState) {
			case STATE_IDLE:
				imageResource = R.drawable.irma_icon_place_card_520px;
				statusTextResource = R.string.status_idle;
				break;
			case STATE_CONNECTING_TO_SERVER:
				imageResource = R.drawable.irma_icon_place_card_520px;
				statusTextResource = R.string.status_connecting;
				break;
			case STATE_CONNECTED:
				imageResource = R.drawable.irma_icon_place_card_520px;
				statusTextResource = R.string.status_connected;
				feedbackTextResource = R.string.feedback_waiting_for_card;
				break;
			case STATE_READY:
				imageResource = R.drawable.irma_icon_card_found_520px;
				statusTextResource = R.string.status_ready;
				break;
			case STATE_COMMUNICATING:
				imageResource = R.drawable.irma_icon_card_found_520px;
				statusTextResource = R.string.status_communicating;
				break;
			case STATE_WAITING_FOR_PIN:
				imageResource = R.drawable.irma_icon_card_found_520px;
				statusTextResource = R.string.status_waitingforpin;
				break;
			default:
				break;
		}

		((TextView) findViewById(R.id.status_text)).setText(statusTextResource);
		if (!showingFeedback)
			((ImageView) findViewById(R.id.statusimage)).setImageResource(imageResource);

		if (feedbackTextResource != 0)
			((TextView) findViewById(R.id.status_text)).setText(feedbackTextResource);
	}

	private void setFeedback(String message, String state) {
		int imageResource = 0;

		setUIForState();

		if (state.equals("success")) {
			imageResource = R.drawable.irma_icon_ok_520px;
		}
		if (state.equals("warning")) {
			imageResource = R.drawable.irma_icon_warning_520px;
		}
		if (state.equals("failure")) {
			imageResource = R.drawable.irma_icon_missing_520px;
		}

		((TextView) findViewById(R.id.feedback_text)).setText(message);

		if (imageResource != 0) {
			((ImageView) findViewById(R.id.statusimage)).setImageResource(imageResource);
			showingFeedback = true;
		}

		if (cdt != null)
			cdt.cancel();

		cdt = new CountDownTimer(FEEDBACK_SHOW_DELAY, 1000) {
			public void onTick(long millisUntilFinished) {
			}

			public void onFinish() {
				clearFeedback();
			}
		}.start();
	}

	private void clearFeedback() {
		showingFeedback = false;
		((TextView) findViewById(R.id.feedback_text)).setText("");
		setUIForState();
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Disable screenshots in release builds
		if (!BuildConfig.DEBUG) {
			getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
		}

		setContentView(R.layout.activity_main);
		loadCard();

		// Setup the DescriptionStore
		AndroidWalker aw = new AndroidWalker(getResources().getAssets());
		DescriptionStore.setTreeWalker(aw);
		IdemixKeyStore.setTreeWalker(aw);

		// NFC stuff
		nfcA = NfcAdapter.getDefaultAdapter(getApplicationContext());
		mPendingIntent = PendingIntent.getActivity(this, 0,
				new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);

		// Setup an intent filter for all TECH based dispatches
		IntentFilter tech = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
		mFilters = new IntentFilter[]{tech};

		// Setup a tech list for all IsoDep cards
		mTechLists = new String[][]{new String[]{IsoDep.class.getName()}};

		// For some reason this textview ignores the android:textIsSelectable in its xml file, so that it catches
		// touch events that were meant for its container. Don't know why setting its value here works.
		((TextView) findViewById(R.id.feedback_text)).setTextIsSelectable(false);

		// Display cool list
		ExpandableListView credentialList = (ExpandableListView) findViewById(R.id.listView);
		//ExpandableCredentialsAdapter adapter = new ExpandableCredentialsAdapter(this, credentialDescriptions, credentialAttributes);
		credentialListAdapter = new ExpandableCredentialsAdapter(this);
		credentialList.setAdapter(credentialListAdapter);
		updateCardCredentials();

		credentialList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
			@Override
			public boolean onItemLongClick(AdapterView<?> adapterView, View view, int i, long id) {
				try {
					CredentialDescription cd = (CredentialDescription) adapterView.getItemAtPosition(i);
					Log.i(TAG, "Credential with index " + i + " containing credential " + cd.getId() + " was " +
							"longclicked");

					CredentialPackage credential = new CredentialPackage(cd, credentialListAdapter.getAttributes(cd));
					Intent detailIntent = new Intent(MainActivity.this, CredentialDetailActivity.class);
					detailIntent.putExtra(CredentialDetailFragment.ARG_ITEM, credential);
					startActivityForResult(detailIntent, DETAIL_REQUEST);
				} catch (ClassCastException e) {
					Log.e(TAG, "Item " + i + " longclicked but was not a CredentialDescription");
				}

				return true;
			}
		});

		setState(STATE_IDLE);
		clearFeedback();

		updater = new AppUpdater(this, updateServer);
	}

	protected void clearCard() {
		if (activityState == STATE_IDLE) {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setTitle("Deleting credentials")
					.setMessage("Are you sure you want to delete ALL credentials?")
					.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							Log.i(TAG, "We're idle, attempting removal of all credentials");
							IdemixCredentials ic = new IdemixCredentials(is);
							List<CredentialDescription> credentialDescriptions = new ArrayList<CredentialDescription>();
							try {
								ic.connect();
								is.sendCardPin("000000".getBytes());
								credentialDescriptions = ic.getCredentials();
								for (CredentialDescription cd : credentialDescriptions) {
									ic.removeCredential(cd);
								}
							} catch (CardServiceException e) {
								e.printStackTrace();
							} catch (InfoException e) {
								e.printStackTrace();
							} catch (CredentialsException e) {
								e.printStackTrace();
							}
							updateCardCredentials();
							is.close();
							storeCard();
							// loadCard();
							logCard();
						}
					})
					.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							// Cancelled
						}
					});
			AlertDialog dialog = builder.create();
			dialog.show();
		}

	}

	protected void tryDeleteCredential(final CredentialDescription cd) {
		if (activityState != STATE_IDLE) {
			Log.i(TAG, "Delete long-click ignored in non-idle mode");
			return;
		}

		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle("Deleting credential")
				.setMessage("Are you sure you want to delete " + cd.getName() + "?")
				.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						Log.i(TAG, "We're idle, attempting removal of credential " + cd.getName());
						IdemixCredentials ic = new IdemixCredentials(is);
						try {
							ic.connect();
							is.sendCardPin("000000".getBytes());
							ic.removeCredential(cd);
						} catch (CredentialsException e) {
							e.printStackTrace();
						} catch (CardServiceException e) {
							e.printStackTrace();
						}
						Log.i(TAG, "Updating credential list");
						updateCardCredentials();
						is.close();
						storeCard();
					}
				})
				.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						// Cancelled
					}
				});
		AlertDialog dialog = builder.create();
		dialog.show();
	}

	protected void logCard() {
		Log.d(TAG, "Current card contents");
		// Retrieve list of credentials from the card
		IdemixCredentials ic = new IdemixCredentials(is);
		List<CredentialDescription> credentialDescriptions = new ArrayList<CredentialDescription>();
		// HashMap<CredentialDescription,Attributes> credentialAttributes = new HashMap<CredentialDescription,Attributes>();
		try {
			ic.connect();
			is.sendCardPin("000000".getBytes());
			credentialDescriptions = ic.getCredentials();
			for (CredentialDescription cd : credentialDescriptions) {
				Log.d(TAG, cd.getName());
			}
		} catch (CardServiceException e) {
			e.printStackTrace();
		} catch (InfoException e) {
			e.printStackTrace();
		} catch (CredentialsException e) {
			e.printStackTrace();
		}
	}

	protected void updateCardCredentials() {
		// Can only be run when not connected to a server
		if (activityState != STATE_IDLE) {
			return;
		}

		// Retrieve list of credentials from the card
		IdemixCredentials ic = new IdemixCredentials(is);
		List<CredentialDescription> credentialDescriptions = new ArrayList<CredentialDescription>();
		HashMap<CredentialDescription, Attributes> credentialAttributes = new HashMap<CredentialDescription, Attributes>();
		try {
			ic.connect();
			is.sendCardPin("000000".getBytes());
			credentialDescriptions = ic.getCredentials();
			for (CredentialDescription cd : credentialDescriptions) {
				credentialAttributes.put(cd, ic.getAttributes(cd));
			}
		} catch (CardServiceException e) {
			e.printStackTrace();
		} catch (InfoException e) {
			e.printStackTrace();
		} catch (CredentialsException e) {
			e.printStackTrace();
		}

		credentialListAdapter.updateData(credentialDescriptions, credentialAttributes);
	}


	@Override
	protected void onPause() {
		super.onPause();
		if (nfcA != null) {
			nfcA.disableForegroundDispatch(this);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		Log.i(TAG, "Action: " + getIntent().getAction());
		if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(getIntent().getAction())) {
			processIntent(getIntent());
		}
		if (nfcA != null) {
			nfcA.enableForegroundDispatch(this, mPendingIntent, mFilters, mTechLists);
		}

		updater.updateVersionInfo(false);
	}

	@Override
	protected void onDestroy() {
		storeCard();
		super.onDestroy();
	}

	private static final int MESSAGE_STARTGET = 1;
	String currentReaderURL = "";
	int currentHandlers = 0;

	Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case MESSAGE_STARTGET:
					Log.i(TAG, "MESSAGE_STARTGET received in handler!");
					AsyncHttpClient client = new AsyncHttpClient();
					client.setTimeout(50000); // timeout of 50 seconds
					client.setUserAgent("org.irmacard.cardemu");

					client.get(MainActivity.this, currentReaderURL, new AsyncHttpResponseHandler() {
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
								if (!(activityState == STATE_IDLE))
									handler.sendMessageDelayed(newMsg, 200);
							}
						}

						@Override
						//public void onFailure(Throwable arg0, String arg1) {
						public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {
							if (activityState != STATE_CONNECTING_TO_SERVER) {
								retry_counter = 0;
								return;
							}

							retry_counter += 1;

							// We should try again, but only if no new requests have started
							// and we should wait a bit longer
							if (currentHandlers <= 1 && retry_counter < MAX_RETRIES) {
								Message newMsg = new Message();
								setFeedback("Trying to reach server again...", "none");
								newMsg.what = MESSAGE_STARTGET;
								handler.sendMessageDelayed(newMsg, 5000);
							} else {
								retry_counter = 0;
								setFeedback("Failed to connect to server", "warning");
								setState(STATE_IDLE);
							}

						}

						public void onStart() {
							currentHandlers += 1;
						}

						;

						public void onFinish() {
							currentHandlers -= 1;
						}

						;
					});

					break;

				default:
					break;
			}
		}
	};

	private String currentWriteURL = null;
	private ReaderMessage lastReaderMessage = null;

	private void handleChannelData(String data) {
		Gson gson = new GsonBuilder().
				registerTypeAdapter(ProtocolCommand.class, new ProtocolCommandDeserializer()).
				registerTypeAdapter(ReaderMessage.class, new ReaderMessageDeserializer()).
				create();


		if (activityState == STATE_CONNECTING_TO_SERVER) {
			// this is the message that containts the url to write to
			JsonParser p = new JsonParser();
			JsonElement write_url = p.parse(data).getAsJsonObject().get("write_url");

			// The server either returns a JSON object containing just the write_url,
			// or a ReaderMessage which we deal with below. So if we don't find the
			// write_url, we let the rest of this method deal with it.
			if (write_url != null) {
				currentWriteURL = write_url.getAsString();
				setState(STATE_READY); // This ensures we will be in this block only once
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
			e.printStackTrace();
			return;
		}

		lastReaderMessage = rm;

		// If the other end sent a command, execute it
		if (rm.type.equals(ReaderMessage.TYPE_COMMAND)) {
			Log.i(TAG, "Got command message");

			if (activityState != STATE_READY) {
				// FIXME: Only when ready can we handle commands
				throw new RuntimeException(
						"Illegal command from server, no card currently connected");
			}

			if (rm.name.equals(ReaderMessage.NAME_COMMAND_AUTHPIN)) {
				askForPIN();
			} else {
				setState(STATE_COMMUNICATING);
				new ProcessReaderMessage().execute(new ReaderInput(lastTag, rm));
			}
		}

		// If the other end sent an event, deal with it
		if (rm.type.equals(ReaderMessage.TYPE_EVENT)) {
			EventArguments ea = (EventArguments) rm.arguments;
			if (rm.name.equals(ReaderMessage.NAME_EVENT_STATUSUPDATE)) {
				String state = ea.data.get("state");
				String feedback = ea.data.get("feedback");
				if (state != null) {
					setFeedback(feedback, state);
				}
			} else if (rm.name.equals(ReaderMessage.NAME_EVENT_TIMEOUT)) {
				setState(STATE_IDLE);
			} else if (rm.name.equals(ReaderMessage.NAME_EVENT_DONE)) {
				storeCard();
				setState(STATE_IDLE);
			}
		}
	}


	private void postMessage(ReaderMessage rm) {
		if (currentWriteURL != null) {
			Gson gson = new GsonBuilder().
					registerTypeAdapter(ProtocolResponse.class, new ProtocolResponseSerializer()).
					create();
			String data = gson.toJson(rm);
			AsyncHttpClient client = new AsyncHttpClient();
			try {
				client.post(MainActivity.this, currentWriteURL, new StringEntity(data), "application/json", new AsyncHttpResponseHandler() {
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
				e.printStackTrace();
			}
		}
	}

	public void onMainShapeTouch(View v) {
		if (activityState == STATE_IDLE) {
			lastTag = null;
			startQRScanner("Scan the QR image in the browser.");
		}
	}

	@Override
	public void onNewIntent(Intent intent) {
		setIntent(intent);
	}

	public void processIntent(Intent intent) {
		Tag tagFromIntent = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
		IsoDep tag = IsoDep.get(tagFromIntent);
		// Only proces tag when we're actually expecting a card.
		if (tag != null && activityState == STATE_CONNECTED) {
			setState(STATE_READY);
			postMessage(new ReaderMessage(ReaderMessage.TYPE_EVENT, ReaderMessage.NAME_EVENT_CARDFOUND, null));
			lastTag = tag;
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == PASSPORT_REQUEST && resultCode == RESULT_OK) {
			loadCard();
			updateCardCredentials();

		} else if (requestCode == DETAIL_REQUEST && resultCode == CredentialDetailActivity.RESULT_DELETE) {
			CredentialDescription cd = (CredentialDescription) data
					.getSerializableExtra(CredentialDetailActivity.ARG_RESULT_DELETE);
			tryDeleteCredential(cd);

		} else {
			IntentResult scanResult = IntentIntegrator
					.parseActivityResult(requestCode, resultCode, data);

			// Process the results from the QR-scanning activity
			if (scanResult != null) {
				String contents = scanResult.getContents();
				if (contents != null) {
					gotoConnectingState(contents);
				}
			}
		}
	}

	private void gotoConnectingState(String url) {
		Log.i(TAG, "Start channel listening: " + url);
		currentReaderURL = url;
		Message msg = new Message();
		msg.what = MESSAGE_STARTGET;
		setState(STATE_CONNECTING_TO_SERVER);
		handler.sendMessage(msg);
	}

	public void askForPIN() {
//		setState(STATE_WAITING_FOR_PIN);
//		DialogFragment newFragment = EnterPINDialogFragment.getInstance(tries);
//	    newFragment.show(getFragmentManager(), "pinentry");
		onPINEntry("0000");
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}

	public void startQRScanner(String message) {
		IntentIntegrator integrator = new IntentIntegrator(this);
		integrator.setPrompt(message);
		integrator.initiateScan();
	}


	private class ReaderInput {
		public IsoDep tag;
		public ReaderMessage message;
		public String pincode = null;

		public ReaderInput(IsoDep tag, ReaderMessage message) {
			this.tag = tag;
			this.message = message;
		}

		public ReaderInput(IsoDep tag, ReaderMessage message, String pincode) {
			this.tag = tag;
			this.message = message;
			this.pincode = pincode;
		}
	}

	private class ProcessReaderMessage extends AsyncTask<ReaderInput, Void, ReaderMessage> {


		@Override
		protected ReaderMessage doInBackground(ReaderInput... params) {
			ReaderInput input = params[0];
			IsoDep tag = input.tag;
			ReaderMessage rm = input.message;

			// It seems sometimes tag is null afterall
			if (card == null) {
				Log.e("ReaderMessage", "card is null, this should not happen!");
				return new ReaderMessage(ReaderMessage.TYPE_EVENT, ReaderMessage.NAME_EVENT_CARDLOST, null);
			}

			// TODO: The current version of the cardemu shouldn't depend on idemix terminal, but for now
			// it is convenient.
			try {
				if (!is.isOpen()) {
					// TODO: this is dangerous, this call to IdemixService already does a "select applet"
					is.open();
				}
				if (rm.name.equals(ReaderMessage.NAME_COMMAND_AUTHPIN)) {
					if (input.pincode != null) {
						// TODO: this should be done properly, maybe without using IdemixService?
						tries = is.sendCredentialPin(input.pincode.getBytes());

						return new ReaderMessage("response", rm.name, rm.id, new PinResultArguments(tries));
					}
				} else if (rm.name.equals(ReaderMessage.NAME_COMMAND_TRANSMIT)) {
					TransmitCommandSetArguments arg = (TransmitCommandSetArguments) rm.arguments;

					boolean verifying = false;
					boolean issuingOne = false;
					boolean issuingTwo = false;
					long start = 0, stop = 0;

					if (arg.commands.get(0).getKey().equals("startprove")) {
						verifying = true;
					}
					if (arg.commands.get(0).getKey().equals("start_issuance")) {
						issuingOne = true;
						MainActivity.this.issuingStartTime = System.currentTimeMillis();
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
								stop - MainActivity.this.issuingStartTime, true);
					}

					return new ReaderMessage(ReaderMessage.TYPE_RESPONSE, rm.name, rm.id, new ResponseArguments(responses));
				} else if (rm.name.equals(ReaderMessage.NAME_COMMAND_IDLE)) {
					// FIXME: IRMA specific implementation,
					// This command is not allowed in normal mode,
					// so it will result in an exception.
					//Log.i("READER", "Processing idle command");
					//is.getCredentials();
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
				setState(STATE_CONNECTED);
			} else {
				if (activityState == STATE_COMMUNICATING) {
					setState(STATE_READY);
				}
			}

			if (result.name.equals(ReaderMessage.NAME_COMMAND_AUTHPIN)) {
				// Handle pin separately, abort if pin incorrect and more tries
				// left
				PinResultArguments args = (PinResultArguments) result.arguments;
				if (!args.success) {
					if (args.tries > 0) {
						// Still some tries left, asking again
						setState(STATE_WAITING_FOR_PIN);
						askForPIN();
						return; // do not send a response yet.
					} else {
						// FIXME: No more tries left
						// Need to go to error state
					}
				}
			}

			if (verificationList.size() == 0) {
				// Post result to browser
				postMessage(result);
			} else {
				// We're doing disclosure proofs: ask for permission first
				askForVerificationPermission(result);
			}
		}
	}

	/**
	 * Show a dialog to the user asking him if he wishes to disclose the credentials
	 * and attributes contained in the verificationList. The disclosure proof(s) are
	 * assumed to be contained in the parameter msg. If the user consents, then
	 * msg will be posted to the server normally. If he answers no, the connection to
	 * the server will be aborted.
	 * <p/>
	 * This function supports the simultaneous disclosure of multiple credentials
	 * (although that currently never happens).
	 *
	 * @param msg The disclosure proofs to be sent if the user consents
	 */
	private void askForVerificationPermission(final ReaderMessage msg) {
		IdemixCredentials ic = new IdemixCredentials(is);
		HashMap<CredentialPackage, Attributes> map = new HashMap<>();

		// Store the ReaderMessage for when the user answered our dialog, see the onDisclose methods below
		disclosureproof = msg;

		// Build the argument to be passed to the DisclosureDialogFragment
		try {
			for (VerificationSetupData entry : verificationList) {
				CredentialDescription desc
						= DescriptionStore.getInstance().getCredentialDescription(entry.getID());

				List<AttributeDescription> attrDescs = desc.getAttributes();
				List<Short> attrIds = disclosureMaskToList(entry.getDisclosureMask());
				Attributes attrs = ic.getAttributes(desc);
				CredentialPackage credential = new CredentialPackage(desc, attrs);
				Attributes disclosed = new Attributes();

				for (short i = 0; i < desc.getAttributeNames().size(); ++i) {
					if (attrIds.contains(i)) {
						String attrname = attrDescs.get(i).getName();
						disclosed.add(attrname, attrs.get(attrname));
					}
				}

				map.put(credential, disclosed);
			}
		} catch (InfoException | CardServiceException | CredentialsException e) {
			e.printStackTrace();
			// If something went wrong we can't properly ask for permission,
			// so we don't want to send the disclosure proof. So abort the connection.
			abortConnection(msg);
			setState(STATE_IDLE);
		}

		// Show dialog
		DisclosureDialogFragment dialog = DisclosureDialogFragment.newInstance(map);
		dialog.show(getFragmentManager(), "disclosuredialog");

		// Be sure that we don't ask permission again for these disclosures
		verificationList.clear();
	}

	@Override
	public void onDiscloseOK() {
		postMessage(disclosureproof);
	}

	@Override
	public void onDiscloseCancel() {
		abortConnection(disclosureproof);
		setState(STATE_IDLE);
	}

	/**
	 * Aborts the connection to the server: sends ISO7816.SW_COMMAND_NOT_ALLOWED.
	 *
	 * @param msg The message that would have been sent otherwise
	 *            (needed for its id).
	 */
	private void abortConnection(ReaderMessage msg) {
		ResponseAPDU response = sw(ISO7816.SW_COMMAND_NOT_ALLOWED); // TODO is this the appropriate response?
		ProtocolResponses responses = new ProtocolResponses();

		responses.put("startprove", new ProtocolResponse("startprove", response));
		ReaderMessage rm = new ReaderMessage(
				ReaderMessage.TYPE_RESPONSE,
				ReaderMessage.NAME_COMMAND_TRANSMIT,
				msg.id,
				new ResponseArguments(responses));
		postMessage(rm);
	}

	// Helper function to build a ResponseAPDU from a status message. Copied from IRMACard.java
	private static ResponseAPDU sw(short status) {
		byte msbyte = (byte) ((byte) (status >> 8) & ((byte) 0xff));
		byte lsbyte = (byte) (((byte) status) & ((byte) 0xff));
		return new ResponseAPDU(new byte[]{msbyte, lsbyte});
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

	@Override
	public void onPINEntry(String dialogPincode) {
		// TODO: in the final version, the following debug code should go :)
		Log.i(TAG, "PIN entered: " + dialogPincode);
		setState(STATE_COMMUNICATING);
		new ProcessReaderMessage().execute(new ReaderInput(lastTag, lastReaderMessage, dialogPincode));
	}

	@Override
	public void onPINCancel() {
		Log.i(TAG, "PIN entry canceled!");
		postMessage(
				new ReaderMessage(ReaderMessage.TYPE_RESPONSE,
						ReaderMessage.NAME_COMMAND_AUTHPIN,
						lastReaderMessage.id,
						new ResponseArguments("cancel")));

		setState(STATE_READY);
	}

	public static class ErrorFeedbackDialogFragment extends DialogFragment {
		public static ErrorFeedbackDialogFragment newInstance(String title, String message) {
			ErrorFeedbackDialogFragment f = new ErrorFeedbackDialogFragment();
			Bundle args = new Bundle();
			args.putString("message", message);
			args.putString("title", title);
			f.setArguments(args);
			return f;
		}

		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
			builder.setMessage(getArguments().getString("message"))
					.setTitle(getArguments().getString("title"))
					.setPositiveButton("OK", new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							dialog.dismiss();
						}
					});
			return builder.create();
		}
	}

	@Override
	public void onBackPressed() {
		// When we are not in IDLE state, return there
		if (activityState != STATE_IDLE) {
			if (cdt != null)
				cdt.cancel();

			setState(STATE_IDLE);
			clearFeedback();
		} else {
			// We are in Idle, do what we always do
			super.onBackPressed();
		}
	}

	private ArrayList<LogEntry> getCardLog() {
		ArrayList<LogEntry> logs = new ArrayList<>();

		IdemixCredentials ic = new IdemixCredentials(is);

		try {
			for(LogEntry l : ic.getLog()) {
				logs.add(l);
			}
		} catch (CardServiceException|InfoException e) {
			e.printStackTrace();
			return null;
		}

		return logs;
	}


	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		Log.d(TAG, "menu press registered");
		// Handle item selection
		switch (item.getItemId()) {
			case R.id.menu_reset:
				Log.d(TAG, "menu_reset pressed");
				resetCard();
				return true;
			case R.id.enroll:
				Log.d(TAG, "enroll pressed");
				Intent i = new Intent(this, org.irmacard.cardemu.selfenrol.Passport.class);
				storeCard();
				i.putExtra("card_json", "loadCard");
				startActivityForResult(i, PASSPORT_REQUEST);
				return true;
			case R.id.show_card_log:
				Log.d(TAG, "show_card_log pressed");
				ArrayList<LogEntry> logs = getCardLog();
				if (logs != null) {
					Intent logIntent = new Intent(this, LogActivity.class);
					logIntent.putExtra(LogFragment.ARG_LOG, logs);
					startActivity(logIntent);
				}
				return true;
			case R.id.menu_clear:
				if (activityState == STATE_IDLE) {
					clearCard();
					updateCardCredentials();
				}
				return true;
			case R.id.check_for_updates:
				updater.updateVersionInfo(true, true);
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}


	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {

		MenuItem item = menu.findItem(R.id.menu_reset);
		//TODO: fix hele menu idem.

		if (activityState == STATE_IDLE) {
			item.setEnabled(true);
			// item.setVisible(true);
			//item.getIcon().setAlpha(255);
		} else {
			// disabled
			item.setEnabled(false);
			// item.setVisible(false);
			//item.getIcon().setAlpha(130);
		}
		return super.onPrepareOptionsMenu(menu);
	}

}
