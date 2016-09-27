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

package org.irmacard.cardemu.selfenrol;

import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;
import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;
import org.acra.ACRA;
import org.irmacard.cardemu.MetricsReporter;
import org.irmacard.cardemu.R;
import org.irmacard.mno.common.DocumentDataMessage;
import org.irmacard.mno.common.EnrollmentStartMessage;
import org.irmacard.mno.common.PassportDataMessage;
import org.jmrtd.BACKey;
import org.jmrtd.PassportService;
import org.jmrtd.lds.SODFile;
import org.jmrtd.lds.icao.DG14File;
import org.jmrtd.lds.icao.DG15File;
import org.jmrtd.lds.icao.DG1File;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PassportEnrollActivity extends AbstractNFCEnrollActivity {
	// Configuration
	private static final String TAG = "cardemu.PassportEnrollA";
	public static final int PassportEnrollActivityCode = 300;

	protected int tagReadAttempt = 0;

	// State variables
	//protected PassportDataMessage passportMsg = null;

	// Date stuff
	protected SimpleDateFormat bacDateFormat = new SimpleDateFormat("yyMMdd", Locale.US);

	@Override
	protected String getURLPath() {
		return "/passport";
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Don't update the screen if super encountered a problem
		if (screen == SCREEN_ERROR)
			return;

		setNfcScreen(SCREEN_PASSPORT);

		// Update the UI
		setContentView(R.layout.enroll_activity_passport);
		screen = SCREEN_PASSPORT;
		updateProgressCounter();
	}


	@Override
	protected void handleNfcEvent(CardService service, EnrollmentStartMessage message) {
		TextView feedbackTextView = (TextView) findViewById(R.id.se_feedback_text);
		if (feedbackTextView != null) {
			feedbackTextView.setText(R.string.feedback_communicating_passport);
		}

		try {
			service.open();
			PassportService passportService = new PassportService(service);
			passportService.sendSelectApplet(false);

			if (documentMsg == null) {
				documentMsg = new PassportDataMessage(message.getSessionToken(), "", message.getNonce());
			}
			readPassport(passportService, documentMsg);
		} catch (CardServiceException e) {
			// TODO under what circumstances does this happen? Maybe handle it more intelligently?
			ACRA.getErrorReporter().handleException(e);
			showErrorScreen(getString(R.string.error_enroll_passporterror),
					getString(R.string.abort), 0,
					getString(R.string.retry), SCREEN_PASSPORT);
		}
	}

	/**
	 * Reads the datagroups 1, 14 and 15, and the SOD file and requests an active authentication from an e-passport
	 * in a seperate thread.
	 */
	private void readPassport(PassportService ps, DocumentDataMessage pdm) {
		new AsyncTask<Object,Void,PassportDataMessage>(){
			ProgressBar progressBar = (ProgressBar) findViewById(R.id.se_progress_bar);
			boolean passportError = false;
			boolean bacError = false;

			long start;
			long stop;

			@Override
			protected PassportDataMessage doInBackground(Object... params) {
				if (params.length <2) {
					return null; //TODO appropriate error
				}
				PassportService ps = (PassportService) params[0];
				PassportDataMessage pdm = (PassportDataMessage) params[1];

				if (tagReadAttempt == 0) {
					start = System.currentTimeMillis();
				}
				tagReadAttempt++;

				// Do the BAC separately from generating the pdm, so we can be specific in our error message if
				// necessary. (Note: the IllegalStateException should not happen, but if it does for some unforseen
				// reason there is no need to let it crash the app.)
				try {
					ps.doBAC(getBACKey());
					Log.i(TAG, "PassportEnrollActivity: doing BAC");
				} catch (CardServiceException | IllegalStateException e) {
					bacError = true;
					Log.e(TAG, "PassportEnrollActivity: doing BAC failed");
					return null;
				}

				Exception ex = null;
				try {
					Log.i(TAG, "PassportEnrollActivity: reading attempt " + tagReadAttempt);
					generatePassportDataMessage(ps, pdm);
				} catch (IOException |CardServiceException e) {
					Log.w(TAG, "PassportEnrollActivity: reading attempt " + tagReadAttempt + " failed, stack trace:");
					Log.w(TAG, "          " + e.getMessage());
					ex = e;
				}

				passportError = !pdm.isComplete();
				if (!pdm.isComplete() && tagReadAttempt == MAX_TAG_READ_ATTEMPTS && ex != null) {
					// Build a fancy report saying which fields we did and which we did not manage to get
					Log.e(TAG, "PassportEnrollActivity: too many attempts failed, aborting");
					ACRA.getErrorReporter().reportBuilder()
							.customData("sod", String.valueOf(pdm.getSodFile() == null))
							.customData("dg1File", String.valueOf(pdm.getDg1File() == null))
							.customData("dg14File", String.valueOf(pdm.getDg14File() == null))
							.customData("dg15File", String.valueOf(pdm.getDg15File() == null))
							.customData("response", String.valueOf(pdm.getResponse() == null))
							.exception(ex)
							.send();
				}

				return pdm;
			}

			@Override
			protected void onProgressUpdate(Void... values) {
				if (progressBar != null) { // progressBar can vanish if the progress goes wrong halfway through
					progressBar.incrementProgressBy(1);
				}
			}

			/**
			 * Do the AA protocol with the passport using the passportService, and put the response in a new
			 * PassportDataMessage. Also read some data groups.
			 */
			public void generatePassportDataMessage(PassportService passportService, PassportDataMessage pdm)
					throws CardServiceException, IOException {
				publishProgress();

				try {
					if (pdm.getDg1File() == null) {
						pdm.setDg1File(new DG1File(passportService.getInputStream(PassportService.EF_DG1)));
						Log.i(TAG, "PassportEnrollActivity: reading DG1");
						publishProgress();
					}
					if (pdm.getSodFile() == null) {
						pdm.setSodFile(new SODFile(passportService.getInputStream(PassportService.EF_SOD)));
						Log.i(TAG, "PassportEnrollActivity: reading SOD");
						publishProgress();
					}
					if (pdm.getSodFile() != null) { // We need the SOD file to check if DG14 exists
						if (pdm.getSodFile().getDataGroupHashes().get(14) != null) { // Checks if DG14 exists
							if (pdm.getDg14File() == null) {
								pdm.setDg14File(new DG14File(passportService.getInputStream(PassportService.EF_DG14)));
								Log.i(TAG, "PassportEnrollActivity: reading DG14");
								publishProgress();
							}
						} else { // If DG14 does not exist, just advance the progress bar
							Log.i(TAG, "PassportEnrollActivity: reading DG14 not necessary, skipping");
							publishProgress();
						}
					}
					if (pdm.getDg15File() == null) {
						pdm.setDg15File(new DG15File(passportService.getInputStream(PassportService.EF_DG15)));
						Log.i(TAG, "PassportEnrollActivity: reading DG15");
						publishProgress();
					}
					// The doAA() method does not use its first three arguments, it only passes the challenge
					// on to another functio within JMRTD.
					if (pdm.getResponse() == null) {
						pdm.setResponse(passportService.doAA(null, null, null, pdm.getChallenge()).getResponse());
						Log.i(TAG, "PassportEnrollActivity: doing AA");
						publishProgress();
					}
				} catch (NullPointerException e) {
					// JMRTD sometimes throws a nullpointer exception if the passport communcation goes wrong
					// (I've seen it happening if the passport is removed from the device halfway through)
					throw new IOException("NullPointerException during passport communication", e);
				}
			}

			@Override
			protected void onPostExecute(PassportDataMessage pdm) {
				// First set the result, since it may be partially okay
				documentMsg = pdm;

				Boolean done = pdm != null && pdm.isComplete();

				Log.i(TAG, "PassportEnrollActivity: attempt " + tagReadAttempt + " finished, done: " + done);

				// If we're not yet done, we should not advance the screen but just wait for further attempts
				if (tagReadAttempt < MAX_TAG_READ_ATTEMPTS && !done) {
					return;
				}

				stop = System.currentTimeMillis();
				MetricsReporter.getInstance().reportMeasurement("passport_data_attempts", tagReadAttempt, false);
				MetricsReporter.getInstance().reportMeasurement("passport_data_time", stop-start);

				// If we're here, we're done. Check for errors or failures, and advance the screen
				if (!bacError && !passportError) {
					enroll(); // This also advances the screen
				}

				if (bacError) {
					showErrorScreen(getString(R.string.error_enroll_bacfailed),
							getString(R.string.abort), 0,
							getString(R.string.retry), SCREEN_BAC);
				}

				if (passportError) {
					showErrorScreen(R.string.error_enroll_passporterror);
				}
			}
		}.execute(ps,pdm);
	}

	/**
	 * Get the BAC key using the input from the user from the BAC screen.
	 *
	 * @return The BAC key.
	 * @throws IllegalStateException when the BAC fields in the BAC screen have not been set.
	 */
	private BACKey getBACKey() {
		long dob = settings.getLong("enroll_bac_dob", 0);
		long doe = settings.getLong("enroll_bac_doe", 0);
		String docnr = settings.getString("enroll_bac_docnr", "");

		String dobString = bacDateFormat.format(new Date(dob));
		String doeString = bacDateFormat.format(new Date(doe));
		return new BACKey(docnr, dobString, doeString);
	}

}
