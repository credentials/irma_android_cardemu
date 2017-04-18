package org.irmacard.cardemu.store;


import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.Html;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.irmacard.cardemu.BuildConfig;
import org.irmacard.cardemu.R;

public class SchemeManagerEnroll {
    private SchemeManagerHandler.KeyserverInputHandler handler;
    private Activity activity;

    private String email;
    private String pin;

    private AlertDialog dialog;

    public SchemeManagerEnroll (Activity act, SchemeManagerHandler.KeyserverInputHandler handler ){
        this.activity = act;
        this.handler = handler;
        dialog = new AlertDialog.Builder(act)
                .setPositiveButton(R.string.next, null)
                .setNeutralButton(R.string.more_information, null)
                .setNegativeButton(R.string.back, null)
                .setCancelable(false)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        LayoutInflater inflater = activity.getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_keyshare_enroll, null);
        dialog.setView(view);
    }


    public void start (){
        dialog.show();
        ((TextView) dialog.findViewById(R.id.keyshare_enroll_step)).setText(activity.getString(R.string.step_string)+" 1");
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.INVISIBLE);

        final EditText emailView = (EditText) dialog.findViewById(R.id.keyshare_enroll_input);

        ((TextView) dialog.findViewById(R.id.keyshare_enroll_text)).setText(Html.fromHtml(activity.getString(R.string.mijnIRMA_enroll_email_description)));

        //some code in case you come to this screen via the back button on the PIN entry screen.
        emailView.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailView.setHint(R.string.email_address);
        if (email == null) {
            emailView.setText("");
        } else {
            emailView.setText(email);
        }
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse("https://privacybydesign.foundation/irma-begin/"));
                activity.startActivity(i);
            }
        });
        // By setting the click handler here instead of above, we get to decide ourselves if we
        // want to dismiss the dialog, so we can keep it if the input did not validate.
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String emailInput = emailView.getText().toString();
                if (!BuildConfig.DEBUG && !android.util.Patterns.EMAIL_ADDRESS.matcher(emailInput).matches()) {
                    Toast.makeText(activity, R.string.invalid_email_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                email = emailInput;
                //continue to the PIN entry screen
                pinEntry();
            }
        });


    }


    private void pinEntry(){
        ((TextView) dialog.findViewById(R.id.keyshare_enroll_step)).setText(activity.getString(R.string.step_string)+" 2");
        ((TextView) dialog.findViewById(R.id.keyshare_enroll_text)).setText(Html.fromHtml(activity.getString(R.string.mijnIRMA_enroll_pin_description,email)));
        final EditText pinView = (EditText) dialog.findViewById(R.id.keyshare_enroll_input);
        pinView.setText("");
        pinView.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinView.setHint(R.string.pinhint);

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.VISIBLE);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                start();
            }
        });
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String pinInput = pinView.getText().toString();
                if (!BuildConfig.DEBUG && pinInput.length() < 5) { // Allow short pins when testing
                    Toast.makeText(activity, R.string.invalid_pin_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                pin = pinInput;
                handler.done(email, pin);
                confirmation();
            }
        });
        dialog.show();
    }

    private void confirmation(){
        ((TextView) dialog.findViewById(R.id.keyshare_enroll_step)).setText(activity.getString(R.string.step_string)+" 3");
        ((TextView) dialog.findViewById(R.id.keyshare_enroll_text)).setText(Html.fromHtml(
                          activity.getString(R.string.mijnIRMA_enroll_email_confirm, email)));
        dialog.findViewById(R.id.keyshare_enroll_input).setVisibility(View.INVISIBLE);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.INVISIBLE);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(R.string.gotit);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
            }
        });
        dialog.show();

    }

}
