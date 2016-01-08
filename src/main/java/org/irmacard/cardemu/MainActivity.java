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

import android.os.AsyncTask;
import android.text.Html;
import android.view.*;
import android.widget.*;

import org.acra.ACRA;
import org.irmacard.android.util.credentials.CredentialPackage;
import org.irmacard.android.util.credentialdetails.*;
import org.irmacard.android.util.cardlog.*;
import org.irmacard.cardemu.HttpClient.HttpClientException;
import org.irmacard.cardemu.disclosuredialog.DisclosureDialogFragment;
import org.irmacard.cardemu.disclosuredialog.DisclosureInformationActivity;
import org.irmacard.cardemu.selfenrol.PassportEnrollActivity;
import org.irmacard.cardemu.updates.AppUpdater;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
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
import org.irmacard.verification.common.*;
import org.irmacard.verification.common.util.GsonUtil;

public class MainActivity extends Activity implements DisclosureDialogFragment.DisclosureDialogListener {
	public static final int PASSPORT_REQUEST = 100;
	private static final int DETAIL_REQUEST = 101;

	private String TAG = "CardEmuMainActivity";

	// Previewed list of credentials
	private ExpandableCredentialsAdapter credentialListAdapter;

	private int activityState = STATE_IDLE;
	private String protocolVersion;

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

	private AppUpdater updater;

	private long issuingStartTime;
	private long qrScanStartTime;

	private String disclosureServer;

	private APDUProtocol apduProtocol;

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
		apduProtocol = new APDUProtocol(this);

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

