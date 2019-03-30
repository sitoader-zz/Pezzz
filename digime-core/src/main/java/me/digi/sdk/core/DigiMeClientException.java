/*
 * Copyright (c) 2009-2018 digi.me Limited. All rights reserved.
 */

package me.digi.sdk.core;

@SuppressWarnings("SameParameterValue")
public class DigiMeClientException extends DigiMeException {
    public DigiMeClientException() {
        super();
    }

    public DigiMeClientException(String message) {
        super(message);
    }

    public DigiMeClientException(String message, Throwable throwable) {
        super(message, throwable);
    }

    public DigiMeClientException(Throwable throwable) {
        super(throwable);
    }
}
