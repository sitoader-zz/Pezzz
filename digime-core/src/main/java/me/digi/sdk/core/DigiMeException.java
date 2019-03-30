/*
 * Copyright (c) 2009-2018 digi.me Limited. All rights reserved.
 */

package me.digi.sdk.core;

@SuppressWarnings("SameParameterValue")
public class DigiMeException extends RuntimeException {

    DigiMeException() {
        super();
    }

    DigiMeException(String msg) {
        super(msg);
    }

    DigiMeException(String format, Object... args) {
        this(String.format(format, args));
    }

    DigiMeException(String message, Throwable throwable) {
        super(message, throwable);
    }

    DigiMeException(Throwable throwable) {
        super(throwable);
    }

    @Override
    public String toString() {
        return getMessage();
    }
}
