package com.rjfun.cordova.facebookads;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.UUID;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PluginResult.Status;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnTouchListener;
import android.widget.RelativeLayout;

import com.facebook.ads.*;
import com.rjfun.cordova.ad.GenericAdPlugin;
import com.facebook.ads.NativeAdBase.Image;
import com.facebook.ads.NativeAdBase.Rating;
import com.facebook.ads.NativeAdListener;

public class FacebookAdPlugin extends GenericAdPlugin {
    private static final String LOGTAG = "FacebookAds";

    private static final String TEST_BANNER_ID = "726719434140206_777151452430337";
    private static final String TEST_INTERSTITIAL_ID = "726719434140206_777151589096990";
    private static final String TEST_NATIVEAD_ID = "726719434140206_777151705763645";
    private static final String TEST_REWARDVIDEO_ID = "726719434140206_777151452430337";


    private boolean mIsRewardedVideoLoading = false;
    private final Object mLock = new Object();

    private AdSize adSize;

    private static final String OPT_DEVICE_HASH = "deviceHash";
    private String deviceHash = "";

    public static final String ACTION_CREATE_NATIVEAD = "createNativeAd";
    public static final String ACTION_REMOVE_NATIVEAD = "removeNativeAd";
    public static final String ACTION_SET_NATIVEAD_CLICKAREA = "setNativeAdClickArea";

    private RelativeLayout layout;

    public class FlexNativeAd {
        public String adId;
        public int x, y, w, h;
        public NativeAd ad;
        public View view;
        public View tracking;
    };

    private HashMap<String, FlexNativeAd> nativeAds = new HashMap<String, FlexNativeAd>();

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();
        AudienceNetworkAds.initialize(getActivity());

