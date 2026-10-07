package com.meetingautomation.api.health;

/** Optional health source for an independently implemented integration area. */
public interface IntegrationHealthContributor {
    IntegrationArea area();

    IntegrationHealthStatus health();
}
