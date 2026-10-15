package com.mongodb.agentengine.sdk.api;

import com.mongodb.agentengine.sdk.Event;
import java.util.List;

/** Response wrapping a list of platform events. */
public record EventsResponse(List<Event> events) {}
