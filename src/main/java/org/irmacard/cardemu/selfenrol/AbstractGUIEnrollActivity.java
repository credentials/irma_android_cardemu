package org.irmacard.cardemu.selfenrol;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import org.irmacard.api.common.exceptions.ApiError;
import org.irmacard.cardemu.BuildConfig;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.SecureSSLSocketFactory;

/**
 * Abstract base class for enrolling, coontaining generic UI, NFC and networking logic. Things handled by this class
 * include:
 * <ul>
 *     <li>The progress counter in the top right of the screen, see {@link #updateProgressCounter(int)}</li>
 *     <li>The error screen, see {@link #showErrorScreen(int)} and its friends</li>
 * </ul>
 * Inheriting classes are expected to handle screens (see
 * {@link #advanceScreen()}), take care of its own UI, and do the actual enrolling.
 */
public abstract class AbstractGUIEnrollActivity extends Activity{

    private static final String TAG = "cardemu.AbsGUIEnrollAct";

    protected static final String SETTINGS = "cardemu";
    protected static final int SCREEN_START = 1;
    protected static final int SCREEN_BAC = 2;
    protected static final int SCREEN_ERROR = -1;

    //state variables
    protected static String imsi;
    protected int screen;

    // Settings
    protected SharedPreferences settings;


    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = getSharedPreferences(SETTINGS, 0);
    }


    public void onBackPressed() {
        super.onBackPressed();
        Log.i(TAG, "onBackPressed() called");
        finish();
    }


    protected void onResume() {
        super.onResume();
        Intent intent = getIntent();
        Log.i(TAG, "onResume() called, action: " + intent.getAction());
    }

    public void onPause() {
        super.onPause();
        Log.i(TAG, "onPause() called");
    }

    protected void enableContinueButton() {
        final Button button = (Button) findViewById(R.id.se_button_continue);
        button.setVisibility(View.VISIBLE);
        button.setEnabled(true);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(TAG, "continue button pressed");
                advanceScreen();
            }
        });
    }

    protected void updateProgressCounter(int count) {
        updateStepLabels(count, R.color.irmadarkblue);
    }

    protected void updateProgressCounter() {
        updateProgressCounter(screen - 1);
    }

    private void updateStepLabels(int count, int color) {
        Resources r = getResources();

        if (count >= 0)
            ((TextView)findViewById(R.id.step_text)).setTextColor(r.getColor(color));
        if (count >= 1)
            ((TextView)findViewById(R.id.step1_text)).setTextColor(r.getColor(color));
        if (count >= 2)
            ((TextView) findViewById(R.id.step2_text)).setTextColor(r.getColor(color));
        if (count >= 3)
            ((TextView) findViewById(R.id.step3_text)).setTextColor(r.getColor(color));
    }

    private void prepareErrorScreen() {
        setContentView(R.layout.enroll_activity_error);
        updateStepLabels(screen - 1, R.color.irmared);
        screen = SCREEN_ERROR;
    }


    protected void showErrorScreen(int errormsgId) {
        ApiError[] errors = ApiError.values();
        if (errormsgId < errors.length)
                if (errors[errormsgId] != ApiError.EXCEPTION) // Contains detailed message, show it
                    showErrorScreen("Server reported: " + errors[errormsgId].getDescription());
            else
                showErrorScreen(getString(R.string.error_enroll_serverdied));
    }

    protected void showErrorScreen(String errormsg) {
        showErrorScreen(errormsg, getString(R.string.abort), 0, null, 0);
    }

    /**
     * Show the error screen.
     *
     * @param errormsg The message to show.
     * @param rightButtonString The text that the right button should show.
     * @param leftButtonScreen The screen that we shoul;d go to when the right button is clicked. Pass 0 if the
     *                         activity should be canceled.
     * @param leftButtonString The text that the left button should show. Pass null if this button should be hidden.
     * @param rightButtonScreen The screen that we should go to when the left button is clicked.
     */
    protected void showErrorScreen(String errormsg, final String rightButtonString, final int rightButtonScreen,
                                   final String leftButtonString, final int leftButtonScreen) {
        prepareErrorScreen();

        TextView view = (TextView)findViewById(R.id.enroll_error_msg);
        Button rightButton = (Button)findViewById(R.id.se_button_continue);
        Button leftButton = (Button)findViewById(R.id.se_button_cancel);

        view.setText(errormsg);

        rightButton.setText(rightButtonString);
        rightButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rightButtonScreen == 0) {
                    screen = SCREEN_START;
                    finish();
                } else {
                    screen = rightButtonScreen - 1;
                    advanceScreen();
                }
            }
        });

        if (leftButtonString != null) {
            leftButton.setText(leftButtonString);
            leftButton.setVisibility(View.VISIBLE);
            leftButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (leftButtonScreen == 0) {
                        screen = SCREEN_START;
                        finish();
                    } else {
                        screen = leftButtonScreen - 1;
                        advanceScreen();
                    }
                }
            });
        } else {
            leftButton.setVisibility(View.INVISIBLE);
        }
    }

    abstract protected void advanceScreen ();

    private static int protocolVersion = 2;

    public static int getProtocolVersion() { return protocolVersion; }
    public static void setProtocolVersion(int version) { protocolVersion = version; }

    protected static String getEnrollmentServer() {
        return BuildConfig.enrollServer + "/v" + protocolVersion;
    }
}
