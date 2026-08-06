package com.jobtrail.domain;

/** Lifecycle of one physical email in the send queue. */
public enum MessageStatus {
    QUEUED,
    SENDING,
    SENT,
    FAILED,
    CANCELLED
}
