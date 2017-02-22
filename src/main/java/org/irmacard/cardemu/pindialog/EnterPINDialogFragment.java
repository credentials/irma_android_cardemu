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

package org.irmacard.cardemu.pindialog;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import org.irmacard.cardemu.R;
import org.irmacard.cardemu.httpclient.HttpClientException;

public class EnterPINDialogFragment extends DialogFragment {
    private static final String EXTRA_TRIES = "irma_library.EnterPINDialogFragment.tries";

    public interface PINDialogListener {
        void onPinEntered(String pincode);
        void onPinCancelled();
        void onPinError(HttpClientException exception);
        void onPinBlocked(int blockTime);
    }

    private PINDialogListener mListener;
    private int tries;
    private AlertDialog dialog;

    public static EnterPINDialogFragment getInstance(int tries, PINDialogListener listener) {
        EnterPINDialogFragment f = new EnterPINDialogFragment();
        f.mListener = listener;

        Bundle args = new Bundle();
        args.putInt(EXTRA_TRIES, tries);
        f.setArguments(args);

        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // For backward compatibility
        if(getArguments().containsKey(EXTRA_TRIES)) {
            tries = getArguments().getInt(EXTRA_TRIES);
        } else {
            tries = -1;
        }
    }

    private void okOnDialog(View dialogView) {
        EditText et = (EditText)dialogView.findViewById(R.id.pincode);
        String pincodeText = et.getText().toString();
        mListener.onPinEntered(pincodeText);
    }

    private void dismissDialog() {
        dialog.dismiss();
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        // Use the Builder class for convenient dialog construction
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        LayoutInflater inflater = getActivity().getLayoutInflater();
        @SuppressLint("InflateParams") final View dialogView = inflater.inflate(R.layout.dialog_pinentry, null);
        builder.setView(dialogView)
                .setTitle("Enter PIN")
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        okOnDialog(dialogView);
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        mListener.onPinCancelled();
                    }
                });
        // Create the AlertDialog object and return it
        dialog = builder.create();
        // Make sure that the keyboard is always shown and doesn't require an additional touch
        // to focus the TextEdit view.
        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode (WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

        TextView error_field = (TextView) dialogView.findViewById(R.id.enterpin_error);
        EditText pin_field = (EditText) dialogView.findViewById(R.id.pincode);
        pin_field.setOnEditorActionListener(new OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId,
                                          KeyEvent event) {
                if ((event != null && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER))
                        || (actionId == EditorInfo.IME_ACTION_DONE)) {
                    okOnDialog(dialogView);
                    dismissDialog();
                }
                return false;
            }
        });

        if(tries != -1) {
            error_field.setVisibility(View.VISIBLE);
            error_field.setText(getResources().getQuantityString(R.plurals.error_tries_left, tries, tries));
        }

        // prevent cancelling the dialog by pressing outside the bounds
        dialog.setCanceledOnTouchOutside(false);

        pin_field.requestFocus();

        return dialog;
    }
}
