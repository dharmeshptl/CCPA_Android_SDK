package com.sourcepoint.ccpa_cmplibrary;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.CountDownTimer;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;


import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.HashSet;

/**
 * Entry point class encapsulating the Consents a giving user has given to one or several vendors.
 * It offers methods to get custom vendors consents.
 * <pre>{@code
 *
 * }
 * </pre>
 */
public class CCPAConsentLib {

    @SuppressWarnings("WeakerAccess")
    public static final String CONSENT_UUID_KEY = "sp.ccpa.consentUUID";

    private  final StoreClient storeClient;

    private final String pmId;

    private final String PM_BASE_URL = "https://ccpa-inapp-pm.sp-prod.net";

    private final String CCPA_ORIGIN = "https://ccpa-service.sp-prod.net";

    private String metaData;

    public enum DebugLevel {DEBUG, OFF}

    public enum MESSAGE_OPTIONS {
        REJECT_ALL,
        ACCEPT_ALL,
        SAVE_AND_EXIT,
        MSG_CANCEL,
        PM_DISMISS,
        SHOW_PRIVACY_MANAGER,
        UNKNOWN
    }

    public String consentUUID;
    public Boolean ccpaApplies;

    /**
     * After the user has chosen an option in the WebView, this attribute will contain an integer
     * indicating what was that choice.
     */
    @SuppressWarnings("WeakerAccess")
    public MESSAGE_OPTIONS choiceType = null;

    public ConsentLibException error = null;

    public UserConsent userConsent;

    private static final String TAG = "CCPAConsentLib";

    private Activity activity;
    private final String property;
    private final int accountId, propertyId;
    private final ViewGroup viewGroup;
    private Callback onAction, onConsentReady, onError, onConsentUIReady, onConsentUIFinished;

    private final boolean weOwnTheView;

    //default time out changes
    private boolean onMessageReadyCalled = false;
    private long defaultMessageTimeOut;

    private CountDownTimer mCountDownTimer = null;

    private final SourcePointClient sourcePoint;

    private ConnectivityManager connectivityManager;

    @SuppressWarnings("WeakerAccess")
    public ConsentWebView webView;

    public interface Callback {
        void run(CCPAConsentLib c);
    }

    public interface OnLoadComplete {
        void onSuccess(Object result);

        default void onFailure(ConsentLibException exception) {
            Log.d(TAG, "default implementation of onFailure, did you forget to override onFailure ?");
            exception.printStackTrace();
        }
    }

    public class ActionTypes {
        public static final int SHOW_PM = 12;
        public static final int MSG_REJECT = 13;
        public static final int MSG_ACCEPT = 11;
        public static final int DISMISS = 15;
        public static final int PM_COMPLETE = 1;
    }

    /**
     * @return a new instance of CCPAConsentLib.Builder
     */
    public static ConsentLibBuilder newBuilder(Integer accountId, String property, Integer propertyId, String pmId , Activity activity) {
        return new ConsentLibBuilder(accountId, property, propertyId, pmId, activity);
    }

    CCPAConsentLib(ConsentLibBuilder b) {
        activity = b.activity;
        property = b.property;
        accountId = b.accountId;
        propertyId = b.propertyId;
        pmId = b.pmId;
        onAction = b.onAction;
        onConsentReady = b.onConsentReady;

        onError = b.onError;
        onConsentUIReady = b.onConsentUIReady;
        onConsentUIFinished = b.onConsentUIFinished;
        viewGroup = b.viewGroup;

        connectivityManager = b.getConnectivityMenager();

        weOwnTheView = viewGroup != null;
        // configurable time out
        defaultMessageTimeOut = b.defaultMessageTimeOut;

        storeClient = new StoreClient(PreferenceManager.getDefaultSharedPreferences(activity));
        sourcePoint = new SourcePointClient(b.accountId, b.property + "/" + b.page, propertyId, b.stagingCampaign, b.targetingParamsString, b.authId);

        webView = buildWebView();

        setConsentData(b.authId);
    }

    void setConsentData(String newAuthId){

        if(didConsentUserChange(newAuthId, storeClient.getAuthId())) storeClient.clearAllData();

        metaData = storeClient.getMetaData();

        consentUUID = storeClient.getConsentUUID();

        ccpaApplies = storeClient.getCcpaApplies();

        storeClient.setAuthId(newAuthId);

        try {
           userConsent =  storeClient.getUserConsent();
        } catch (ConsentLibException e) {
            onErrorTask(e);
        }
    }

    private boolean didConsentUserChange(String newAuthId, String oldAuthId){
        return oldAuthId != null && newAuthId != null && !newAuthId.equals(oldAuthId);
    }

