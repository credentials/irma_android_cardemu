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

import java.util.*;

import android.view.*;
import android.widget.*;
import com.google.gson.*;
import net.sf.scuba.smartcards.*;

import org.acra.ACRA;
import org.irmacard.android.util.credentials.AndroidWalker;
import org.irmacard.android.util.credentials.CredentialPackage;
import org.irmacard.android.util.disclosuredialog.DisclosureDialogFragment;
import org.irmacard.android.util.disclosuredialog.DisclosureDialogFragment.DisclosureDialogListener;
import org.irmacard.android.util.credentialdetails.*;
import org.irmacard.android.util.cardlog.*;
import org.irmacard.cardemu.selfenrol.PassportEnrollActivity;
import org.irmacard.cardemu.updates.AppUpdater;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.info.IdemixKeyStore;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.credentials.info.AttributeDescription;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.util.log.LogEntry;
import org.irmacard.idemix.IdemixService;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import org.irmacard.idemix.util.VerificationSetupData;


public class MainActivity extends Activity implements DisclosureDialogListener {
	public static final int PASSPORT_REQUEST = 100;
	private static final int DETAIL_REQUEST = 101;

	private String TAG = "CardEmuMainActivity";

	// Previewed list of credentials
	ExpandableCredentialsAdapter credentialListAdapter;

	// State variables
	private IRMACard card = null;
	private IdemixService is = null;

	private int activityState = STATE_IDLE;

	// New states
	public static final int STATE_IDLE = 1;
	public static final int STATE_CONNECTING_TO_SERVER = 2;
	public static final int STATE_CONNECTED = 3;
	public static final int STATE_READY = 4;
	public static final int STATE_COMMUNICATING = 5;
	public static final int STATE_WAITING_FOR_PIN = 6;

	// Timer for briefly displaying feedback messages on CardEmu
	CountDownTimer cdt;
	private static final int FEEDBACK_SHOW_DELAY = 10000;
	private boolean showingFeedback = false;

	AppUpdater updater;

	APDUProtocol apduProtocol;

	private long qrScanStartTime;

	public int getState() {
		return activityState;
	}

