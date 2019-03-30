/*
 * Copyright (c) 2009-2018 digi.me Limited. All rights reserved.
 */

package me.digi.sdk.core.internal.ipc;

import android.app.Activity;

import me.digi.sdk.core.DigiMeAuthorizationManager;
import me.digi.sdk.core.DigiMeClient;
import me.digi.sdk.core.SDKCallback;
import me.digi.sdk.core.session.CASession;

public class DigiMeDirectResolver implements AuthorizationResolver {
    private boolean shouldOverride = false;

    @Override
    public void resolveAuthFlow(DigiMeAuthorizationManager authManager, Activity activity, SDKCallback<CASession> authCallback) {
        if (!shouldOverride) {
            DigiMeClient.getInstance().createSession(authCallback);
        } else {
            authManager.beginAuthorization(activity, authCallback);
        }
    }

    @Override
    public void clientResolved(SDKCallback<CASession> authCallback) { }

    @Override
    public void stop() { }

    @Override
    public void overrideSessionCreation(boolean shouldOverride) {
        this.shouldOverride = shouldOverride;
    }
}