    private ConsentWebView buildWebView() {
        return new ConsentWebView(activity) {

            @Override
            public void onMessageReady() {
                Log.d("msgReady", "called");
                if (mCountDownTimer != null) mCountDownTimer.cancel();
                if(!onMessageReadyCalled) {
                    runOnLiveActivityUIThread(() -> CCPAConsentLib.this.onConsentUIReady.run(CCPAConsentLib.this));
                    onMessageReadyCalled = true;
                }
                displayWebViewIfNeeded();
            }

            @Override
            public void onError(ConsentLibException e) {
                ConsentLibException exception = hasLostInternetConnection() ?
                        new ConsentLibException.NoInternetConnectionException() : e;
                onErrorTask(exception);
            }

            @Override
            public void onSavePM(UserConsent u) {
                CCPAConsentLib.this.choiceType = MESSAGE_OPTIONS.SAVE_AND_EXIT;
                CCPAConsentLib.this.userConsent = u;
                CCPAConsentLib.this.onAction.run(CCPAConsentLib.this);
                try {
                    sendConsent(ActionTypes.PM_COMPLETE);
                } catch (Exception e) {
                    onErrorTask(e);
                }
            }

            @Override
            public void onAction(int choiceType) {
                try{
                    switch (choiceType) {
                        case ActionTypes.SHOW_PM:
                            CCPAConsentLib.this.choiceType = MESSAGE_OPTIONS.SHOW_PRIVACY_MANAGER;
                            onShowPm();
                            break;
                        case ActionTypes.MSG_ACCEPT:
                            CCPAConsentLib.this.choiceType = MESSAGE_OPTIONS.ACCEPT_ALL;
                            onMsgAccepted();
                            break;
                        case ActionTypes.DISMISS:
                            CCPAConsentLib.this.choiceType = MESSAGE_OPTIONS.MSG_CANCEL;
                            onDismiss();
                            break;
                        case ActionTypes.MSG_REJECT:
                            CCPAConsentLib.this.choiceType = MESSAGE_OPTIONS.REJECT_ALL;
                            onMsgRejected();
                            break;
                        default:
                            CCPAConsentLib.this.choiceType = MESSAGE_OPTIONS.UNKNOWN;
                            break;
                    }
                    CCPAConsentLib.this.onAction.run(CCPAConsentLib.this);
                }catch (UnsupportedEncodingException e) {
                    onErrorTask(e);
                } catch (JSONException e) {
                    onErrorTask(e);
                }catch (ConsentLibException e){
                    onError(e);
                }
            }
        };
    }

    private void onMsgAccepted() throws UnsupportedEncodingException, JSONException, ConsentLibException {
        userConsent = new UserConsent(UserConsent.ConsentStatus.consentedAll);
        sendConsent(ActionTypes.MSG_ACCEPT);
    }

    private void onDismiss(){
        webView.post(new Runnable() {
            @Override
            public void run() {
                if (webView.canGoBack()) webView.goBack();
                else finish();
            }
        });
    }

    private void onMsgRejected() throws UnsupportedEncodingException, JSONException, ConsentLibException {
        userConsent = new UserConsent(UserConsent.ConsentStatus.rejectedAll);
        sendConsent(ActionTypes.MSG_REJECT);
    }

    private void onShowPm() throws ConsentLibException{
        loadConsentUI(pmUrl());
    }

    /**
     * Communicates with SourcePoint to load the message. It all happens in the background and the WebView
     * will only show after the message is ready to be displayed (received data from SourcePoint).
     *
     * @throws ConsentLibException.NoInternetConnectionException - thrown if the device has lost connection either prior or while interacting with CCPAConsentLib
     */
    public void run() {
        try {
        onMessageReadyCalled = false;
        mCountDownTimer = getTimer(defaultMessageTimeOut);
        mCountDownTimer.start();
            renderMsgAndSaveConsent();
        } catch (Exception e) {
            onErrorTask(e);
        }
    }

    public void showPm() {
        try {
            mCountDownTimer = getTimer(defaultMessageTimeOut);
            mCountDownTimer.start();
            loadConsentUI(pmUrl());
        } catch (ConsentLibException e) {
            onErrorTask(e);
        }
    }

    private void loadConsentUI(String url)throws ConsentLibException{
        if (hasLostInternetConnection())
            throw new ConsentLibException.NoInternetConnectionException();

            runOnLiveActivityUIThread(() -> {
                if (webView == null) {
                    webView = buildWebView();
                }
                webView.loadConsentMsgFromUrl(url);
            });
    }

    private void renderMsgAndSaveConsent() throws ConsentLibException {
        if (hasLostInternetConnection())
            throw new ConsentLibException.NoInternetConnectionException();

            sourcePoint.getMessage(consentUUID, metaData, new OnLoadComplete() {
                @Override
                public void onSuccess(Object result) {
                    try {
                        JSONObject jsonResult = new JSONObject((String) result);
                        consentUUID = jsonResult.getString("uuid");
                        metaData = jsonResult.getString("meta");
                        ccpaApplies = jsonResult.getBoolean("ccpaApplies");
                        userConsent = new UserConsent(jsonResult.getJSONObject("userConsent"));
                        if (jsonResult.has("url")) {
                            loadConsentUI(jsonResult.getString("url"));
                        } else {
                            finish();
                        }
                    }
                    //TODO call onFailure callbacks / throw consentlibException
                    catch (JSONException e) {
                        onErrorTask(e);
                    } catch (ConsentLibException e) {
                        onErrorTask(e);
                    }
                }

                @Override
                public void onFailure(ConsentLibException e) {
                    onErrorTask(e);
                }
            });
    }

