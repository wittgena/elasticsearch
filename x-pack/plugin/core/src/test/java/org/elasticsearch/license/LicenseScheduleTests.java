/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.license;

import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.scheduler.SchedulerEngine;
import org.junit.Before;

import static org.hamcrest.Matchers.equalTo;

public class LicenseScheduleTests extends ESTestCase {

    private License license;
    private SchedulerEngine.Schedule schedule;

    @Before
    public void setuo() throws Exception {
        license = TestUtils.generateSignedLicense(TimeValue.timeValueHours(12));
        schedule = LicenseService.nextLicenseCheck(license);
    }

    public void testEnabledLicenseSchedule() throws Exception {
        int expiryDuration = (int) (license.expiryDate() - license.issueDate());
        long triggeredTime = license.issueDate() + between(0, expiryDuration);
        assertThat(schedule.nextScheduledTimeAfter(license.issueDate(), triggeredTime), equalTo(license.expiryDate()));
    }

    public void testExpiredLicenseSchedule() throws Exception {
        long triggeredTime = license.expiryDate() + randomIntBetween(1, 1000);
        assertThat(schedule.nextScheduledTimeAfter(license.issueDate(), triggeredTime),
                equalTo(-1L));
    }

    public void testInvalidLicenseSchedule() throws Exception {
        long triggeredTime = license.issueDate() - randomIntBetween(1, 1000);
        assertThat(schedule.nextScheduledTimeAfter(triggeredTime, triggeredTime),
                equalTo(license.issueDate()));
    }
}
