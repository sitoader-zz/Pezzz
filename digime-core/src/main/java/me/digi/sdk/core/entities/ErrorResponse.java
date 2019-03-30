/*
 * Copyright (c) 2009-2018 digi.me Limited. All rights reserved.
 */

package me.digi.sdk.core.entities;

import com.google.gson.annotations.SerializedName;

public class ErrorResponse extends ServerError {
    public class ErrorResponseWrapper {
        @SerializedName("code")
        public String code;

        @SerializedName("message")
        public String message;

        @SerializedName("reference")
        public String reference;
    }

    @SerializedName("error")
    public ErrorResponseWrapper cause;

    public ErrorResponse(String code, String message, String reference, int responseCode) {
        this.cause = new ErrorResponseWrapper();
        this.cause.code = code;
        this.cause.message = message;
        this.cause.reference = reference;
        this.setCode(responseCode);
    }

    @Override
    public String errorCode() {
        return cause.code;
    }

    @Override
    public String message() {
        return cause.message;
    }

    @Override
    public String reference() {
        return cause.reference;
    }
}
