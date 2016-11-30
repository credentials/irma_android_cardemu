package org.irmacard.cardemu;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.util.Log;

import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;
import org.irmacard.cardemu.httpclient.JsonHttpClient;
import org.irmacard.cardemu.store.KeyshareIntroDialog;
import org.irmacard.cardemu.store.StoreManager;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.info.SchemeManager;
import org.irmacard.keyshare.common.UserLoginMessage;
import org.irmacard.keyshare.common.UserMessage;

import java.io.IOException;


public class SchemeManagerHandler {
    // If the activity is paused or killed, then it can't getEnrollInput any feedback; we keep track of this here
    private boolean cancelFeedback = false;

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

    public static void enrollCloudServer(final Activity activity,
                                         final String schemeManager,
                                         final String url,
                                         final String email,
                                         final String pin) {
        final JsonHttpClient client = new JsonHttpClient(GsonUtil.getGson());
        UserLoginMessage loginMessage = new UserLoginMessage(email, null, pin);

        client.post(UserMessage.class, url + "/web/users/selfenroll", loginMessage, new HttpResultHandler<UserMessage>() {
            @Override public void onSuccess(UserMessage result) {
                CredentialManager.addKeyshareServer(schemeManager, new KeyshareServer(url, email));
                showSuccess(activity, "Successfully installed", "Succesfully linked against keyshare server.");
            }

            @Override public void onError(HttpClientException e) {
                // Don't keep the scheme manager if we didn't manage to enroll to its cloud server
                DescriptionStore.getInstance().removeSchemeManager(schemeManager);
                showError(activity,
                        activity.getString(R.string.downloading_schememanager_failed_title),
                        activity.getString(R.string.downloading_schememanager_failed_text, e.getMessage()));
            }
        });
    }

    public void confirmAndDownloadManager(final String url, final Activity activity, final Runnable runnable) {
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
                    SchemeManager manager = new SchemeManager(
                            DescriptionStore.doHttpRequest(url + "/description.xml"));
                    publishProgress(manager);
                    return null;
                } catch (IOException|InfoException e) {
                    e.printStackTrace();
                    return e;
                }
            }

            @Override protected void onProgressUpdate(final SchemeManager... values) {
                final SchemeManager manager = values[0];

                new AlertDialog.Builder(activity)
                        .setTitle(R.string.add_schememanager_title)
                        .setMessage(activity.getString(
                                R.string.add_schememanager_question, manager.getHumanReadableName()))
                        .setNegativeButton(android.R.string.no, null)
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                downloadManager(manager, activity, runnable);
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

    private static void showSuccess(Activity activity, String title, String message) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Exception e) {
            /* ignore */
        }
    }

    public void setCancelFeedback(boolean cancelFeedback) {
        this.cancelFeedback = cancelFeedback;
    }

    private void downloadManager(final SchemeManager manager, final Activity activity,
                                 final Runnable runnable) {
        cancelFeedback = false; // If we're here, the activity is still alive so that it can getEnrollInput feedback

        if (manager.getKeyshareServer().length() > 0) {
            KeyshareIntroDialog.getEnrollInput(activity, new KeyshareIntroDialog.KeyshareIntroDialogHandler() {
                @Override
                public void done(String email, String pin) {
                    downloadManager(manager, activity, runnable, email, pin);
                }
            });
        } else {
            downloadManager(manager, activity, runnable, null, null);
        }
    }

    private void downloadManager(final SchemeManager manager, final Activity activity,
                                 final Runnable runnable, final String email, final String pin) {
        final ProgressDialog progressDialog = new ProgressDialog(activity);
        progressDialog.setMessage(activity.getString(R.string.downloading));
        progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        progressDialog.setIndeterminate(true);
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();

        StoreManager.downloadSchemeManager(manager.getUrl(), new StoreManager.DownloadHandler() {
            @Override public void onSuccess() {
                if (manager.getKeyshareServer().length() > 0)
                    enrollCloudServer(activity, manager.getName(), manager.getKeyshareServer(), email, pin);

                if (cancelFeedback)
                    return;

                progressDialog.dismiss();
                if (runnable != null)
                    runnable.run();
            }
            @Override public void onError(Exception e) {
                if (cancelFeedback)
                    return;

                progressDialog.cancel();
                showError(activity,
                        activity.getString(R.string.downloading_schememanager_failed_title),
                        activity.getString(R.string.downloading_schememanager_failed_text, e.getMessage())
                );
            }
        });
    }
}
