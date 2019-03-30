/*
 * Copyright (c) 2009-2018 digi.me Limited. All rights reserved.
 */

package me.digi.examples.ca;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.gson.JsonElement;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import me.digi.sdk.core.entities.CAAccounts;
import me.digi.sdk.core.session.CASession;
import me.digi.sdk.core.DigiMeClient;
import me.digi.sdk.core.SDKException;
import me.digi.sdk.core.SDKListener;
import me.digi.sdk.core.entities.CAFileResponse;
import me.digi.sdk.core.entities.CAFiles;
import me.digi.sdk.core.internal.AuthorizationException;

public class MainActivity extends AppCompatActivity implements SDKListener {

    private static final String TAG = "DemoActivity";
    private TextView statusText;
    private Button gotoCallback;
    private TextView downloadedCount;
    private TextView accountInfo;
    private DigiMeClient dgmClient;
    private final AtomicInteger counter = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private int allFiles = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        dgmClient = DigiMeClient.getInstance();

        statusText = findViewById(R.id.status_text);
        accountInfo = findViewById(R.id.accountInfo);
        gotoCallback = findViewById(R.id.go_to_callback);
        gotoCallback.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dgmClient.removeListener(MainActivity.this);
                startActivity(new Intent(MainActivity.this, CallbackActivity.class));
            }
        });
        gotoCallback.setVisibility(View.GONE);
        downloadedCount = findViewById(R.id.counter);

        //Add this activity as a listener to DigiMeClient and start the auth flow
        dgmClient.addListener(this);
        dgmClient.authorize(this, null);

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        dgmClient.getAuthManager().onActivityResult(requestCode, resultCode, data);

    }

    /**
     *
     * SDKListener overrides for DiGiMeClient
     */

    @Override
    public void sessionCreated(CASession session) {
        Log.d(TAG, "Session created with token " + session.getSessionKey());
        statusText.setText(R.string.session_created);
    }

    @Override
    public void sessionCreateFailed(SDKException reason) {
        Log.d(TAG, reason.getMessage());
        gotoCallback.setVisibility(View.VISIBLE);
    }

    @Override
    public void authorizeSucceeded(CASession session) {
        Log.d(TAG, "Session created with token " + session.getSessionKey());
        statusText.setText(R.string.session_authorized);
        DigiMeClient.getInstance().getFileList(null);
    }

    @Override
    public void authorizeDenied(AuthorizationException reason) {
        Log.d(TAG, "Failed to authorize session; Reason " + reason.getThrowReason().name());
        statusText.setText(R.string.auth_declined);
        gotoCallback.setVisibility(View.VISIBLE);
    }

    @Override
    public void authorizeFailedWithWrongRequestCode() {
        Log.d(TAG, "We received a wrong request code while authorization was in progress!");
    }

    @Override
    public void clientRetrievedFileList(CAFiles files) {
        accountInfo.setText(R.string.fetch_accounts);
        //Fetch account metadata
        DigiMeClient.getInstance().getAccounts(null);

        downloadedCount.setText(String.format(Locale.getDefault(), "Downloaded : %d/%d", 0, files.fileIds.size()));
        allFiles = files.fileIds.size();
        for (final String fileId :
                files.fileIds) {
            counter.incrementAndGet();
            //Fetch content for returned file IDs
            DigiMeClient.getInstance().getFileJSON(fileId, null);
        }
        String progress = getResources().getQuantityString(R.plurals.files_retrieved, files.fileIds.size(), files.fileIds.size());
        statusText.setText(progress);
        gotoCallback.setVisibility(View.VISIBLE);
    }

    @Override
    public void clientFailedOnFileList(SDKException reason) {
        Log.d(TAG, "Failed to retrieve file list: " + reason.getMessage());
        gotoCallback.setVisibility(View.VISIBLE);
    }

    @Override
    public void contentRetrievedForFile(String fileId, CAFileResponse content) {
    }

    @Override
    public void jsonRetrievedForFile(String fileId, JsonElement content) {
        Log.d(TAG, content.toString());
        updateCounters();
    }

    @Override
    public void contentRetrieveFailed(String fileId, SDKException reason) {
        Log.d(TAG, "Failed to retrieve file content for file: " + fileId + "; Reason: " + reason);
        failedCount.incrementAndGet();
        updateCounters();
    }

    @Override
    public void accountsRetrieved(CAAccounts accounts) {
        accountInfo.setText(String.format(Locale.getDefault(),"Returning data for %d accounts: %s", accounts.accounts.size(), accounts.getAllServiceNames()));
    }

    @Override
    public void accountsRetrieveFailed(SDKException reason) {
        Log.d(TAG, "Failed to retrieve account details for session. Reason: " + reason);
        accountInfo.setText(R.string.account_fail);
    }

    private void updateCounters() {
        int current = counter.decrementAndGet();
        downloadedCount.setText(String.format(Locale.getDefault(), "Downloaded : %d/%d; Failed: %d", allFiles - current, allFiles, failedCount.get()));
    }
}
