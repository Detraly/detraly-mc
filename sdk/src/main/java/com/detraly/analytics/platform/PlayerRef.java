package com.detraly.analytics.platform;

import java.net.InetSocketAddress;
import java.util.UUID;

public interface PlayerRef {
    String name();

    UUID uuid();

    InetSocketAddress address();

    boolean isOnline();

    void sendPrivacyNotice();
}
