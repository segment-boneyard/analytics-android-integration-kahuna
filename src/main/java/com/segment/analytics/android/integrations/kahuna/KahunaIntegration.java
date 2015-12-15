package com.segment.analytics.android.integrations.kahuna;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import com.kahuna.sdk.EmptyCredentialsException;
import com.kahuna.sdk.IKahuna;
import com.kahuna.sdk.IKahunaUserCredentials;
import com.kahuna.sdk.Kahuna;
import com.segment.analytics.Analytics;
import com.segment.analytics.Traits;
import com.segment.analytics.ValueMap;
import com.segment.analytics.integrations.IdentifyPayload;
import com.segment.analytics.integrations.Integration;
import com.segment.analytics.integrations.Logger;
import com.segment.analytics.integrations.ScreenPayload;
import com.segment.analytics.integrations.TrackPayload;
import com.segment.analytics.internal.Utils;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.kahuna.sdk.KahunaUserCredentials.EMAIL_KEY;
import static com.kahuna.sdk.KahunaUserCredentials.FACEBOOK_KEY;
import static com.kahuna.sdk.KahunaUserCredentials.GOOGLE_PLUS_ID;
import static com.kahuna.sdk.KahunaUserCredentials.INSTALL_TOKEN_KEY;
import static com.kahuna.sdk.KahunaUserCredentials.LINKEDIN_KEY;
import static com.kahuna.sdk.KahunaUserCredentials.TWITTER_KEY;
import static com.kahuna.sdk.KahunaUserCredentials.USERNAME_KEY;
import static com.kahuna.sdk.KahunaUserCredentials.USER_ID_KEY;
import static com.segment.analytics.Analytics.LogLevel.DEBUG;
import static com.segment.analytics.internal.Utils.isNullOrEmpty;
import static com.segment.analytics.internal.Utils.isOnClassPath;
import static com.segment.analytics.internal.Utils.toISO8601Date;

/**
 * Kahuna helps mobile marketers send push notifications and in-app messages.
 *
 * @see <a href="https://www.kahuna.com/">Kahuna</a>
 * @see <a href="https://segment.com/docs/integrations/kahuna/">Kahuna Integration</a>
 * @see <a href="http://app.usekahuna.com/tap/getstarted/android/">Kahuna Android SDK</a>
 */
public class KahunaIntegration extends Integration<Void> {
  static final String CATEGORIES_VIEWED = "Categories Viewed";
  static final String LAST_VIEWED_CATEGORY = "Last Viewed Category";
  static final String LAST_PRODUCT_VIEWED_NAME = "Last Product Viewed Name";
  static final String LAST_PRODUCT_ADDED_TO_CART_NAME = "Last Product Added To Cart Name";
  static final String LAST_PRODUCT_ADDED_TO_CART_CATEGORY = "Last Product Added To Cart Category";
  static final String LAST_PURCHASE_DISCOUNT = "Last Purchase Discount";
  static final int MAX_CATEGORIES_VIEWED_ENTRIES = 50;
  static final String NONE = "None";
  static final String KAHUNA_KEY = "Kahuna";
  static final String SEGMENT_WRAPPER_VERSION = "segment";
  static final Set<String> KAHUNA_CREDENTIALS =
      Utils.newSet(USERNAME_KEY, EMAIL_KEY, FACEBOOK_KEY, TWITTER_KEY, LINKEDIN_KEY,
          INSTALL_TOKEN_KEY, GOOGLE_PLUS_ID);
  private static final String SEGMENT_USER_ID_KEY = "userId";

  boolean trackAllPages;
  final IKahuna kahuna;
  Logger logger;

  public static final Factory FACTORY = new Factory() {
    @Override public Integration<?> create(ValueMap settings, Analytics analytics) {
      if (!isOnClassPath("android.support.v4.app.Fragment")) {
        analytics.getLogger().info("Kahuna requires the support library to be bundled.");
        return null;
      }

      boolean trackAllPages = settings.getBoolean("trackAllPages", false);
      String apiKey = settings.getString("apiKey");
      String pushSenderId = settings.getString("pushSenderId");

      IKahuna kahuna = Kahuna.getInstance();
      Logger logger = analytics.logger(KAHUNA_KEY);

      return new KahunaIntegration(kahuna, logger, analytics.getApplication(), trackAllPages,
          apiKey, pushSenderId);
    }

    @Override public String key() {
      return KAHUNA_KEY;
    }
  };

  public KahunaIntegration(IKahuna kahuna, Logger logger, Context context, boolean trackAllPages,
      String apiKey, String pushSenderId) {
    this.kahuna = kahuna;
    this.logger = logger;
    this.trackAllPages = trackAllPages;

    kahuna.onAppCreate(context, apiKey, pushSenderId);
    kahuna.setHybridSDKVersion(SEGMENT_WRAPPER_VERSION, BuildConfig.VERSION_NAME);
    kahuna.setDebugMode(logger.logLevel.ordinal() >= DEBUG.ordinal());
  }

