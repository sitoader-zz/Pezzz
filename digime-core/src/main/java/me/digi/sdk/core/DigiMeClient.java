/*
 * Copyright (c) 2009-2018 digi.me Limited. All rights reserved.
 */

package me.digi.sdk.core;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import com.google.gson.JsonElement;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

import me.digi.sdk.core.config.ApiConfig;
import me.digi.sdk.core.entities.CAAccounts;
import me.digi.sdk.core.entities.CAFileResponse;
import me.digi.sdk.core.entities.CAFiles;
import me.digi.sdk.core.internal.AuthorizationException;
import me.digi.sdk.core.internal.Util;
import me.digi.sdk.core.provider.KeyLoaderProvider;
import me.digi.sdk.core.session.CASession;
import me.digi.sdk.core.session.CASessionManager;
import me.digi.sdk.core.session.SessionManager;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;


@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "SameParameterValue", "UnusedReturnValue", "StaticFieldLeak", "WeakerAccess"})
public final class DigiMeClient {
    static final String TAG = "DigiMeCore";

    private static volatile DigiMeClient singleton;

    private static volatile Executor coreExecutor;
    private static volatile String applicationId;
    private static volatile String applicationName;
    private static volatile String[] contractIds;

    private static final boolean debugEnabled = BuildConfig.DEBUG;

    /**
     *   Connection timeout in seconds
     */
    public static int globalConnectTimeout = 25;

    /**
     *   Connection read/write IO timeout in seconds
     */
    public static int globalReadWriteTimeout = 30;

    /**
     *   Controls retries globally
     */
    public static boolean retryOnFail = true;

    /**
     *   Minimal delay to retry failed request
     */
    public static long minRetryPeriod = 500;

    /**
     *   Minimal delay to retry failed request
     */
    public static boolean retryWithExponentialBackoff = true;

    /**
     *   Maximum number of times to retry before failing. 0 uses per call defaults, >0 sets a global hard limit.
     */
    public static int maxRetryCount = 0;

    private static Context appContext;
    private static final Object SYNC = new Object();
    private static KeyLoaderProvider loaderProvider;

    //Predefined <meta-data> paths where the sdk looks for necessary items
    private static final String APPLICATION_ID_PATH = "me.digi.sdk.AppId";
    private static final String APPLICATION_NAME_PATH = "me.digi.sdk.AppName";
    private static final String CONSENT_ACCESS_CONTRACTS_PATH = "me.digi.sdk.Contracts";

    private static CASession defaultSession;
    private final List<SDKListener> listeners = new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<CASession, DigiMeAPIClient> networkClients;
    private volatile CertificatePinner certificatePinner;
    private volatile DigiMeAuthorizationManager authManager;

    private SessionManager<CASession> consentAccessSessionManager;

    public final Flow<CAContract> flow;

    private DigiMeClient() {
        this.networkClients = new ConcurrentHashMap<>();

        this.flow = new Flow<>(new FlowLookupInitializer<CAContract>() {
            @Override
            public CAContract create(String identifier) {
                return new CAContract(identifier, DigiMeClient.getApplicationId());
            }
        });
    }

    private static Boolean clientInitialized = false;

