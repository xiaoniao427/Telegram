package org.telegram.messenger.chromecast;

import android.content.Context;

import androidx.annotation.NonNull;


import java.util.List;

public class ChromecastOptionsProvider implements OptionsProvider {
    private static final CastOptions castOptions = new CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .build();

    @NonNull
    @Override
    public CastOptions getCastOptions(@NonNull Context context) {
        return castOptions;
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(@NonNull Context context) {
        return null;
    }
}
