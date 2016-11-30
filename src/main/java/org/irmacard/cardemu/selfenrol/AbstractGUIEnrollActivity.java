package org.irmacard.cardemu.selfenrol;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import org.irmacard.api.common.exceptions.ApiError;
import org.irmacard.cardemu.BuildConfig;
import org.irmacard.cardemu.R;

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

    @SuppressWarnings("FieldCanBeLocal")
    private static int protocolVersion = 2;

    protected static final String SETTINGS = "cardemu";
    protected static final int SCREEN_START = 1;
    protected static final int SCREEN_ERROR = -1;
    protected static final int SCREEN_BAC = 2;
    protected static final int SCREEN_PASSPORT = 3;
    protected static final int SCREEN_ISSUE = 4;

    //state variables
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
        if (count >= 0)
            ((TextView)findViewById(R.id.step_text)).setTextColor(ContextCompat.getColor(this, color));
        if (count >= 1)
            ((TextView)findViewById(R.id.step1_text)).setTextColor(ContextCompat.getColor(this, color));
        if (count >= 2)
            ((TextView) findViewById(R.id.step2_text)).setTextColor(ContextCompat.getColor(this, color));
        if (count >= 3)
            ((TextView) findViewById(R.id.step3_text)).setTextColor(ContextCompat.getColor(this, color));
    }

    private void prepareErrorScreen() {
        setContentView(R.layout.enroll_activity_error);
        updateStepLabels(screen - 1, R.color.irmared);
        screen = SCREEN_ERROR;
    }


    protected void showErrorScreen(int errormsgId) {
        String errormsg;
        ApiError[] errors = ApiError.values();

        // See if it is an ApiError code
        if (errormsgId < errors.length) {
            if (errors[errormsgId] != ApiError.EXCEPTION) // Contains detailed message, getEnrollInput it
                errormsg = getString(R.string.server_reported, errors[errormsgId].getDescription());
            else // Unspecified error, getEnrollInput generic message
                errormsg = getString(R.string.error_enroll_serverdied);
        }
        else { // See if it is a string from strings.xml
            try {
                errormsg = getString(errormsgId);
            } catch (Resources.NotFoundException e) {
                errormsg = getString(R.string.error_enroll_serverdied);
            }
        }

        showErrorScreen(errormsg);
    }

    protected void showErrorScreen(String errormsg) {
        showErrorScreen(errormsg, getString(R.string.abort), 0, null, 0);
    }

    /**
     * Show the error screen.
     *
     * @param errormsg The message to getEnrollInput.
     * @param rightButtonString The text that the right button should getEnrollInput.
     * @param leftButtonScreen The screen that we shoul;d go to when the right button is clicked. Pass 0 if the
     *                         activity should be canceled.
     * @param leftButtonString The text that the left button should getEnrollInput. Pass null if this button should be hidden.
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

    protected void advanceScreen() {
        switch (screen) {
            case SCREEN_PASSPORT:
                setContentView(R.layout.enroll_activity_issue);
                screen = SCREEN_ISSUE;
                updateProgressCounter();
                break;
            case SCREEN_ISSUE:
            case SCREEN_ERROR:
                screen = SCREEN_START;
                finish();
                break;

            default:
                Log.e(TAG, "Error, screen switch fall through");
                break;
        }
    }

    protected static String getEnrollmentServer() {
        return BuildConfig.enrollServer + "/v" + protocolVersion;
    }
}
