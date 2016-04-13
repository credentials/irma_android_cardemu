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

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.*;
import android.widget.*;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.irmacard.cardemu.*;
import org.irmacard.cardemu.BuildConfig;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Calendar;
import java.util.GregorianCalendar;


public class EnrollSelectActivity extends AbstractGUIEnrollActivity {
    // Configuration
    private static final String TAG = "EnrollSelectActivity";
    public final static int EnrollSelectActivityCode = 200;
    protected static final int SCREEN_ISSUE = 4;
    protected static final int PASSPORT_ACTIVITY = 0;
    protected static final int DL_ACTIVITY = 1;

    // State variables
    protected int next_activity;
    protected DateFormat hrDateFormat = DateFormat.getDateInstance();
    private TextWatcher bacFieldWatcher;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "onCreate() called");

        // Disable screenshots in release builds
        if (!BuildConfig.DEBUG) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }

        setContentView(R.layout.enroll_activity_start);
        setTitle(R.string.app_name_enroll);

        enableContinueButton();

        screen = SCREEN_START;
        updateProgressCounter();

      //  String enrollServer = getEnrollmentServer().substring(8); // Strip "https://"
      //  enrollServer = enrollServer.substring(0, enrollServer.indexOf('/')); // Strip path from the url
        String helpHtml = String.format(getString(R.string.se_connect_mno));

        TextView helpTextView = (TextView)findViewById(R.id.se_feedback_text);
        if (helpTextView != null) { // Can be null if we are on error screen
            helpTextView.setText(Html.fromHtml(helpHtml));
            helpTextView.setMovementMethod(LinkMovementMethod.getInstance());
            helpTextView.setLinksClickable(true);
        }
    }


    /**
     * Called when the user presses the continue button
     */
    protected void advanceScreen(){
        switch (screen) {
            case SCREEN_START:
                setContentView(R.layout.enroll_activity_bac);
                screen = SCREEN_BAC;
                updateProgressCounter(screen - 1);
                Spinner spinner = (Spinner) findViewById(R.id.bac_selector);
                ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                        R.array.document_list, android.R.layout.simple_spinner_item);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(adapter);
                spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view,
                                               int position, long id) {
                        Log.v(TAG, (String) parent.getItemAtPosition(position));
                        RelativeLayout drivers_license = (RelativeLayout) findViewById(R.id.drivers_license_data);
                        RelativeLayout passport = (RelativeLayout) findViewById(R.id.passport_data);
                        if (position == 0) {
                            drivers_license.setVisibility(View.GONE);
                            passport.setVisibility(View.VISIBLE);
                            next_activity = PASSPORT_ACTIVITY;
                        } else if (position == 1) {
                            drivers_license.setVisibility(View.VISIBLE);
                            passport.setVisibility(View.GONE);
                            next_activity = DL_ACTIVITY;
                        } else {
                            throw new IllegalArgumentException("Pulldown list provided unspecified argument");
                        }
                        if (bacFieldWatcher != null)
                            bacFieldWatcher.onTextChanged("", 0, 0, 0);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        // TODO Auto-generated method stub
                    }
                });
                spinner.setSelection(settings.getInt("enroll_document", PASSPORT_ACTIVITY));
                populateBacAndBapFields();
                EditText docnrEditText;
                EditText dobEditText;
                EditText doeEditText;
                long bacDob;
                long bacDoe;

                enableContinueButton();
                setBacFieldWatcher();

                break;
            case SCREEN_BAC:
                if (next_activity == PASSPORT_ACTIVITY){
                    // Store the entered document number and dates in the settings.
                    docnrEditText = (EditText) findViewById(R.id.doc_nr_edittext);
                    dobEditText = (EditText) findViewById(R.id.dob_edittext);
                    doeEditText = (EditText) findViewById(R.id.doe_edittext);

                    if (docnrEditText != null && dobEditText != null && doeEditText != null) {
                        bacDob = 0;
                        bacDoe = 0;
                        try {
                            String dobString = dobEditText.getText().toString();
                            String doeString = doeEditText.getText().toString();
                            if (dobString.length() != 0)
                                bacDob = hrDateFormat.parse(dobString).getTime();
                            if (doeString.length() != 0)
                                bacDoe = hrDateFormat.parse(doeString).getTime();
                        } catch (ParseException e) {
                            // Should not happen: the DOB and DOE EditTexts are set only by the DatePicker's,
                            // OnDateSetListener, which should always set a properly formatted string.
                            e.printStackTrace();
                        }

                        if (bacDoe < System.currentTimeMillis()) {
                            showErrorScreen(getString(R.string.error_enroll_passport_expired),
                                    getString(R.string.abort), 0,
                                    getString(R.string.retry), SCREEN_BAC);
                            return;
                        }

                        settings.edit()
                                .putInt("enroll_document", next_activity)
                                .putLong("enroll_bac_dob", bacDob)
                                .putLong("enroll_bac_doe", bacDoe)
                                .putString("enroll_bac_docnr", docnrEditText.getText().toString())
                                .apply();
                    }

                    Intent i = new Intent(this, PassportEnrollActivity.class);
                    startActivityForResult(i, PassportEnrollActivity.PassportEnrollActivityCode);
                } else if (next_activity == DL_ACTIVITY){
                    //safe the mrz text field for later.
                    EditText mrzText = (EditText) findViewById(R.id.mrz);
                    if (mrzText != null){
                        settings.edit()
                                .putInt("enroll_document", next_activity)
                                .putString("mrz",mrzText.getText().toString())
                                .apply();
                    }
                    //TODO handle DL GUI

                    Intent i = new Intent(this, DriversLicenseEnrollActivity.class);
                    startActivityForResult(i, DriversLicenseEnrollActivity.DriversLicenseEnrollActivityCode);
                } else {
                    throw new IllegalStateException("Enroll Activity advancing an unknown screen: " + screen);
                }
                break;
            case SCREEN_ISSUE:
            case SCREEN_ERROR:
                screen = SCREEN_START;
                finish();
                break;

            default:
                Log.e(TAG, "Error, screen switch fall through: " + screen);
                break;
        }
    }


    public void startQRScanner(String message) {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setPrompt(message);
        integrator.initiateScan();
    }

    private void populateBacAndBapFields() {
        // Restore the BAC input fields from the settings, if present
        long bacDob = settings.getLong("enroll_bac_dob", 0);
        long bacDoe = settings.getLong("enroll_bac_doe", 0);
        String docnr = settings.getString("enroll_bac_docnr", "");

        EditText docnrEditText = (EditText) findViewById(R.id.doc_nr_edittext);
        EditText dobEditText = (EditText) findViewById(R.id.dob_edittext);
        EditText doeEditText = (EditText) findViewById(R.id.doe_edittext);

        Calendar c = Calendar.getInstance();
        if (bacDob != 0) {
            c.setTimeInMillis(bacDob);
            setDateEditText(dobEditText, c);
        }
        if (bacDoe != 0) {
            c.setTimeInMillis(bacDoe);
            setDateEditText(doeEditText, c);
        }
        if (docnr.length() != 0)
            docnrEditText.setText(docnr);

        //Restore the BAP input field from the settings, if present
        String mrz = settings.getString("mrz",null);
        EditText mrzText = (EditText) findViewById(R.id.mrz);
        if (mrzText != null){
            mrzText.setText(mrz);
        }
    }


    public void onDateTouch(View v) {
        final EditText dateView = (EditText) v;
        final String name = v.getId() == R.id.dob_edittext ? "dob" : "doe";
        Long current = settings.getLong("enroll_bac_" + name, 0);

        final Calendar c = Calendar.getInstance();
        if (current != 0)
            c.setTimeInMillis(current);

        int currentYear = c.get(Calendar.YEAR);
        int currentMonth = c.get(Calendar.MONTH);
        int currentDay = c.get(Calendar.DAY_OF_MONTH);

        DatePickerDialog dpd = new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int monthOfYear, int dayOfMonth) {
                Calendar date = new GregorianCalendar(year, monthOfYear, dayOfMonth);
                setDateEditText(dateView, date);
            }
        }, currentYear, currentMonth, currentDay);
        dpd.show();
    }

    private void setDateEditText(EditText dateView, Calendar c) {
        dateView.setText(hrDateFormat.format(c.getTime()));
    }

    private void setBacFieldWatcher() {
        final EditText docnrEditText = (EditText) findViewById(R.id.doc_nr_edittext);
        final EditText dobEditText = (EditText) findViewById(R.id.dob_edittext);
        final EditText doeEditText = (EditText) findViewById(R.id.doe_edittext);
        final EditText mrzEditText = (EditText) findViewById(R.id.mrz);
        final Button continueButton = (Button) findViewById(R.id.se_button_continue);


        bacFieldWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean enableButton = (next_activity == PASSPORT_ACTIVITY
                        && docnrEditText.getText().length() > 0
                        && dobEditText.getText().length() > 0
                        && doeEditText.getText().length() > 0)
                        || next_activity == DL_ACTIVITY
                        && mrzEditText.getText().length() > 0;
                continueButton.setEnabled(enableButton);
            }
        };

        docnrEditText.addTextChangedListener(bacFieldWatcher);
        dobEditText.addTextChangedListener(bacFieldWatcher);
        doeEditText.addTextChangedListener(bacFieldWatcher);
        mrzEditText.addTextChangedListener(bacFieldWatcher);

        bacFieldWatcher.onTextChanged("", 0, 0, 0);
    }

    public void onQRButtonTouch (View v) {
        if (next_activity == DL_ACTIVITY) {
            startQRScanner("Scan the QR image on your Driver's License.");
        }
    }


    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PassportEnrollActivity.PassportEnrollActivityCode || requestCode == DriversLicenseEnrollActivity.DriversLicenseEnrollActivityCode) {
            setResult(resultCode, data);
            super.finish();
        } else {
            IntentResult scanResult = IntentIntegrator
                    .parseActivityResult(requestCode, resultCode, data);

            // Process the results from the QR-scanning activity
            if (scanResult != null) {
                String contents = scanResult.getContents();
                if (contents != null) {
                    EditText mrzEditText = (EditText) findViewById(R.id.mrz);
                    mrzEditText.setText(contents);
                }
            }
        }
    }

}