        this.adSize = __AdSizeFromString("SMART_BANNER");
    }

    protected AdSize __AdSizeFromString(String str) {
        AdSize sz;
        if ("BANNER".equals(str)) {
            sz = AdSize.BANNER_320_50;
            // other size not supported by facebook audience network: FULL_BANNER, MEDIUM_RECTANGLE, LEADERBOARD, SKYSCRAPER
            //} else if ("SMART_BANNER".equals(str)) {
        } else {
            sz = isTablet() ? AdSize.BANNER_HEIGHT_90 : AdSize.BANNER_HEIGHT_50;
        }

        return sz;
    }

    public boolean isTablet() {
        Configuration conf = getActivity().getResources().getConfiguration();
        boolean xlarge = ((conf.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) == 4);
        boolean large = ((conf.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_LARGE);
        return (xlarge || large);
    }

    @Override
    protected String __getProductShortName() {
        return "FacebookAds";
    }

    @Override
    protected String __getTestBannerId() {
        return TEST_BANNER_ID;
    }

    @Override
    protected String __getTestInterstitialId() {
        return TEST_INTERSTITIAL_ID;
    }

    protected String __getTestNativeAdId() {
        return TEST_NATIVEAD_ID;
    }

    @Override
    public boolean execute(String action, JSONArray inputs, CallbackContext callbackContext) throws JSONException {
        Log.d("execute", "action-----:" + action);
        PluginResult result = null;

        if (ACTION_CREATE_NATIVEAD.equals(action)) {
            String adid = inputs.optString(0);
            if (this.testTraffic) adid = this.__getTestNativeAdId();
            this.createNativeAd(adid);
            result = new PluginResult(Status.OK);

        } else if (ACTION_REMOVE_NATIVEAD.equals(action)) {
            String adid = inputs.optString(0);
            this.removeNativeAd(adid);
            result = new PluginResult(Status.OK);

        } else if (ACTION_SET_NATIVEAD_CLICKAREA.equals(action)) {
            String adid = inputs.optString(0);
            int x = inputs.optInt(1);
            int y = inputs.optInt(2);
            int w = inputs.optInt(3);
            int h = inputs.optInt(4);
            this.setNativeAdClickArea(adid, x, y, w, h);
            result = new PluginResult(Status.OK);

        } else {
            return super.execute(action, inputs, callbackContext);
        }

        if (result != null) sendPluginResult(result, callbackContext);

        return true;
    }

    public void createNativeAd(final String adId) {
        Log.d(LOGTAG, "createNativeAd: " + adId);
        final Activity activity = getActivity();
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (nativeAds.containsKey(adId)) {
                    removeNativeAd(adId);
                }

                if (layout == null) {
                    layout = new RelativeLayout(getActivity());
                    RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.MATCH_PARENT,
                            RelativeLayout.LayoutParams.MATCH_PARENT);
                    ViewGroup parentView = (ViewGroup) getView().getRootView();
                    parentView.addView(layout, params);
                }

                FlexNativeAd unit = new FlexNativeAd();
                unit.adId = adId;
                unit.x = unit.y = 0;
                unit.w = unit.h = 4;

                unit.view = new View(getActivity());
                unit.tracking = new View(getActivity());
                layout.addView(unit.tracking, new RelativeLayout.LayoutParams(unit.w, unit.h));
                layout.addView(unit.view, new RelativeLayout.LayoutParams(unit.w, unit.h));
                if (isTesting) {
                    unit.tracking.setBackgroundColor(0x30FF0000);
                    unit.view.setBackgroundColor(0x3000FF00);
                }

                // pass scroll event in tracking view to webview to improve UX
                final View webV = getView();
                final View trackingV = unit.tracking;
                final View touchV = unit.view;
                OnTouchListener t = new OnTouchListener() {
                    public float mTapX = 0, mTapY = 0;

                    @Override
                    public boolean onTouch(View v, MotionEvent evt) {
                        switch (evt.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                mTapX = evt.getX();
                                mTapY = evt.getY();
                                break;

                            case MotionEvent.ACTION_UP:
                                boolean clicked = (Math.abs(evt.getX() - mTapX) + Math.abs(evt.getY() - mTapY) < 10);
                                mTapX = 0;
                                mTapY = 0;
                                if (clicked) {
                                    evt.setAction(MotionEvent.ACTION_DOWN);
                                    trackingV.dispatchTouchEvent(evt);
                                    evt.setAction(MotionEvent.ACTION_UP);
                                    return trackingV.dispatchTouchEvent(evt);
                                }
                                break;
                        }

                        // adjust touch event location to web view
                        int offsetWebV[] = {0, 0}, offsetTouchView[] = {0, 0};
                        touchV.getLocationOnScreen(offsetTouchView);
                        webV.getLocationOnScreen(offsetWebV);
                        evt.offsetLocation(offsetTouchView[0] - offsetWebV[0], offsetTouchView[1] - offsetWebV[1]);

                        return webV.dispatchTouchEvent(evt);
                    }
                };
                unit.view.setOnTouchListener(t);

                unit.ad = new NativeAd(getActivity(), adId);
                unit.ad.setAdListener(new NativeAdListener() {
                    @Override
                    public void onError(Ad ad, AdError error) {
                        fireAdErrorEvent(EVENT_AD_FAILLOAD, error.getErrorCode(), error.getErrorMessage(), ADTYPE_NATIVE);
                    }

                    @Override
                    public void onAdLoaded(Ad ad) {
                        fireNativeAdLoadEvent(ad);
                    }

                    @Override
                    public void onAdClicked(Ad ad) {
                        fireAdEvent(EVENT_AD_LEAVEAPP, ADTYPE_NATIVE);
                    }

                    @Override
                    public void onLoggingImpression(Ad ad) {
                        // Ad impression logged callback
                    }


                    @Override
                    public void onMediaDownloaded(Ad ad) {

                    }
                });

                nativeAds.put(adId, unit);

                unit.ad.loadAd();
            }
        });
    }

    public void fireNativeAdLoadEvent(Ad ad) {
        Iterator<String> it = nativeAds.keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            FlexNativeAd unit = nativeAds.get(key);
            if ((unit != null) && (unit.ad == ad)) {
                String jsonData = "{}";
                try {
                    String titleForAd = unit.ad.getAdvertiserName();
                    Image coverImage = unit.ad.getAdCoverImage();
                    Image iconForAd = unit.ad.getAdIcon();
                    String socialContextForAd = unit.ad.getAdSocialContext();
                    String titleForAdButton = unit.ad.getAdCallToAction();
                    String textForAdBody = unit.ad.getAdBodyText();
                    Rating appRatingForAd = unit.ad.getAdStarRating();

                    JSONObject json = new JSONObject();
                    json.put("adNetwork", __getProductShortName());
                    json.put("adEvent", EVENT_AD_LOADED);
                    json.put("adType", ADTYPE_NATIVE);
                    json.put("adId", unit.adId);

                    JSONObject adRes = new JSONObject();
                    adRes.put("title", titleForAd);
                    adRes.put("socialContext", socialContextForAd);
                    adRes.put("buttonText", titleForAdButton);
                    adRes.put("body", textForAdBody);
                    if (appRatingForAd != null) {
                        adRes.put("rating", appRatingForAd.getValue());
                        adRes.put("ratingScale", appRatingForAd.getScale());
                    }

                    JSONObject coverInfo = new JSONObject();
                    if (coverImage != null) {
						coverInfo.put("height", coverImage.getHeight());
						coverInfo.put("width", coverImage.getWidth());
						coverInfo.put("url", coverImage.getUrl());
                    }

                    JSONObject iconInfo = new JSONObject();
                    if (iconForAd != null) {
						iconInfo.put("height", iconForAd.getHeight());
						iconInfo.put("width", iconForAd.getWidth());
						iconInfo.put("url", iconForAd.getUrl());
                    }

                    adRes.put("coverImage", coverInfo);
                    adRes.put("icon", iconInfo);
                    json.put("adRes", adRes);

                    jsonData = json.toString();
                } catch (Exception e) {
                }
                if (unit.ad != null) {
                    unit.ad.unregisterView();
                    unit.ad.registerViewForInteraction(unit.tracking, null);
                }
                fireEvent(__getProductShortName(), EVENT_AD_LOADED, jsonData);
                break;
            }
        }
    }

    public void removeNativeAd(final String adId) {
        final Activity activity = getActivity();
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (nativeAds.containsKey(adId)) {
                    FlexNativeAd unit = nativeAds.remove(adId);
                    if (unit.view != null) {
                        ViewGroup parentView = (ViewGroup) unit.view.getParent();
                        if (parentView != null) {
                            parentView.removeView(unit.view);
                        }
                        unit.view = null;
                    }
                    if (unit.ad != null) {
                        unit.ad.unregisterView();
                        unit.ad.destroy();
                        unit.ad = null;
                    }
                }
            }
        });
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public void setNativeAdClickArea(final String adId, int x, int y, int w, int h) {
        final FlexNativeAd unit = nativeAds.get(adId);
        if (unit != null) {
            DisplayMetrics metrics = cordova.getActivity().getResources().getDisplayMetrics();
            unit.x = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, metrics);
            unit.y = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, y, metrics);
            unit.w = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, w, metrics);
            unit.h = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, h, metrics);

            View rootView = getView().getRootView();
            int offsetRootView[] = {0, 0}, offsetMainView[] = {0, 0};
            rootView.getLocationOnScreen(offsetRootView);
            getView().getLocationOnScreen(offsetMainView);
            unit.x += (offsetMainView[0] - offsetRootView[0]);
            unit.y += (offsetMainView[1] - offsetRootView[1]);

            final Activity activity = getActivity();
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (unit.view != null) {
                        unit.view.setLeft(unit.x);
                        unit.view.setTop(unit.y);
                        unit.view.setRight(unit.x + unit.w);
                        unit.view.setBottom(unit.y + unit.h);
                    }
                    if (unit.tracking != null) {
                        unit.tracking.setLeft(unit.x);
                        unit.tracking.setTop(unit.y);
                        unit.tracking.setRight(unit.x + unit.w);
                        unit.tracking.setBottom(unit.y + unit.h);
                    }
                }
            });
        }
    }

    @Override
    public void setOptions(JSONObject options) {
        super.setOptions(options);

        if (options.has(OPT_AD_SIZE)) {
            this.adSize = __AdSizeFromString(options.optString(OPT_AD_SIZE));
        }

        if (isTesting) {
            testTraffic = true;

            if (options.has(OPT_DEVICE_HASH)) {
                this.deviceHash = options.optString(OPT_DEVICE_HASH);
                Log.d(LOGTAG, "set device hash: " + this.deviceHash);
                AdSettings.addTestDevice(this.deviceHash);
            }

            SharedPreferences adPrefs = getActivity().getSharedPreferences("FBAdPrefs", 0);
            String deviceIdHash = adPrefs.getString("deviceIdHash", (String) null);
            if (deviceIdHash == null) {
                deviceIdHash = this.md5(UUID.randomUUID().toString());
                adPrefs.edit().putString("deviceIdHash", deviceIdHash).commit();
            }
            Log.d(LOGTAG, "auto set device hash: " + this.deviceHash);
            AdSettings.addTestDevice(deviceIdHash);
        }
    }

    @Override
    protected View __createAdView(String adId) {
        if (isTesting) adId = TEST_BANNER_ID;
        AdView ad = new AdView(getActivity(), adId, adSize);
        ad.setAdListener(new AdListener() {
            @Override
            public void onAdClicked(Ad arg0) {
                fireAdEvent(EVENT_AD_LEAVEAPP, ADTYPE_BANNER);
            }

            @Override
            public void onAdLoaded(Ad arg0) {
                if ((!bannerVisible) && autoShowBanner) {
                    showBanner(adPosition, posX, posY);
                }
                fireAdEvent(EVENT_AD_LOADED, ADTYPE_BANNER);
            }

            @Override
            public void onError(Ad arg0, AdError arg1) {
                fireAdErrorEvent(EVENT_AD_FAILLOAD, arg1.getErrorCode(), arg1.getErrorMessage(), ADTYPE_BANNER);
            }

            @Override
            public void onLoggingImpression(Ad ad) {
                // Ad impression logged callback
            }
        });
        return ad;
    }

    @Override
    protected int __getAdViewWidth(View view) {
        if (view instanceof AdView) {
            AdView ad = (AdView) view;
            return ad.getWidth();
        }
        return 320;
    }

    @Override
    protected int __getAdViewHeight(View view) {
        if (view instanceof AdView) {
            AdView ad = (AdView) view;
            return ad.getHeight();
        }
        return 50;
    }

    @Override
    protected void __loadAdView(View view) {
        if (view instanceof AdView) {
            AdView ad = (AdView) view;
            ad.loadAd();
        }
    }

    @Override
    protected void __pauseAdView(View view) {
        if (view instanceof AdView) {
            AdView ad = (AdView) view;
            // ad.pause();
        }
    }

    @Override
    protected void __resumeAdView(View view) {
        if (view instanceof AdView) {
            AdView ad = (AdView) view;
            // ad.resume();
        }
    }

    @Override
    protected void __destroyAdView(View view) {
        if (view instanceof AdView) {
            AdView ad = (AdView) view;
            ad.destroy();
        }
    }

    @Override
    protected Object __createInterstitial(String adId) {
        if (isTesting) adId = TEST_INTERSTITIAL_ID;
        Log.d("FacebookAdPlugin", "__createInterstitial...adId:" + adId + "isTesing" + isTesting);
        InterstitialAd ad = new InterstitialAd(getActivity(), adId);
        ad.setAdListener(new InterstitialAdListener() {
            @Override
            public void onAdClicked(Ad arg0) {
                fireAdEvent(EVENT_AD_LEAVEAPP, ADTYPE_INTERSTITIAL);
            }

            @Override
            public void onAdLoaded(Ad arg0) {
                if (autoShowInterstitial) {
                    showInterstitial();
                }
                fireAdEvent(EVENT_AD_LOADED, ADTYPE_INTERSTITIAL);
            }

            @Override
            public void onError(Ad arg0, AdError arg1) {
                fireAdErrorEvent(EVENT_AD_FAILLOAD, arg1.getErrorCode(), arg1.getErrorMessage(), ADTYPE_INTERSTITIAL);
            }

            @Override
            public void onInterstitialDismissed(Ad arg0) {
                fireAdEvent(EVENT_AD_DISMISS, ADTYPE_INTERSTITIAL);
            }

            @Override
            public void onInterstitialDisplayed(Ad arg0) {
                fireAdEvent(EVENT_AD_PRESENT, ADTYPE_INTERSTITIAL);
            }

            @Override
            public void onLoggingImpression(Ad ad) {
                // Ad impression logged callback
            }
        });
        return ad;
    }

    @Override
    protected void __loadInterstitial(Object interstitial) {
        if (interstitial instanceof InterstitialAd) {
            InterstitialAd ad = (InterstitialAd) interstitial;
            ad.loadAd();
        }
    }

    @Override
    protected void __showInterstitial(Object interstitial) {
        if (interstitial instanceof InterstitialAd) {
            InterstitialAd ad = (InterstitialAd) interstitial;
            ad.show();
        }
    }

    @Override
    protected void __destroyInterstitial(Object interstitial) {
        if (interstitial instanceof InterstitialAd) {
            InterstitialAd ad = (InterstitialAd) interstitial;
            ad.setAdListener(null);
            ad.destroy();
        }
    }


    protected String __getTestRewardVideoId() {
        Log.d("FacebookAdPlugin", "__getTestRewardVideoId.....");
        return "";
    }


    protected Object __prepareRewardVideoAd(String adId) {

        Log.d("FacebookAdPlugin", "__prepareRewardVideoAd.....:" + adId);
// Instantiate a RewardedVideoAd object.
        // NOTE: the placement ID will eventually identify this as your App, you can ignore it for
        // now, while you are testing and replace it later when you have signed up.
        // While you are using this temporary code you will only get test ads and if you release
        // your code like this to the Google Play your users will not receive ads (you will get a no fill error).

//		rewardVideoAd =

        String TAG = "__prepareRewardVideoAd";

        if (isTesting) adId = TEST_REWARDVIDEO_ID;
        Log.d("FacebookAdPlugin", "__prepareRewardVideoAd.....adId:" + adId + " isTesting:" + isTesting);
        RewardedVideoAd rewardedVideoAd = new RewardedVideoAd(getActivity(), adId);
        rewardedVideoAd.setAdListener(new RewardedVideoAdListener() {
            @Override
            public void onError(Ad ad, AdError error) {
                // Rewarded video ad failed to load
                Log.e(TAG, "Rewarded video ad failed to load: " + error.getErrorMessage());
                synchronized (mLock) {
                    mIsRewardedVideoLoading = false;
                }
                rewardVideoAd = null; //<-- Added line before the fireAdEvent

                fireAdErrorEvent(EVENT_AD_FAILLOAD, error.getErrorCode(), error.getErrorMessage(), ADTYPE_REWARDVIDEO);
            }

            @Override
            public void onAdLoaded(Ad ad) {
                // Rewarded video ad is loaded and ready to be displayed
                Log.d(TAG, "Rewarded video ad is loaded and ready to be displayed!");
                synchronized (mLock) {
                    mIsRewardedVideoLoading = false;
                }

                fireAdEvent(EVENT_AD_LOADED, ADTYPE_REWARDVIDEO);

                if (autoShowRewardVideo) {
                    showRewardVideoAd();
                }
            }

            @Override
            public void onAdClicked(Ad ad) {
                // Rewarded video ad clicked
                Log.d(TAG, "Rewarded video ad clicked!");
                fireAdEvent(EVENT_AD_LEAVEAPP, ADTYPE_REWARDVIDEO);
            }

            @Override
            public void onLoggingImpression(Ad ad) {
                // Rewarded Video ad impression - the event will fire when the
                // video starts playing
                Log.d(TAG, "Rewarded video ad impression logged!");
                fireAdEvent(EVENT_AD_WILLPRESENT, ADTYPE_REWARDVIDEO);
            }

            @Override
            public void onRewardedVideoCompleted() {
                // Rewarded Video View Complete - the video has been played to the end.
                // You can use this event to initialize your reward
                Log.d(TAG, "Rewarded video completed!");

                // Call method to give reward
                // giveReward();
//				fireAdEvent(EVENT_AD_WILLDISMISS, ADTYPE_REWARDVIDEO);

                String obj = __getProductShortName();
                String json = String.format("{'adNetwork':'%s','adType':'%s','adEvent':'%s','rewardType':'%s','rewardAmount':%d}",
                        obj, ADTYPE_REWARDVIDEO, EVENT_AD_PRESENT, "", 1);
                fireEvent(obj, EVENT_AD_PRESENT, json);
            }

            @Override
            public void onRewardedVideoClosed() {
                // The Rewarded Video ad was closed - this can occur during the video
                // by closing the app, or closing the end card.
                Log.d(TAG, "Rewarded video ad closed!");
                fireAdEvent(EVENT_AD_DISMISS, ADTYPE_REWARDVIDEO);
            }
        });

        synchronized (mLock) {
            if (!mIsRewardedVideoLoading) {
                mIsRewardedVideoLoading = true;
//				Bundle extras = new Bundle();
//				extras.putBoolean("_noRefresh", true);
//				AdRequest adRequest = new AdRequest.Builder()
//						.addNetworkExtrasBundle(AdMobAdapter.class, extras)
//						.build();
//				ad.loadAd(adId, adRequest);
                rewardedVideoAd.loadAd();
            }
        }

        return rewardedVideoAd;

//		return null;
    }

    protected void __showRewardVideoAd(Object rewardvideo) {
        Log.d("__showRewardVideoAd", "__showRewardVideoAd.....");
        if (rewardvideo == null) return;

        if (rewardvideo instanceof RewardedVideoAd) {
            RewardedVideoAd ad = (RewardedVideoAd) rewardvideo;

            if (!ad.isAdLoaded()) {
                return;
            }
            if (ad.isAdInvalidated()) {
                return;
            }
            ad.show();
        }
    }

    protected void __destroyRewardVideoAd(Object rewardvideo) {
        Log.d("__destroyRewardVideoAd", "__destroyRewardVideoAd.....");
        if (rewardvideo == null) return;
        if (rewardvideo instanceof RewardedVideoAd) {
            RewardedVideoAd ad = (RewardedVideoAd) rewardvideo;
            ad.destroy();

        }

    }
}
