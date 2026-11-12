package com.gustler.backend.api.push.delivery;

import com.gustler.backend.api.push.domain.DeliveryOutcome;

record ApnsResponse(DeliveryOutcome outcome, String reason) {
}
