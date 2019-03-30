/*
 * Copyright (c) 2009-2018 digi.me Limited. All rights reserved.
 */

package me.digi.sdk.core.session;

public interface SessionListener {

    void sessionCreated(final Session session);

    void sessionDestroyed(final Session session, DestroyedReason reason);

    void currentSessionChanged(final Session oldSession, final Session newSession);

    enum DestroyedReason {
        TIMEOUT,
        INVALIDATED
    }
}