  @Override public void onActivityStarted(Activity activity) {
    super.onActivityStarted(activity);
    kahuna.start();
  }

  @Override public void onActivityStopped(Activity activity) {
    super.onActivityStopped(activity);
    kahuna.stop();
  }

  @Override public void identify(IdentifyPayload identify) {
    super.identify(identify);

    Traits traits = identify.traits();
    IKahunaUserCredentials credentials = kahuna.createUserCredentials();
    Map<String, String> userAttributes = kahuna.getUserAttributes();
    for (String key : traits.keySet()) {
      if (KAHUNA_CREDENTIALS.contains(key)) {
        // Only set credentials if it is a key recognized by Kahuna.
        credentials.add(key, traits.getString(key));
      } else if (SEGMENT_USER_ID_KEY.equals(key)) {
        credentials.add(USER_ID_KEY, identify.userId());
      } else {
        // Set it as a user attribute otherwise
        Object value = traits.get(key);
        if (value instanceof Date) {
          userAttributes.put(key, toISO8601Date((Date) value));
        } else {
          userAttributes.put(key, String.valueOf(value));
        }
      }
    }
    try {
      kahuna.login(credentials);
    } catch (EmptyCredentialsException e) {
      logger.error(e, "Use reset() instead of passing empty/null values to identify().");
    }
    kahuna.setUserAttributes(userAttributes);
  }

  @Override public void track(TrackPayload track) {
    super.track(track);

    String event = track.event();
    if ("Viewed Product Category".equalsIgnoreCase(event)) {
      trackViewedProductCategory(track);
    } else if ("Viewed Product".equalsIgnoreCase(event)) {
      trackViewedProduct(track);
      trackViewedProductCategory(track);
    } else if ("Added Product".equalsIgnoreCase(event)) {
      trackAddedProduct(track);
      trackAddedProductCategory(track);
    } else if ("Completed Order".equalsIgnoreCase(event)) {
      trackCompletedOrder(track);
    }

    int quantity = track.properties().getInt("quantity", -1);
    double revenue = track.properties().revenue();
    if (quantity == -1 && revenue == 0) {
      kahuna.trackEvent(event);
    } else {
      // Kahuna requires revenue in cents.
      kahuna.trackEvent(event, quantity, (int) (revenue * 100));
    }
  }

  void trackViewedProductCategory(TrackPayload track) {
    String category = track.properties().category();

    if (isNullOrEmpty(category)) {
      category = NONE;
    }

    Map<String, String> userAttributes = kahuna.getUserAttributes();
    Set<String> categoriesViewed;
    if (userAttributes.containsKey(CATEGORIES_VIEWED)) {
      categoriesViewed = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
          return size() > MAX_CATEGORIES_VIEWED_ENTRIES;
        }
      });
      String serializedCategories = userAttributes.get(CATEGORIES_VIEWED);
      String[] categories = serializedCategories.split(",");
      categoriesViewed.addAll(Arrays.asList(categories));
    } else {
      categoriesViewed = new HashSet<>();
    }
    categoriesViewed.add(category);

    userAttributes.put(CATEGORIES_VIEWED, TextUtils.join(",", categoriesViewed));
    userAttributes.put(LAST_VIEWED_CATEGORY, category);
    kahuna.setUserAttributes(userAttributes);
  }

  void trackViewedProduct(TrackPayload track) {
    String name = track.properties().name();
    if (!isNullOrEmpty(name)) {
      Map<String, String> userAttributes = kahuna.getUserAttributes();
      userAttributes.put(LAST_PRODUCT_VIEWED_NAME, name);
      kahuna.setUserAttributes(userAttributes);
    }
  }

  void trackAddedProduct(TrackPayload track) {
    String name = track.properties().name();
    if (!isNullOrEmpty(name)) {
      Map<String, String> userAttributes = kahuna.getUserAttributes();
      userAttributes.put(LAST_PRODUCT_ADDED_TO_CART_NAME, name);
      kahuna.setUserAttributes(userAttributes);
    }
  }

  void trackAddedProductCategory(TrackPayload track) {
    String category = track.properties().category();
    if (!isNullOrEmpty(category)) {
      Map<String, String> userAttributes = kahuna.getUserAttributes();
      userAttributes.put(LAST_PRODUCT_ADDED_TO_CART_CATEGORY, category);
      kahuna.setUserAttributes(userAttributes);
    }
  }

  void trackCompletedOrder(TrackPayload track) {
    double discount = track.properties().discount();
    Map<String, String> userAttributes = kahuna.getUserAttributes();
    userAttributes.put(LAST_PURCHASE_DISCOUNT, String.valueOf(discount));
    kahuna.setUserAttributes(userAttributes);
  }

  @Override public void screen(ScreenPayload screen) {
    super.screen(screen);

    if (trackAllPages) {
      kahuna.trackEvent(String.format("Viewed %s Screen", screen.event()));
    }
  }

  @Override public void reset() {
    super.reset();
    kahuna.logout();
  }
}
