package org.irmacard.cardemu.store;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import org.irmacard.cardemu.R;

public class KeyshareIntroDialog {
    public interface KeyshareIntroDialogHandler {
        void done(String email, String pin);
    }

    @SuppressLint("InflateParams")
    public static void getEnrollInput(Activity activity, final KeyshareIntroDialogHandler handler) {
        LayoutInflater inflater = activity.getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_keyshare_enroll, null);

        final EditText emailView = (EditText) view.findViewById(R.id.emailaddress);
        final EditText pinView = (EditText) view.findViewById(R.id.pincode);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(false)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String email = emailView.getText().toString();
                        String pin = pinView.getText().toString();

                        CredentialManager.setKeyshareUsername(email);
                        handler.done(email, pin);
                    }
                })
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    public static char[] toCharArray(String pin) {
        char[] arr = new char[pin.length()];
        char[] pinchars = pin.toCharArray();
        for (int i = 0; i < pin.length(); ++i)
            arr[i] = (char) (pinchars[i] - '0');
        return arr;
    }
}