		Log.i(TAG, "onPause() called");
	}

	@Override
	protected void onResume() {
		super.onResume();

		Log.i(TAG, "onResume() called, action: " + getIntent().getAction());

		updater.updateVersionInfo(false);
	}

	@Override
	protected void onDestroy() {
		Log.i(TAG, "onDestroy() called");
		super.onDestroy();
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
	public void onNewIntent(Intent intent) {
		setIntent(intent);
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
					gotoConnectingState(contents);
				}
			}
		}
	}

	private void connectAPDUProtocol(String url) {
		IRMACard card = CredentialManager.saveCard();
		card.addVerificationListener(apduProtocol.getListener());
		apduProtocol.setCard(card);

		Log.i(TAG, "Start channel listening: " + url);
		apduProtocol.connect(url);
	}

	public void onAPDUProtocolDone() {
		CredentialManager.loadFromCard();
		CredentialManager.save();
	}

	private void connectJsonProtocol(String url) {
		if (!url.endsWith("/"))
			url = url + "/";
		disclosureServer = url;
		Log.i(TAG, "Start channel listening: " + url);

		setState(STATE_CONNECTING_TO_SERVER);
		final String server = url;
		final HttpClient client = new HttpClient(GsonUtil.getGson());

		new AsyncTask<Void,Void,DisclosureStartResult>() {
			@Override
			protected DisclosureStartResult doInBackground(Void... params) {
				try {
					DisclosureProofRequest request = client.doGet(DisclosureProofRequest.class, server);
					return new DisclosureStartResult(request);
				} catch (HttpClientException e) {
					return new DisclosureStartResult(e);
				}
			}

			@Override
			protected void onPostExecute(DisclosureStartResult result) {
				if (result.request != null) {
					setState(STATE_READY);
					askForVerificationPermission(result.request);
				} else {
					cancelDisclosure(server);
					String feedback;
					if (result.exception.getCause() != null)
						feedback =  result.exception.getCause().getMessage();
					else
						feedback = "Server returned status " + result.exception.status;
					setFeedback(feedback, "failure");
				}
			}
		}.execute();
	}

	private void gotoConnectingState(String json) {
		try {
			DisclosureQr contents = GsonUtil.getGson().fromJson(json, DisclosureQr.class);
			protocolVersion = contents.getVersion();

			switch (protocolVersion) {
				case "1.0":
					connectAPDUProtocol(contents.getUrl());
					break;
				case "2.0":
					connectJsonProtocol(contents.getUrl());
					break;
				default:
					setFeedback("Protocol not supported", "failure");
			}
		} catch (Exception e) { // Assume the QR contained just a bare URL
			protocolVersion = "1.0";
			connectAPDUProtocol(json);
		}
	}

	public void askForVerificationPermission(final DisclosureProofRequest request) {
		List<AttributeDisjunction> missing = new ArrayList<>();
		for (AttributeDisjunction disjunction : request.getContent()) {
			if (CredentialManager.getCandidates(disjunction).isEmpty()) {
				missing.add(disjunction);
			}
		}

		if (missing.isEmpty()) {
			DisclosureDialogFragment dialog = DisclosureDialogFragment.newInstance(request);
			dialog.show(getFragmentManager(), "disclosuredialog");
		}
		else {
			String message = "The verifier requires attributes of the following kind: ";
			int count = 0;
			int max = missing.size();
			for (AttributeDisjunction disjunction : missing) {
				count++;
				message += "<b>" + disjunction.getLabel() + "</b>";
				if (count < max - 1 || count == max)
					message += ", ";
				if (count == max - 1 && max > 1)
					message += " and ";
			}
			message += " but you do not have the appropriate attributes.";

			new AlertDialog.Builder(this)
					.setTitle("Missing attributes")
					.setMessage(Html.fromHtml(message))
					.setPositiveButton("OK", new DialogInterface.OnClickListener() {
						@Override public void onClick(DialogInterface dialog, int which) {
							cancelDisclosure(disclosureServer);
							onDiscloseCancel();
						}
					})
					.setNeutralButton("More Information", new DialogInterface.OnClickListener() {
						@Override public void onClick(DialogInterface dialog, int which) {
							cancelDisclosure(disclosureServer);
							Intent intent = new Intent(MainActivity.this, DisclosureInformationActivity.class);
							intent.putExtra("request", request);
							startActivity(intent);
						}
					})
					.show();
		}
	}

	@Override
	public void onDiscloseOK(final DisclosureProofRequest request) {
		switch (protocolVersion) {
			case "1.0":
				apduProtocol.sendDisclosureProof();
				CredentialManager.loadFromCard(); // retrieve log entry of disclosure
				break;
			case "2.0":
				discloseJsonProtocol(request);
				break;
		}
	}

	public void discloseJsonProtocol(final DisclosureProofRequest request) {
		setState(STATE_COMMUNICATING);

		new AsyncTask<Void,Void,String>() {
			HttpClient client = new HttpClient(GsonUtil.getGson());
			String successMessage = "Done";
			String server = disclosureServer;
			boolean shouldCancel = false;

			@Override
			protected String doInBackground(Void[] params) {
				DisclosureProofResult.Status status;

				try {
					ProofList proofs;
					try {
						proofs = CredentialManager.getProofs(request);
					} catch (CredentialsException e) {
						e.printStackTrace();
						shouldCancel = true;
						return e.getMessage();
					}

					status = client.doPost(DisclosureProofResult.Status.class, server + "proofs", proofs);
				} catch (HttpClientException e) {
					e.printStackTrace();
					if (e.getCause() != null)
						return e.getCause().getMessage();
					else
						return "Server returned status " + e.status;
				}

				if (status == DisclosureProofResult.Status.VALID)
					return successMessage;
				else { // We successfully computed a proof but server rejects it? That's fishy, report it
					String feedback = "Server rejected proof: " + status.name().toLowerCase();
					ACRA.getErrorReporter().handleException(new Exception(feedback));
					return feedback;
				}
			}

			@Override
			protected void onPostExecute(String result) {
				setState(STATE_IDLE);
				String status = result.equals(successMessage) ? "success" : "failure";

				if (shouldCancel)
					cancelDisclosure(server);

				// Translate some possible problems to more human-readable versions
				if (result.startsWith("failed to connect"))
					result = "Could not connect";
				if (result.startsWith("Supplied sessionToken not found or expired"))
					result = "Server refused connection";

				setFeedback(result, status);
			}
		}.execute();
	}

	@Override
	public void onDiscloseCancel() {
		switch (protocolVersion) {
			case "1.0":
				apduProtocol.abortConnection();
				break;
			case "2.0":
				cancelDisclosure(disclosureServer);
				break;
		}

		setState(STATE_IDLE);
		setFeedback("Disclosure cancelled", "failure");
	}

	/**
	 * Cancels the current disclosure session by DELETE-ing the specified url and setting the state to idle.
	 */
	private void cancelDisclosure(final String server) {
		disclosureServer = null;

		new AsyncTask<Void,Void,Void>() {
			@Override protected Void doInBackground(Void... params) {
				try {
					new HttpClient(GsonUtil.getGson()).doDelete(server);
				} catch (HttpClientException e) {
					e.printStackTrace();
				}
				return null;
			}
		}.execute();
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
