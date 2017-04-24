package org.irmacard.cardemu.store;


import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.Html;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.BuildConfig;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;
import org.irmacard.cardemu.httpclient.JsonHttpClient;
import org.irmacard.credentials.info.SchemeManager;

public class SchemeManagerEnroll {
    private SchemeManagerHandler.KeyserverInputHandler handler;
    private Activity activity;

    private String email;
    private String pin;
    private SchemeManager manager;

    private AlertDialog dialog;

    public SchemeManagerEnroll (Activity act,
                                SchemeManager manager,
                                SchemeManagerHandler.KeyserverInputHandler handler ){
        this.activity = act;
        this.handler = handler;
        this.manager = manager;
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
        ((TextView) dialog.findViewById(R.id.keyshare_enroll_step)).setText(activity.getString(R.string.step_string, 1));
        ((TextView) dialog.findViewById(R.id.keyshare_enroll_step)).setVisibility(View.VISIBLE);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.GONE);
        dialog.findViewById(R.id.keyshare_pin_input).setVisibility(View.GONE);

        final EditText emailView = (EditText) dialog.findViewById(R.id.keyshare_enroll_input);
        emailView.setVisibility(View.VISIBLE);

        ((TextView) dialog.findViewById(R.id.keyshare_enroll_text)).setText(Html.fromHtml(activity.getString(R.string.keyshare_enroll_email_description)));

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
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(R.string.next);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String emailInput = emailView.getText().toString();
                if (!BuildConfig.DEBUG && !android.util.Patterns.EMAIL_ADDRESS.matcher(emailInput).matches()) {
                    Toast.makeText(activity, R.string.invalid_email_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                email = emailInput;

                final JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());
                client.get(
                        Boolean.class,
                        manager.getKeyshareServer() + "/web/users/available/" + email,
                        new HttpResultHandler<Boolean>() {
                            @Override public void onSuccess(Boolean result) {
                                if (result)
                                    pinEntry();
                                else
                                    showError(activity.getString(R.string.emailaddress_in_use_text, email));
                            }
                            @Override public void onError(HttpClientException exception) {
                                showError(activity.getString(R.string.keyshare_connection_error));
                            }
                        }
                );
            }
        });
    }

    private void showError(String message) {
        ((TextView) dialog.findViewById(R.id.keyshare_enroll_step)).setVisibility(View.GONE);
        ((EditText) dialog.findViewById(R.id.keyshare_enroll_input)).setVisibility(View.GONE);
        ((TextView) dialog.findViewById(R.id.keyshare_enroll_text)).setText(message);

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.GONE);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(R.string.back);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start();
            }
        });
    }


    private void pinEntry(){
        ((TextView) dialog.findViewById(R.id.keyshare_enroll_step)).setText(activity.getString(R.string.step_string, 2));
        ((TextView) dialog.findViewById(R.id.keyshare_enroll_text)).setText(Html.fromHtml(activity.getString(R.string.keyshare_enroll_pin_description,email)));
        final EditText pinView = (EditText) dialog.findViewById(R.id.keyshare_enroll_input);
        pinView.setText("");
        pinView.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinView.setHint(R.string.pinhint);

        final EditText pinAgainView = (EditText) dialog.findViewById(R.id.keyshare_pin_input);
        pinAgainView.setVisibility(View.VISIBLE);
        pinAgainView.setText("");

        final View okButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        okButton.setEnabled(false);

        final TextWatcher pinValidator = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                boolean pinMatch = pinView.getText().toString().equals(pinAgainView.getText().toString());
                boolean longEnough = pinView.getText().length() > 4;

                okButton.setEnabled(pinMatch && (longEnough));

                if (!longEnough && pinView.getText().length() > 0)
                    pinView.setError(activity.getString(R.string.invalid_pin_error));
                else
                    pinView.setError(null);
                if (!pinMatch && pinAgainView.getText().length() > 0)
                    pinAgainView.setError(activity.getString(R.string.pins_dont_match_error));
                else
                    pinAgainView.setError(null);
            }
        };
        pinView.addTextChangedListener(pinValidator);
        pinAgainView.addTextChangedListener(pinValidator);

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.VISIBLE);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                start();
            }
        });
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pin = pinView.getText().toString();
                handler.done(email, pin);
                confirmation();
            }
        });
        dialog.show();
    }

    private void confirmation(){
        ((TextView) dialog.findViewById(R.id.keyshare_enroll_step)).setText(activity.getString(R.string.step_string, 3));
        ((TextView) dialog.findViewById(R.id.keyshare_enroll_text)).setText(Html.fromHtml(
                          activity.getString(R.string.keyshare_enroll_email_confirm, email)));
        dialog.findViewById(R.id.keyshare_enroll_input).setVisibility(View.GONE);
        ((EditText) dialog.findViewById(R.id.keyshare_pin_input)).setVisibility(View.GONE);

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.GONE);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(R.string.gotit);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
            }
        });
        dialog.show();

    }

}