    private JSONObject paramsToSendConsent() throws JSONException {
        JSONObject params = new JSONObject();

        params.put("consents", userConsent.jsonConsents);
        params.put("accountId", accountId);
        params.put("propertyId", propertyId);
        params.put("privacyManagerId", pmId);
        params.put("uuid", consentUUID);
        params.put("meta", metaData);
        return params;
    }

    private void sendConsent(int actionType) throws JSONException, UnsupportedEncodingException, ConsentLibException {
        if (hasLostInternetConnection())
            throw new ConsentLibException.NoInternetConnectionException();

            sourcePoint.sendConsent(actionType, paramsToSendConsent(), new OnLoadComplete() {
                @Override
                public void onSuccess(Object result) {
                    try {
                        JSONObject jsonResult = new JSONObject((String) result);
                        ccpaApplies = jsonResult.getBoolean("ccpaApplies");
                        userConsent = new UserConsent(jsonResult.getJSONObject("userConsent"));
                        consentUUID = jsonResult.getString("uuid");
                        metaData = jsonResult.getString("meta");
                        finish();
                    } catch (Exception e) {
                        onErrorTask(e);
                    }
                }

                @Override
                public void onFailure(ConsentLibException e) {
                    onErrorTask(e);
                }
            });
    }

    boolean hasLostInternetConnection() {
        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        return activeNetwork == null || !activeNetwork.isConnectedOrConnecting();
    }

    private CountDownTimer getTimer(long defaultMessageTimeOut) {
        return new CountDownTimer(defaultMessageTimeOut, defaultMessageTimeOut) {
            @Override
            public void onTick(long millisUntilFinished) {     }
            @Override
            public void onFinish() {
                if (!onMessageReadyCalled) {
                    onErrorTask(new ConsentLibException("a timeout has occurred when loading the message"));
                }
            }
        };
    }

    private String pmUrl(){
        HashSet<String> params = new HashSet<>();
        params.add("privacy_manager_id=" + pmId);
        params.add("site_id=" + propertyId);
        params.add("ccpa_origin=" + CCPA_ORIGIN);
        if(consentUUID != null) params.add("ccpaUUID=" + consentUUID);

        return PM_BASE_URL + "?" + TextUtils.join("&", params);
    }

    private void runOnLiveActivityUIThread(Runnable uiRunnable) {
        if (activity != null && !activity.isFinishing()) {
            activity.runOnUiThread(uiRunnable);
        }
    }

    private void displayWebViewIfNeeded() {
        if (weOwnTheView) {
            runOnLiveActivityUIThread(() -> {
                if (webView != null) {
                    if (webView.getParent() != null) {
                        ((ViewGroup) webView.getParent()).removeView(webView);
                    }
                    webView.display();
                    viewGroup.addView(webView);
                }
            });
        }
    }

    private void removeWebViewIfNeeded() {
        if (weOwnTheView && activity != null) destroy();
    }

    private void onErrorTask(Exception e){
        this.error = new ConsentLibException(e);
        cancelCounter();
        runOnLiveActivityUIThread(() -> {
            CCPAConsentLib.this.onConsentUIFinished.run(CCPAConsentLib.this);
            CCPAConsentLib.this.onError.run(CCPAConsentLib.this);
            resetCallbacks();
        });
    }

    private void resetCallbacks(){
        onAction = onError = onConsentUIFinished = onConsentReady = onConsentUIReady = c -> {};
    }

    private void cancelCounter(){
        if (mCountDownTimer != null) mCountDownTimer.cancel();
    }


    void storeData(){
        storeClient.setConsentUuid(consentUUID);
        storeClient.setMetaData(metaData);
        storeClient.setUserConsents(userConsent);
        storeClient.setCcpaApplies(ccpaApplies);
        storeClient.setConsentString(userConsent.consentString);
    }

    private boolean isViewPresented(View v) {
        return v != null && v.getParent() != null;
    }

    private void finish() {
        storeData();
        Log.i("uuid", consentUUID);
        if(isViewPresented(webView)) runOnLiveActivityUIThread(() -> CCPAConsentLib.this.onConsentUIFinished.run(CCPAConsentLib.this));
        runOnLiveActivityUIThread(() -> {
            removeWebViewIfNeeded();
            if(userConsent != null) onConsentReady.run(CCPAConsentLib.this);
            activity = null; // release reference to activity
        });
    }

    public void destroy() {
        if (mCountDownTimer != null) mCountDownTimer.cancel();
        if (webView != null) {
            if (viewGroup != null) {
                viewGroup.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
    }

    public void clearConsentData(){
        storeClient.clearAllData();
    }
}