	public void setState(int state) {
		Log.i(TAG, "Set state: " + state);
		activityState = state;

		switch (activityState) {
			case STATE_IDLE:
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

	public void setFeedback(String message, String state) {
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

		Log.i(TAG, "onCreate() called");

		// Disable screenshots in release builds
		if (!BuildConfig.DEBUG) {
			getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
		}

		setContentView(R.layout.activity_main);

		apduProtocol = new APDUProtocol(this);
		card = CardManager.loadCard(apduProtocol.getListener());
		apduProtocol.setCard(card);
		is = new IdemixService(new SmartCardEmulatorService(card));

		// Setup the DescriptionStore
		AndroidWalker aw = new AndroidWalker(getResources().getAssets());
		DescriptionStore.setTreeWalker(aw);
		IdemixKeyStore.setTreeWalker(aw);

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

		updater = new AppUpdater(this, BuildConfig.updateServer);
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
							List<CredentialDescription> credentialDescriptions;
							try {
								ic.connect();
								is.sendCardPin("000000".getBytes());
								credentialDescriptions = ic.getCredentials();
								for (CredentialDescription cd : credentialDescriptions) {
									ic.removeCredential(cd);
								}
							} catch (CardServiceException|InfoException|CredentialsException e) {
								e.printStackTrace();
							}

							updateCardCredentials();
							is.close();
							CardManager.storeCard();
							CardManager.logCard(is);
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
						} catch (CredentialsException | CardServiceException e) {
							//e.printStackTrace();
							ACRA.getErrorReporter().handleException(e);
						}
						Log.i(TAG, "Updating credential list");
						updateCardCredentials();
						is.close();
						CardManager.storeCard();
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

	protected void updateCardCredentials() {
		// Can only be run when not connected to a server
		if (activityState != STATE_IDLE) {
			return;
		}

		// Retrieve list of credentials from the card
		IdemixCredentials ic = new IdemixCredentials(is);
		List<CredentialDescription> credentialDescriptions = new ArrayList<>();
		HashMap<CredentialDescription, Attributes> credentialAttributes = new HashMap<>();
		try {
			ic.connect();
			is.sendCardPin("000000".getBytes());
			credentialDescriptions = ic.getCredentials();
			for (CredentialDescription cd : credentialDescriptions) {
				credentialAttributes.put(cd, ic.getAttributes(cd));
			}
		} catch (CardServiceException|InfoException|CredentialsException e) {
			ACRA.getErrorReporter().handleException(e);
		}

		credentialListAdapter.updateData(credentialDescriptions, credentialAttributes);

		TextView noCredsText = (TextView) findViewById(R.id.no_credentials_text);
		Button enrollButton = (Button) findViewById(R.id.enroll_button);
		int visibility = credentialDescriptions.isEmpty() ? View.VISIBLE : View.INVISIBLE;

		if (noCredsText != null && enrollButton != null) {
			noCredsText.setVisibility(visibility);
			enrollButton.setVisibility(visibility);
		}
	}


	@Override
	protected void onPause() {
		super.onPause();

		Log.i(TAG, "onPause() called");
	}

	@Override
	protected void onResume() {
		super.onResume();

		Intent intent = getIntent();
		Log.i(TAG, "onResume() called, action: " + intent.getAction());

		updater.updateVersionInfo(false);
	}

	@Override
	protected void onDestroy() {
		Log.i(TAG, "onDestroy() called");
		CardManager.storeCard();
		super.onDestroy();
	}

	public void onMainShapeTouch(View v) {
		if (activityState == STATE_IDLE) {
			startQRScanner("Scan the QR image in the browser.");
		}
	}

	public void onEnrollButtonTouch(View v) {
		Intent i = new Intent(this, PassportEnrollActivity.class);
		CardManager.storeCard();
		startActivityForResult(i, PASSPORT_REQUEST);
	}

	@Override
	public void onNewIntent(Intent intent) {
		setIntent(intent);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == PASSPORT_REQUEST && resultCode == RESULT_OK) {
			card = CardManager.loadCard(apduProtocol.getListener());
			apduProtocol.setCard(card);
			is = new IdemixService(new SmartCardEmulatorService(card));
			updateCardCredentials();

		} else if (requestCode == DETAIL_REQUEST && resultCode == CredentialDetailActivity.RESULT_DELETE) {
			CredentialDescription cd = (CredentialDescription) data
					.getSerializableExtra(CredentialDetailActivity.ARG_RESULT_DELETE);
			tryDeleteCredential(cd);

		} else {
			MetricsReporter.getInstance().reportAggregrateMeasurement("qr_scan_time", System.currentTimeMillis() - qrScanStartTime);

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

	private void gotoConnectingState(String data) {
		String url;

		try {
			JsonObject o = new JsonParser().parse(data).getAsJsonObject();
			String version = o.get("v").getAsString();

			if (!version.equals("1.0")) {
				new AlertDialog.Builder(this)
						.setTitle("Not Supported")
						.setMessage("This verifier uses a protocol which this version of the app does not yet support. No attributes will be disclosed.")
						.setIcon(R.drawable.irma_icon_missing_064px)
						.setPositiveButton("OK", null)
						.show();
				return;
			}

			url = o.get("u").getAsString();
		} catch (JsonSyntaxException | NullPointerException e) {
			// If parsing the data as JSON failed, assume the old behavior, i.e., assume it is an url
			url = data;
		}

		Log.i(TAG, "Start channel listening: " + url);
		apduProtocol.connect(url);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}

	public void startQRScanner(String message) {
		qrScanStartTime = System.currentTimeMillis();
		IntentIntegrator integrator = new IntentIntegrator(this);
		integrator.setPrompt(message);
		integrator.initiateScan();
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
	 */
	public void askForVerificationPermission(List<VerificationSetupData> verificationList) {
		IdemixCredentials ic = new IdemixCredentials(is);
		HashMap<CredentialPackage, Attributes> map = new HashMap<>();

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
			//e.printStackTrace();
			ACRA.getErrorReporter().handleException(e);
			// If something went wrong we can't properly ask for permission,
			// so we don't want to send the disclosure proof. So abort the connection.
			apduProtocol.abortConnection();
			setState(STATE_IDLE);
		}

		// Show dialog
		DisclosureDialogFragment dialog = DisclosureDialogFragment.newInstance(map);
		dialog.show(getFragmentManager(), "disclosuredialog");
	}

	@Override
	public void onDiscloseOK() {
		apduProtocol.sendDisclosureProof();
	}

	@Override
	public void onDiscloseCancel() {
		apduProtocol.abortConnection();
		setState(STATE_IDLE);
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
	public boolean onPrepareOptionsMenu(Menu menu) {
		MenuItem item = menu.findItem(R.id.menu_reset);
		item.setVisible(CardManager.hasDefaultCard());
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		Log.d(TAG, "menu press registered");
		// Handle item selection
		switch (item.getItemId()) {
			case R.id.menu_reset:
				Log.d(TAG, "menu_reset pressed");
				card = CardManager.loadDefaultCard(apduProtocol.getListener());
				apduProtocol.setCard(card);
				is = new IdemixService(new SmartCardEmulatorService(card));
				CardManager.storeCard();
				updateCardCredentials();
				return true;
			case R.id.enroll:
				Log.d(TAG, "enroll menu item pressed");
				onEnrollButtonTouch(null);
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

}
