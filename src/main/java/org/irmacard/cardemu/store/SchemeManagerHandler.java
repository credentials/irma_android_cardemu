package org.irmacard.cardemu.store;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.BuildConfig;
import org.irmacard.cardemu.IRMApp;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;
import org.irmacard.cardemu.httpclient.JsonHttpClient;
import org.irmacard.cardemu.pindialog.EnterPINDialogFragment;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.info.SchemeManager;
import org.irmacard.keyshare.common.UserLoginMessage;
import org.irmacard.keyshare.common.UserMessage;

import java.io.IOException;

import de.henku.jpaillier.KeyPair;

/**
 * Contains static methods for adding and removing scheme managers and keyshare servers,
 * see e.g. {@link #confirmAndDownloadManager(String, Activity, Runnable)}. (For issuers, keys and
 * credential types, see {@link StoreManager}.)
 */
public class SchemeManagerHandler {
    public interface KeyserverInputHandler {
        void done(String email, String pin);
    }

    public static void confirmAndDeleteManager(final SchemeManager manager,
                                               final Context context,
                                               final Runnable runnable) {
        new AlertDialog.Builder(context)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(R.string.remove_schememanager_title)
                .setMessage(R.string.remove_schememanager_question)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        Log.i("SchemePrefs", "Deleting scheme manager " + manager.getName());
                        IRMApp.getStoreManager().removeSchemeManager(manager.getName());
                        if (runnable != null)
                            runnable.run();
                    }
                })
                .show();
    }

    /**
     * Enroll to the keyshare server at the specified url for the specified scheme manager.
     * @param email Emailadress to use
     * @param pin Pincode to use
     * @param activity Used for showing any error messages
     * @param runnable Run afterwards if successful
     */
    public static void enrollKeyshareServer(final String schemeManager,
                                            final String url,
                                            final String email,
                                            final String pin,
                                            final Activity activity,
                                            final Runnable runnable) {
        final JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());
        final KeyPair keyPair = CredentialManager.getNewKeyshareKeypair();
        UserLoginMessage loginMessage = new UserLoginMessage(email, null, pin, keyPair.getPublicKey());

        client.post(UserMessage.class, url + "/web/users/selfenroll", loginMessage, new HttpResultHandler<UserMessage>() {
            @Override public void onSuccess(UserMessage result) {
                CredentialManager.addKeyshareServer(schemeManager, new KeyshareServer(url, email, keyPair));
                if (runnable != null)
                    runnable.run();
            }

            @Override public void onError(HttpClientException e) {
                showError(activity,
                        activity.getString(R.string.downloading_schememanager_failed_title),
                        activity.getString(R.string.downloading_schememanager_failed_text, e.getMessage()));
            }
        });
    }

    /**
     * Starting point for scheme manager installation:
     * Downloads the scheme manager description at the specified url, parse it, ask the user if she
     * consents to installing it, and if so, proceed to
     * {@link #installManagerAndKeyshareServer(SchemeManager, Activity, Runnable)}.
     * @param url Where to find the scheme manager
     * @param activity For dialogs
     * @param runnable Run afterwards
     */
    public static void confirmAndDownloadManager(final String url,
                                                 final Activity activity,
                                                 final Runnable runnable) {
        if (DescriptionStore.getInstance().containsSchemeManager(url)) {
            showError(activity,
                    activity.getString(R.string.schememanager_known_title),
                    activity.getString(R.string.schememanager_known_text)
            );
            return;
        }

        new AsyncTask<Void,SchemeManager,Exception>() {
            @Override protected Exception doInBackground(Void... params) {
                try {
                    SchemeManager manager = DescriptionStore.getInstance()
                            .downloadSchemeManager(url, BuildConfig.DEBUG);

                    // We use onProgressUpdate() for success, and onPostExecute() for errors.
                    publishProgress(manager);
                    return null;
                } catch (IOException|InfoException e) {
                    e.printStackTrace();
                    return e;
                }
            }

            /** Use the just successfully downloaded manager to ask the user if she consents to installing it */
            @Override protected void onProgressUpdate(final SchemeManager... values) {
                final SchemeManager manager = values[0];

                new AlertDialog.Builder(activity)
                        .setTitle(R.string.add_schememanager_title)
                        .setMessage(activity.getString(
                                R.string.add_schememanager_question, manager.getHumanReadableName()))
                        .setNegativeButton(android.R.string.no, null)
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                installManagerAndKeyshareServer(manager, activity, runnable);
                            }
                        })
                        .show();
            }

            @Override protected void onPostExecute(Exception e) {
                if (e == null)
                    return;
                showError(activity,
                        activity.getString(R.string.downloading_schememanager_failed_title),
                        activity.getString(R.string.downloading_schememanager_failed_text, e.getMessage())
                );
            }
        }.execute();
    }

    private static void showError(Activity activity, String title, String message) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Exception e) {
            /* ignore */
        }
    }

    /**
     * If the scheme manager has no keyshare server, then install it immediately. Else: <br/>
     * - ask the user for her email address and pin <br/>
     * - use that to enroll to the keyshare server <br/>
     * - if success, then install the scheme manager.
     * @param manager Manager to install
     * @param activity Activity for showing dialogs
     * @param runnable Will be run afterwards in case of success
     */
    private static void installManagerAndKeyshareServer(final SchemeManager manager,
                                                        final Activity activity,
                                                        final Runnable runnable) {
        // Will be run either immediately, or after successful keyshare server enrolling
        final Runnable wrappedRunnable = new Runnable() {
            @Override public void run() {
                installManager(manager);
                if (runnable != null)
                    runnable.run();
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.scheme_manager_added_title)
                        .setMessage(activity.getString(
                                R.string.scheme_manager_added_text, manager.getHumanReadableName()))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        };

        if (manager.hasKeyshareServer() // Perhaps we are still enrolled from a previous time we knew this manager
                && !CredentialManager.isEnrolledToKeyshareServer(manager.getName())) {
            getKeyserverEnrollInput(activity, new KeyserverInputHandler() {
                @Override public void done(String email, String pin) {
                    enrollKeyshareServer(manager.getName(), manager.getKeyshareServer(),
                        email, pin, activity, wrappedRunnable);
                }
            });
        } else {
            wrappedRunnable.run();
        }
    }

    /**
     * Add the given scheme manager to the description store.
     */
    private static void installManager(final SchemeManager manager) {
        try {
            DescriptionStore.getInstance().addAndSaveSchemeManager(manager);
        } catch (InfoException e) {
            e.printStackTrace();
        }
    }

    /**
     * Get an email address and pin to enroll to a keyshare server (which is not done here).
     * If the user is already enrolled to a keyshare server, we reuse the emailaddress from that
     * server, and ask the user only for a pin (which we verify at the server). Otherwise, we also
     * ask her emailaddress. The specified handler is called afterwards (not if the user cancels
     * or if an error occurs).
     */
    @SuppressLint("InflateParams")
    public static void getKeyserverEnrollInput(Activity activity, final KeyserverInputHandler handler) {
        if (CredentialManager.getKeyshareUsername().length() > 0) {
            EnterPINDialogFragment.verifyPin(
                    CredentialManager.getAnyKeyshareServer(), activity,
                    new EnterPINDialogFragment.PINDialogListener() {
                        @Override public void onPinEntered(String pincode) {
                            handler.done(CredentialManager.getKeyshareUsername(), pincode);
                        }
                        @Override public void onPinCancelled() { /* ignore */ }
                        @Override public void onPinError() { /* ignore */ }
                    }
            );
        }

        else {
            LayoutInflater inflater = activity.getLayoutInflater();
            View view = inflater.inflate(R.layout.dialog_keyshare_enroll, null);

            final EditText emailView = (EditText) view.findViewById(R.id.emailaddress);
            final EditText pinView = (EditText) view.findViewById(R.id.pincode);

            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setView(view)
                    .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            String email = emailView.getText().toString();
                            String pin = pinView.getText().toString();

                            CredentialManager.setKeyshareUsername(email);
                            handler.done(email, pin);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        }
    }
}