    public static synchronized void init(
            final Context appContext) {
        if (clientInitialized) {
            return;
        }

        if (appContext == null) {
            throw new IllegalArgumentException("appContext can not be null.");
        }

        DigiMeClient.appContext = appContext.getApplicationContext();
        DigiMeClient.updatePropertiesFromMetadata(DigiMeClient.appContext);
        if ((applicationId == null) || (applicationId.length() == 0)) {
            throw new DigiMeException("Valid application ID must be set in manifest or by calling setApplicationId.");
        }

        clientInitialized = true;
        getInstance().onStart();
        defaultSession = new CASession("default", 0, "default", null);

        //Check if core app available
        FutureTask<Void> backgroundStartup =
                new FutureTask<>(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        getInstance().getCertificatePinner();

                        return null;
                    }
                });
        getCoreExecutor().execute(backgroundStartup);
    }

    public static Executor getCoreExecutor() {
        synchronized (SYNC) {
            if (DigiMeClient.coreExecutor == null) {
                DigiMeClient.coreExecutor = AsyncTask.THREAD_POOL_EXECUTOR;
            }
        }
        return DigiMeClient.coreExecutor;
    }
    

    public static void checkClientInitialized() {
        if (!DigiMeClient.isClientInitialized()) {
            throw new DigiMeClientException("DigiMe Core Client has not been properly initialized. You need to call DigiMeClient.init().");
        }
    }

    public static synchronized boolean isClientInitialized() {
        return clientInitialized;
    }

    public static Context getApplicationContext() {
        checkClientInitialized();
        return appContext;
    }

    @SuppressWarnings("SameReturnValue")
    public static String getVersion() {
        return DigiMeSDKVersion.VERSION;
    }

    public static String getApplicationId() {
        checkClientInitialized();
        return applicationId;
    }

    public static String getApplicationName() {
        checkClientInitialized();
        return applicationName;
    }

    public static void setApplicationName(String applicationName) {
        DigiMeClient.applicationName = applicationName;
    }

    public static KeyLoaderProvider getDefaultKeyLoader() {
        checkClientInitialized();
        return loaderProvider;
    }

    private void onStart(){
        consentAccessSessionManager = new CASessionManager();
    }

    private synchronized void createCertificatePinner() {
        if (certificatePinner == null) {
            this.certificatePinner = new CertificatePinner.Builder()
                    .add(ApiConfig.get().getHost(), "sha256/wKlzaShrDcjVp9ctFYJHFSJaNXLtUYqwhQBiNn+iaHU=") //new unec
                    .add(ApiConfig.get().getHost(), "sha256/3i4O332aSRETnPQnzdMQr3zv4ajufFW6bywiCxRLWDw=")
                    .add(ApiConfig.get().getHost(), "sha256/dJtgu1DIYCnEB2vznevQ8hj9ADPRHzIN4pVG/xqP1DI=")
                    .add(ApiConfig.get().getHost(), "sha256/wpsB0loL9mSlGQZTWRQtWcIL0S5Wsu6rc85ToklfkDE=")
                    .add(ApiConfig.get().getHost(), "sha256/L/ZH1QCgUbk0OG8ePmvLnsTxUnjCzizynPQIw3iWxVo=")
                    .add(ApiConfig.get().getHost(), "sha256/YxigD5mXO/e2a+8fJrBBd/nDqFhwWfcZynp6EatNsso=") //integration
                    .add(ApiConfig.get().getHost(), "sha256/HC6oU3LGzhkwHionuDaZacaIbjwYaMT/Qc7bxWLyy8g=") //prod 1
                    .add(ApiConfig.get().getHost(), "sha256/2qix+QNHzGWG5nhEFNIMxPZ57YbgT0liSisVLERNzt8=") //prod 2
                    .add(ApiConfig.get().getHost(), "sha256/W8QTLPG35cP39gFmUjKLLKAlHrYmGxvHf5Zf+INBZzo=") //prod 3
                    .add(ApiConfig.get().getHost(), "sha256/3Q5tS8ejLixxAC+UORUXfDdXpg76r113b2/MAQoWI84=") //enc
                    .add(ApiConfig.get().getHost(), "sha256/+/QBUOjekzuaexmKgLE0F6h38yChLNA6WQWukjgeHhU=") //sandbox
                    .add(ApiConfig.get().getHost(), "sha256/f5dj/05YHJjWrNpmpHobLJSLh0EWp/TSgXHJPlYsJYY=") //alpha
                    .build();
        }
    }

    /*
     *  DigiMeClient instance methods
     */

    public static DigiMeClient getInstance() {
        checkClientInitialized();
        if (singleton == null) {
            synchronized (DigiMeClient.class) {
                singleton = new DigiMeClient();
            }
        }
        return singleton;
    }

    public CertificatePinner getCertificatePinner() {
        checkClientInitialized();
        if (certificatePinner == null) {
            createCertificatePinner();
        }
        return certificatePinner;
    }

    public SessionManager<CASession> getSessionManager() {
        checkClientInitialized();
        return consentAccessSessionManager;
    }

    public void addListener(@NonNull final SDKListener listener) {
        if (!listeners.contains(listener))
            listeners.add(listener);
    }

    public boolean removeListener(@NonNull final SDKListener listener) {
        return this.listeners.remove(listener);
    }

    public DigiMeAuthorizationManager getAuthManager() {
        if (authManager == null) {
            synchronized (DigiMeClient.class) {
                if (authManager == null) {
                    authManager = new DigiMeAuthorizationManager();
                }
            }
        }
        return authManager;
    }

    /**
     *  Public methods
     */

    public DigiMeAuthorizationManager authorize(@NonNull Activity activity, @Nullable SDKCallback<CASession> callback) {
        checkClientInitialized();
        SDKCallback<CASession> forwarder = new AutoSessionForwardCallback(activity, callback);
        getAuthManager().resolveAuthorizationPath(activity, forwarder, false);
        return getAuthManager();
    }

    @Deprecated
    public void createSession(@Nullable SDKCallback<CASession>callback) throws DigiMeException {
        if (!flow.isInitialized()) {
            throw new DigiMeException("No contracts registered! You must have forgotten to add contract Id to the meta-data path \"%s\" or pass the CAContract object to createSession.", CONSENT_ACCESS_CONTRACTS_PATH);
        }
        if (!flow.next()) { flow.rewind().next(); }
        createSession(flow.currentId, callback);
    }

    public void createSession(@NonNull String contractId, @Nullable SDKCallback<CASession>callback) {
        boolean useFlow = false;
        CAContract contract;
        if (flow.isInitialized()) {
            useFlow = flow.stepTo(contractId);
        }
        if (useFlow) {
            contract = flow.get();
        } else {
            if (Util.validateContractId(contractId) && DigiMeClient.debugEnabled) {
                throw new DigiMeException("Provided contractId has invalid format.");
            }
            contract = new CAContract(contractId, DigiMeClient.getApplicationId());
        }
        startSessionWithContract(contract, callback);
    }

    public void startSessionWithContract(CAContract contract, @Nullable SDKCallback<CASession> callback) {
        checkClientInitialized();
        DigiMeAPIClient client = getDefaultApi();
        SessionForwardCallback dispatchCallback;
        if (callback instanceof AutoSessionForwardCallback) {
            dispatchCallback = (AutoSessionForwardCallback) callback;
        } else {
            dispatchCallback = new SessionForwardCallback(callback);
        }
        if (contract == null) {
            dispatchCallback.failed(new SDKValidationException("Contract is null. Session can not be initialized!"));
        }
        client.sessionService().getSessionToken(contract).enqueue(dispatchCallback);
    }

    public void getFileList(@Nullable SDKCallback<CAFiles> callback) {
        getFileListWithSession(getSessionManager().getCurrentSession(), callback);
    }

    public void getFileListWithSession(CASession session, @Nullable SDKCallback<CAFiles> callback) {
        checkClientInitialized();
        ContentForwardCallback<CAFiles> proxy = new ContentForwardCallback<>(callback, null, CAFiles.class);
        if (!validateSession(session, proxy)) return;
        //noinspection ConstantConditions
        getApi().consentAccessService().list(session.sessionKey)
                .enqueue(proxy);
    }

    public void getAccounts(@Nullable SDKCallback<CAAccounts> callback) {
        getAccountsWithSession(getSessionManager().getCurrentSession(), callback);
    }

    public void getAccountsWithSession(CASession session, @Nullable SDKCallback<CAAccounts> callback) {
        checkClientInitialized();
        ContentForwardCallback<CAAccounts> proxy = new ContentForwardCallback<>(callback, null, CAAccounts.class);
        if (!validateSession(session, proxy)) return;
        //noinspection ConstantConditions
        getApi().consentAccessService().accounts(session.sessionKey)
                .enqueue(proxy);
    }

    public void getFileContent(String fileId, @Nullable SDKCallback<CAFileResponse> callback) {
        getFileContentWithSession(fileId, getSessionManager().getCurrentSession(), callback);
    }

    public void getFileContentWithSession(String fileId, CASession session, @Nullable SDKCallback<CAFileResponse> callback) {
        checkClientInitialized();
        ContentForwardCallback<CAFileResponse> proxy = new ContentForwardCallback<>(callback, fileId, CAFileResponse.class);
        if (!validateSession(session, proxy)) return;
        if (fileId == null) {
            throw new IllegalArgumentException("File ID can not be null.");
        }
        //noinspection ConstantConditions
        getApi().consentAccessService().data(session.sessionKey, fileId)
                .enqueue(proxy);
    }

    public void getFileJSON(String fileId, @Nullable SDKCallback<JsonElement> callback) {
        getFileJSONWithSession(fileId, getSessionManager().getCurrentSession(), callback);
    }

    public void getFileJSONWithSession(String fileId, CASession session, @Nullable SDKCallback<JsonElement> callback) {
        checkClientInitialized();
        ContentForwardCallback<JsonElement> proxy = new ContentForwardCallback<>(callback, fileId, JsonElement.class);
        if (!validateSession(session, proxy)) return;
        if (fileId == null) {
            throw new IllegalArgumentException("File ID can not be null.");
        }
        //noinspection ConstantConditions
        getApi().consentAccessService().dataRaw(session.sessionKey, fileId)
                .enqueue(proxy);
    }

    public DigiMeAPIClient getDefaultApi() {
        return getApi(defaultSession);
    }

    public DigiMeAPIClient getApi() {
        checkClientInitialized();
        final CASession session = consentAccessSessionManager.getCurrentSession();
        if (session == null) {
            return null;
        }
        return getApi(session);
    }

    public DigiMeAPIClient getApi(CASession session) {
        checkClientInitialized();
        if (!networkClients.containsKey(session)) {
            networkClients.putIfAbsent(session, new DigiMeAPIClient());
        }
        return networkClients.get(session);
    }

    public DigiMeAPIClient addCustomClient(OkHttpClient client) {
        checkClientInitialized();
        final CASession session = consentAccessSessionManager.getCurrentSession();
        if (session == null) {
            return null;
        }
        return addCustomClient(session, client, null);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    public DigiMeAPIClient addCustomClient(CASession session, OkHttpClient client, ApiConfig apiConfig) {
        checkClientInitialized();
        DigiMeAPIClient apiClient;
        ApiConfig realConfig = apiConfig == null ? ApiConfig.get() : apiConfig;
        if (client == null) {
            apiClient = new DigiMeAPIClient();
        } else {
            apiClient = new DigiMeAPIClient(client, realConfig);
        }
        return networkClients.put(session, apiClient);
    }

    /**
     *  Private helpers
     */

    private void authorizeInitializedSessionWithManager(DigiMeAuthorizationManager authManager, @NonNull Activity activity, @Nullable SDKCallback<CASession> callback) {
        if (authManager == null) {
            throw new IllegalArgumentException("Authorization Manager can not be null.");
        }
        SDKCallback<CASession> forwarder = (callback != null && callback instanceof AuthorizationForwardCallback) ? callback : new AuthorizationForwardCallback(callback);
        if (!authManager.nativeClientAvailable(activity)) {
            forwarder = new AutoSessionForwardCallback(activity, callback, true);
        }
        authManager.resolveAuthorizationPath(activity, forwarder, true);
    }

    private static void updatePropertiesFromMetadata(Context context) {
        if (context == null) {
            return;
        }
        ApplicationInfo ai;
        try {
            ai = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }

        if (ai == null || ai.metaData == null) {
            return;
        }
        if (applicationId == null) {
            Object appId = ai.metaData.get(APPLICATION_ID_PATH);
            if (appId instanceof String) {
                applicationId = (String) appId;
            } else if (appId instanceof Integer) {
                throw new DigiMeException(
                        "App ID must be placed in the strings manifest file");
            }
        }

        if (applicationName == null) {
            applicationName = ai.metaData.getString(APPLICATION_NAME_PATH);
        }

        if (contractIds == null) {
            Object contract = ai.metaData.get(CONSENT_ACCESS_CONTRACTS_PATH);
            if (contract instanceof String) {
                String cont = (String) contract;
                contractIds = new String[]{cont};
            } else if (contract instanceof Integer) {
                String type = context.getResources().getResourceTypeName((int) contract);
                if (type.equalsIgnoreCase("array")) {
                    contractIds = context.getResources().getStringArray((int)contract);
                } else if (type.equalsIgnoreCase("string")) {
                    String cnt = context.getResources().getString((int)contract);
                    contractIds = new String[]{cnt};
                } else {
                    throw new DigiMeException(
                            "Allowed types for contract ID are only string-array or string. Check that you have set the correct meta-data type.");
                }
            }
        }

        if (loaderProvider == null) {
            loaderProvider = new KeyLoaderProvider(ai.metaData, context);
        }
    }

    private boolean validateSession(SDKCallback callback) {
        boolean valid = false;
        if (getSessionManager().getCurrentSession() != null && getSessionManager().getCurrentSession().isValid()) {
            valid = true;
        }
        if (!valid && callback != null) {
            callback.failed(new SDKValidationException("Current session is null or invalid", SDKValidationException.SESSION_VALIDATION_ERROR));
        }
        return valid;
    }

    private boolean validateSession(CASession session, SDKCallback callback) throws IllegalArgumentException {
        boolean valid = false;
        if (session == null) {
            if (callback == null) {
                throw new IllegalArgumentException("Session can not be null.");
            } else {
                callback.failed(new SDKValidationException("Current session is null", SDKValidationException.SESSION_VALIDATION_ERROR));
            }
        } else if (session.isValid()) {
            valid = true;
        }
        if (!valid && callback != null) {
            callback.failed(new SDKValidationException("Current session is invalid", SDKValidationException.SESSION_VALIDATION_ERROR));
        }
        return valid;
    }

    /**
     *  Iterator for pre-registered CAContract flow
     *
     */

    abstract class FlowLookupInitializer<T> {

        public abstract T create(String identifier);
    }

    public static final class Flow<T> {
        static final int START_MARKER = Integer.MAX_VALUE;

        private String currentId;
        private int currentStep;
        private final ArrayList<String> identifiers;
        private final ConcurrentHashMap<String, T> lookup;

        private Flow() {
            this.lookup = new ConcurrentHashMap<>();
            if (DigiMeClient.contractIds == null || DigiMeClient.contractIds.length == 0) {
                this.identifiers = new ArrayList<>();
            } else {
                this.identifiers = new ArrayList<>(Arrays.asList(DigiMeClient.contractIds));
            }
            tryInit();
        }

        private Flow(FlowLookupInitializer<T> initializer) {
            this();
            if (this.isInitialized()) {
                for (String id :
                        identifiers) {
                    this.lookup.putIfAbsent(id, initializer.create(id));
                }
            }
        }

        private void tryInit() {
            if (identifiers == null) {
                currentStep = -1;
                currentId = null;
            } else if (identifiers.size() == 0) {
                currentStep = -1;
                currentId = null;
            } else {
                currentStep = START_MARKER;
                currentId = null;
            }
        }

        public int getCurrentStep() {
            return currentStep;
        }

        public String getCurrentId() {
            return currentId;
        }

        public boolean isInitialized() {
            return !(currentStep < 0 || (currentStep != START_MARKER && currentId == null));
        }

        public boolean next() {
            if (identifiers == null) { return false; }
            if (currentStep == START_MARKER) { currentStep = -1; }
            if (currentStep + 1 >= identifiers.size()) { return false; }
            currentStep++;
            currentId = identifiers.get(currentStep);

            return true;
        }

        public T get() {
            if (!isInitialized()) { return null; }
            return lookup.get(currentId);
        }

        public boolean stepTo(String identifier) {
            if (identifier == null) { return false; }
            if (identifier.equals(currentId)) { return true; }
            if (lookup.containsKey(identifier)) {
                int index = identifiers.indexOf(identifier);
                if (index >= 0) {
                    currentId = identifier;
                    currentStep = index;
                    return true;
                }
            }
            return false;
        }

        public Flow rewind() {
            tryInit();
            return this;
        }
    }

    /**
     *  Callback wrappers
     */


    class SessionForwardCallback extends SDKCallback<CASession> {
        final SDKCallback<CASession> callback;
        private boolean overrideSessionCallback = false;

        SessionForwardCallback(SDKCallback<CASession> callback) {
            this.callback = callback;
        }
        SessionForwardCallback(SDKCallback<CASession> callback, boolean overrideSessionCallback) {
            this.callback = callback;
            this.overrideSessionCallback = overrideSessionCallback;
        }

        @Override
        public void succeeded(SDKResponse<CASession> result) {
            final CASession session = result.body;
            if (session == null) {
                callback.failed(new SDKException("Session create returned an empty session!"));
                return;
            }
            CASessionManager sm = (CASessionManager)consentAccessSessionManager;
            sm.setCurrentSession(session);
            getInstance().getApi(session);
            if (callback != null && !overrideSessionCallback) {
                callback.succeeded(new SDKResponse<>(session, result.response));
            }
            for (SDKListener listener : listeners) {
                listener.sessionCreated(session);
            }
        }

        @Override
        public void failed(SDKException exception) {
            if (callback != null) {
                callback.failed(exception);
            }
            for (SDKListener listener : listeners) {
                listener.sessionCreateFailed(exception);
            }
        }
    }

    private class AutoSessionForwardCallback extends SessionForwardCallback {
        private final WeakReference<Activity> callActivity;

        AutoSessionForwardCallback(Activity activity, SDKCallback<CASession> callback) {
            super(callback);
            callActivity = new WeakReference<>(activity);
        }

        AutoSessionForwardCallback(Activity activity, SDKCallback<CASession> callback, boolean overrideCallback) {
            super(callback, overrideCallback);
            callActivity = new WeakReference<>(activity);
        }
        @Override
        public void succeeded(SDKResponse<CASession> result) {
            super.succeeded(result);
            if (callActivity.get() != null) {
                authorizeInitializedSessionWithManager(getAuthManager(), callActivity.get(), callback);
            }
        }
        @Override
        public void failed(SDKException exception) {
            super.failed(exception);
        }
    }

    private class AuthorizationForwardCallback extends SDKCallback<CASession> {
        private final SDKCallback<CASession> callback;

        AuthorizationForwardCallback(SDKCallback<CASession> callback) {
            this.callback = callback;
        }

        @Override
        public void succeeded(SDKResponse<CASession> result) {
            if (callback != null) {
                callback.succeeded(result);
            }
            for (SDKListener listener : listeners) {
                listener.authorizeSucceeded(result.body);
            }
        }

        @Override
        public void failed(SDKException exception) {
            if (exception instanceof AuthorizationException) {
                determineReason((AuthorizationException) exception);
            } else if (callback != null) {
                callback.failed(exception);
            }
        }

        private void determineReason(AuthorizationException exception) {
            AuthorizationException.Reason reason = exception.getThrowReason();

            if (callback != null && reason != AuthorizationException.Reason.WRONG_CODE) {
                callback.failed(exception);
            }
            for (SDKListener listener : listeners) {
                switch (reason) {
                    case ACCESS_DENIED:
                        listener.authorizeDenied(exception);
                        break;
                    case WRONG_CODE:
                    case IN_PROGRESS:
                        listener.authorizeFailedWithWrongRequestCode();
                        break;
                }
            }
        }
    }

    private class ContentForwardCallback<T> extends SDKCallback<T> {
        final SDKCallback<T> callback;
        final String reserved;
        private final Class<T> type;

        ContentForwardCallback(SDKCallback<T> callback, Class<T> type) {
            this(callback, null, type);
        }

        ContentForwardCallback(SDKCallback<T> callback, String additionalData, Class<T> type) {
            this.callback = callback;
            this.reserved = additionalData;
            this.type = type;
        }

        @Override
        public void succeeded(SDKResponse<T> result) {
            if (callback != null) {
                callback.succeeded(result);
            }
            T returnedObject = result.body;

            for (SDKListener listener : listeners) {
                if (returnedObject instanceof CAFiles) {
                    listener.clientRetrievedFileList((CAFiles) returnedObject);
                } else if (returnedObject instanceof CAFileResponse) {
                    listener.contentRetrievedForFile(reserved, (CAFileResponse) returnedObject);
                } else if (returnedObject instanceof JsonElement) {
                    listener.jsonRetrievedForFile(reserved, (JsonElement) returnedObject);
                } else if (returnedObject instanceof CAAccounts) {
                    listener.accountsRetrieved((CAAccounts) returnedObject);
                }
            }
        }

        @Override
        public void failed(SDKException exception) {
            if (callback != null) {
                callback.failed(exception);
            }
            for (SDKListener listener : listeners) {
                if (type.equals(CAFiles.class)) {
                    listener.clientFailedOnFileList(exception);
                } else if (type.equals(CAFileResponse.class)) {
                    listener.contentRetrieveFailed(reserved, exception);
                } else if (type.equals(JsonElement.class)) {
                    listener.contentRetrieveFailed(reserved, exception);
                } else if (type.equals(CAAccounts.class)) {
                    listener.accountsRetrieveFailed(exception);
                }
            }
        }
    }
}
