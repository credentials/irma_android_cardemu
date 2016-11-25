package org.irmacard.cardemu;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.Log;

import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.store.StoreManager;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.SchemeManager;


public class SchemeManagerHandler {
    // If the activity is paused or killed, then it can't show any feedback; we keep track of this here
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

    public void confirmAndDownloadManager(final String url, final Context context, final Runnable runnable) {
        if (DescriptionStore.getInstance().containsSchemeManager(url)) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.schememanager_known_title)
                    .setMessage(R.string.schememanager_known_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.add_schememanager_title) // TODO probably have this dialog be more informative
                .setMessage(context.getString(R.string.add_schememanager_question, url))
                .setNegativeButton(android.R.string.no, null)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        downloadManager(url, context, runnable);
                    }
                })
                .show();
    }

    public void setCancelFeedback(boolean cancelFeedback) {
        this.cancelFeedback = cancelFeedback;
    }

    private void downloadManager(String url, final Context context, final Runnable runnable) {
        cancelFeedback = false; // If we're here, the activity is still alive so that it can show feedback

        final ProgressDialog progressDialog = new ProgressDialog(context);
        progressDialog.setMessage(context.getString(R.string.downloading));
        progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        progressDialog.setIndeterminate(true);
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();

        StoreManager.downloadSchemeManager(url, new StoreManager.DownloadHandler() {
            @Override public void onSuccess() {
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
                new AlertDialog.Builder(context)
                        .setTitle(R.string.downloading_schememanager_failed_title)
                        .setMessage(context.getString(R.string.downloading_schememanager_failed_text, e.getMessage()))
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }
}
