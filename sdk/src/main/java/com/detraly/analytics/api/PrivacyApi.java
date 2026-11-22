package com.detraly.analytics.api;

import java.util.UUID;

public interface PrivacyApi {
    enum Result {
        OK,
        BAD_REQUEST,
        UNAUTHORIZED,
        FORBIDDEN,
        RETRY_LATER,
        NETWORK_ERROR,
        NOT_LINKED
    }

    Result setOptedOut(String token, UUID uuid, boolean optedOut);
}
