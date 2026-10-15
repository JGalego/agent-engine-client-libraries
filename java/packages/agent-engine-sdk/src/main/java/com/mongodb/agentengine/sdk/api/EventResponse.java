package com.mongodb.agentengine.sdk.api;

import com.mongodb.agentengine.sdk.Event;

/** Response wrapping a single platform event. */
public record EventResponse(Event event) {}
