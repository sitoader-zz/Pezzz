/*
 * Copyright (c) 2009-2018 digi.me Limited. All rights reserved.
 */

package me.digi.sdk.core.internal;

import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.stream.JsonReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

import me.digi.sdk.core.config.ApiConfig;
import me.digi.sdk.core.entities.ErrorResponse;
import me.digi.sdk.crypto.CACryptoProvider;
import me.digi.sdk.crypto.CAKeyStore;
import me.digi.sdk.crypto.DGMCryptoFailureException;
import me.digi.sdk.crypto.FailureCause;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class CAContentCryptoInterceptor implements Interceptor {
    private final Gson gson = new Gson();
    private CACryptoProvider cryptoProvider;
    private boolean cryptoInitialized;

    private static final String CONTENT_KEY = "fileContent";

    public CAContentCryptoInterceptor(CAKeyStore providerKeys) {
        cryptoInitialized = !providerKeys.isEmpty();
        cryptoProvider = new CACryptoProvider(providerKeys);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());

        if (response.isSuccessful() && EncryptedPaths.shouldDecrypt(chain.request().url())) {
            if (response.body() == null || !cryptoInitialized) {
                return response;
            }
            //noinspection ConstantConditions
            LinkedTreeMap<String, Object> parsedMap = extractFileContent(response.peekBody(response.body().contentLength()).byteStream());
            if (!isEncryptedField(parsedMap)) {
                return response;
            }
            String newBody = null;
            try {
                newBody = extractEncryptedString(parsedMap, EncryptedPaths.isAccountsPath(response.request().url()));
            } catch (DGMCryptoFailureException dge) {
                return mapError("Decryption failure", "Failed to decrypt content", 411, response);
            }
            if (newBody == null) return response;

            String wantedContentType = response.header("Content-Type");
            if (TextUtils.isEmpty(wantedContentType)) {
                wantedContentType = "application/json";
            }
            return response.newBuilder()
                    .body(ResponseBody.create(MediaType.parse(wantedContentType), newBody))
                    .build();
        }
        return response;
    }

    /**
     *  Returns a custom error response so it can be correctly mapped and de-serialized to an exception in the client
     *  Primarily used to communicate decryption failure (since original response would throw a different exception)
     */
    private Response mapError(@NonNull String responseMessage, @NonNull String errorMessage, int code, @NonNull Response originalResponse) {
        if (code < 400) return originalResponse;
        ErrorResponse error = new ErrorResponse(responseMessage, errorMessage, "", code);
        return originalResponse.newBuilder()
                .code(code)
                .message(responseMessage)
                .body(ResponseBody.create(MediaType.parse("application/json"), gson.toJson(error, ErrorResponse.class)))
                .header("Content-Type", "application/json")
                .build();
    }

    private LinkedTreeMap<String, Object> extractFileContent(InputStream in) {
        return gson.fromJson(new JsonReader(new InputStreamReader(in)), Object.class);
    }

    private boolean isEncryptedField(LinkedTreeMap treeMap) {
        return treeMap.get(CONTENT_KEY) != null && (treeMap.get(CONTENT_KEY) instanceof String);
    }

    private String extractEncryptedString(LinkedTreeMap<String, Object> parsedMap, boolean stripFileContent) throws DGMCryptoFailureException {
        String decrypted;
        try {
            //We can assume the check has already passed at the call site
            String fileContent = (String) parsedMap.get(CONTENT_KEY);
            decrypted = cryptoProvider.decryptStream(new ByteArrayInputStream(fileContent.getBytes("UTF-8")));
        } catch (IOException | DGMCryptoFailureException | NullPointerException ex) {
            decrypted = null;
            throw new DGMCryptoFailureException(FailureCause.RSA_DECRYPTION_FAILURE);
        }
        if (TextUtils.isEmpty(decrypted)) {
            return null;
        }

        String returnJson;
        try {
            if (!stripFileContent) {
                parsedMap.put("fileContent", gson.fromJson(decrypted, JsonElement.class));
                returnJson = gson.toJson(parsedMap);
            } else {
                returnJson = decrypted;
            }
        } catch (Exception ex) {
            return null;
        }

        return returnJson;
    }


    private static class EncryptedPaths {
        private static final String[] whitelist = {"v1/permission-access/query/_any_/_any_"};
        private static final ApiConfig thisApi = ApiConfig.get();
        private static final String ANY_MATCHER = "_any_";
        private static final String ACCOUNT_FILE = "accounts.json";

        static boolean shouldDecrypt(HttpUrl url) {
            boolean match = false;
            for (String pattern: whitelist) {
                HttpUrl template = HttpUrl.parse(thisApi.getUrl() + pattern);
                if (template == null || template.pathSegments().size() != url.pathSegments().size()) {
                    continue;
                }
                for (int i = 0; i < url.pathSegments().size(); i++) {
                    String templateSegment = template.pathSegments().get(i);
                    match = templateSegment.equals(ANY_MATCHER) ||
                            templateSegment.equals(url.pathSegments().get(i));
                    if (!match) break;
                }
                if (match) break;
            }
            return match;
        }

        static boolean isAccountsPath(HttpUrl url) {
            List<String> segments = url.pathSegments();
            return segments.get(segments.size() - 1).endsWith(ACCOUNT_FILE);
        }
    }
}
