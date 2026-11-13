package org.coryd.ops.dto;

/** Whose fault an ops cancellation was. RIDER can keep the late-cancellation fee back; DRIVER and PLATFORM refund in full. */
public enum CancellationFault {
    DRIVER,
    RIDER,
    PLATFORM
}
