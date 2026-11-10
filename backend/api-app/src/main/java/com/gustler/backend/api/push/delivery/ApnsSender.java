package com.gustler.backend.api.push.delivery;

import com.gustler.backend.api.push.application.NotificationDeliveryRepository.ClaimedDelivery;

interface ApnsSender {
    ApnsResponse send(ClaimedDelivery delivery);
}
