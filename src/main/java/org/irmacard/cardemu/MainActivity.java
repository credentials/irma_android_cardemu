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

import android.content.SharedPreferences;
import android.view.*;
import android.widget.*;

import org.irmacard.android.util.credentials.CredentialPackage;
import org.irmacard.android.util.credentialdetails.*;
import org.irmacard.android.util.cardlog.*;
import org.irmacard.cardemu.protocols.Protocol;
import org.irmacard.cardemu.protocols.ProtocolHandler;
import org.irmacard.cardemu.selfenrol.PassportEnrollActivity;
import org.irmacard.cardemu.updates.AppUpdater;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.util.log.LogEntry;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public class MainActivity extends Activity {
	public static final int PASSPORT_REQUEST = 100;
	private static final int DETAIL_REQUEST = 101;

	private static final String TAG = "CardEmuMainActivity";
	private static final String SETTINGS = "cardemu";

	private SharedPreferences settings;

	// Previewed list of credentials
	private ExpandableCredentialsAdapter credentialListAdapter;

	private int activityState = STATE_IDLE;

	// New states
	public static final int STATE_IDLE = 1;
	public static final int STATE_CONNECTING_TO_SERVER = 2;
	public static final int STATE_CONNECTED = 3;
	public static final int STATE_READY = 4;
	public static final int STATE_COMMUNICATING = 5;
	public static final int STATE_WAITING_FOR_PIN = 6;

	// Timer for briefly displaying feedback messages on CardEmu
	private CountDownTimer cdt;
	private static final int FEEDBACK_SHOW_DELAY = 10000;
	private boolean showingFeedback = false;

	// Time after which old Intents are ignored (in milliseconds)
	private static final long INTENT_EXPIRY_TIME = 5000;

	private AppUpdater updater;

	private long qrScanStartTime;

	// Keep track of last verification url to ensure we handle it only once
	private String lastSessionUrl = "()";

	private boolean launchedFromBrowser;

	private ProtocolHandler protocolHandler = new ProtocolHandler(this) {
		@Override public void onStatusUpdate(Action action, Status status) {
			switch (status) {
				case COMMUNICATING:
					setState(STATE_COMMUNICATING); break;
				case CONNECTED:
					setState(STATE_CONNECTED); break;
				case DONE:
					setState(STATE_IDLE); break;
			}
		}

		@Override public void onSuccess(Action action) {
			switch (action) {
				case DISCLOSING:
					setFeedback("Successfully disclosed attributes", "success"); break;
				case ISSUING:
					setFeedback("Issuing was successful", "success"); break;
			}
			finish(true);
		}

		@Override public void onCancelled(Action action) {
			switch (action) {
				case DISCLOSING:
					setFeedback("Cancelled disclosure", "warning"); break;
				case ISSUING:
					setFeedback("Cancelled issuing", "warning"); break;
			}
			finish(true);
		}

		@Override public void onFailure(Action action, String message) {
			String feedback;
			switch (action) {
				case DISCLOSING:
					feedback = "Disclosure failed: "; break;
				case ISSUING:
					feedback = "Issuing failed: "; break;
				case UNKNOWN:
				default:
					feedback = "Failed: "; break;
			}
			feedback += message;
			setFeedback(feedback, "failure");
			finish(false);
		}

		private void finish(boolean returnToBrowser) {
			setState(STATE_IDLE);
			if (launchedFromBrowser && returnToBrowser)
				onBackPressed();
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Log.i(TAG, "onCreate() called");

		// Disable screenshots in release builds
		if (!BuildConfig.DEBUG) {
			getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
		}

		setContentView(R.layout.activity_main);

		// For some reason this textview ignores the android:textIsSelectable in its xml file, so that it catches
		// touch events that were meant for its container. Don't know why setting its value here works.
		((TextView) findViewById(R.id.feedback_text)).setTextIsSelectable(false);

		CredentialManager.load();

		// Display cool list
		ExpandableListView credentialList = (ExpandableListView) findViewById(R.id.listView);
		credentialListAdapter = new ExpandableCredentialsAdapter(this);
		credentialList.setAdapter(credentialListAdapter);
		updateCredentialList();

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

		settings = getSharedPreferences(SETTINGS, 0);
	}

	public int getState() {
		return activityState;
	}

	public void setState(int state) {
		Log.i(TAG, "Set state: " + state);
		activityState = state;

		switch (activityState) {
			case STATE_IDLE:
				updateCredentialList();
				break;
			default:
				break;
		}

		setUIForState();
	}

	private void setUIForState() {
		int imageResource = 0;
		int statusTextResource = 0;

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

	protected void deleteAllCredentials() {
		if (activityState == STATE_IDLE) {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setTitle("Deleting credentials")
					.setMessage("Are you sure you want to delete ALL credentials?")
					.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							Log.i(TAG, "We're idle, attempting removal of all credentials");

							CredentialManager.deleteAll();

							updateCredentialList();
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

						CredentialManager.delete(cd);

						Log.i(TAG, "Updating credential list");
						updateCredentialList();
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

	protected void updateCredentialList() {
		// Can only be run when not connected to a server
		if (activityState != STATE_IDLE) {
			return;
		}

		HashMap<CredentialDescription, Attributes> credentials = CredentialManager.getAllAttributes();
		List<CredentialDescription> cds = new ArrayList<>(credentials.keySet());

		Collections.sort(cds, new Comparator<CredentialDescription>() {
			@Override
			public int compare(CredentialDescription lhs, CredentialDescription rhs) {
				return lhs.getName().compareTo(rhs.getName());
			}
		});

		credentialListAdapter.updateData(cds, credentials);

		TextView noCredsText = (TextView) findViewById(R.id.no_credentials_text);
		Button enrollButton = (Button) findViewById(R.id.enroll_button);
		int visibility = cds.isEmpty() ? View.VISIBLE : View.INVISIBLE;

		if (noCredsText != null && enrollButton != null) {
			noCredsText.setVisibility(visibility);
			enrollButton.setVisibility(visibility);
		}
	}


	@Override
	protected void onPause() {
		super.onPause();
		settings.edit().putString("lastSessionUrl", lastSessionUrl).apply();
		Log.i(TAG, "onPause() called");
	}

	@Override
	public void onNewIntent(Intent intent) {
		setIntent(intent);
	}

	@Override
	protected void onDestroy() {
		Log.i(TAG, "onDestroy() called");
		super.onDestroy();
	}

	@Override
	protected void onResume() {
		super.onResume();
		Log.i(TAG, "onResume() called");
		lastSessionUrl = settings.getString("lastSessionUrl", "");
		processIntent();
		updater.updateVersionInfo(false);
	}

	private void processIntent() {
		Intent intent = getIntent();
		Log.i(TAG, "processIntent() called, action: " + intent.getAction());

		String qr = intent.getStringExtra("qr");
		long timestamp  = intent.getLongExtra("timestamp", 0);

		if (!intent.getAction().equals(Intent.ACTION_VIEW) || qr == null) {
			return;
		}

		if (timestamp > 0 && System.currentTimeMillis() - timestamp > INTENT_EXPIRY_TIME) {
			Log.i(TAG, "Discarding event, timestamp (" + timestamp +
					") too old for qr: " + qr);
			return;
		}

		Log.i(TAG, "Received qr in intent: " + qr);
		if(!qr.equals(lastSessionUrl)) {
			lastSessionUrl = qr;
			launchedFromBrowser = true;
			Protocol.NewSession(qr, this, protocolHandler);
		} else {
			Log.i(TAG, "Already processed this qr");
		}
	}

	public void onMainShapeTouch(View v) {
		if (activityState == STATE_IDLE) {
			startQRScanner("Scan the QR image in the browser.");
		}
	}

	public void onEnrollButtonTouch(View v) {
		Intent i = new Intent(this, PassportEnrollActivity.class);
		CredentialManager.save();
		startActivityForResult(i, PASSPORT_REQUEST);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == PASSPORT_REQUEST && resultCode == RESULT_OK) {
			CredentialManager.loadFromCard();
			CredentialManager.save();
			updateCredentialList();
		}
		else if (requestCode == DETAIL_REQUEST && resultCode == CredentialDetailActivity.RESULT_DELETE) {
			CredentialDescription cd = (CredentialDescription) data
					.getSerializableExtra(CredentialDetailActivity.ARG_RESULT_DELETE);
			tryDeleteCredential(cd);

		}
		else {
			MetricsReporter.getInstance().reportAggregrateMeasurement("qr_scan_time", System.currentTimeMillis() - qrScanStartTime);

			IntentResult scanResult = IntentIntegrator
					.parseActivityResult(requestCode, resultCode, data);

			// Process the results from the QR-scanning activity
			if (scanResult != null) {
				String contents = scanResult.getContents();
				if (contents != null) {
					launchedFromBrowser = false;
					Protocol.NewSession(contents, this, protocolHandler);
				}
			}
		}
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
				CredentialManager.loadDefaultCard();
				CredentialManager.save();
				updateCredentialList();
				return true;
			case R.id.enroll:
				Log.d(TAG, "enroll menu item pressed");
				onEnrollButtonTouch(null);
				return true;
			case R.id.show_card_log:
				Log.d(TAG, "show_card_log pressed");
				ArrayList<LogEntry> logs = new ArrayList<>(CredentialManager.getLog());
				Intent logIntent = new Intent(this, LogActivity.class);
				logIntent.putExtra(LogFragment.ARG_LOG, logs);
				startActivity(logIntent);
				return true;
			case R.id.menu_clear:
				if (activityState == STATE_IDLE) {
					deleteAllCredentials();
					updateCredentialList();
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
